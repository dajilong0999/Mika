package com.wangzan101.mika.mcp

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.PhoneUnavailable
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.mcp.DirectPhoneControl
import com.wangzan101.mika.core.mcp.McpServer
import com.wangzan101.mika.core.mcp.McpTaskTools
import com.wangzan101.mika.core.mcp.McpToolSession
import com.wangzan101.mika.core.shell.AccessibilityRepair
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.settings.Prerequisites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 外部 Agent 直连手机:在本进程里养一条无模型的工具会话([McpServer.openToolSession]),
 * 会话的工具就是 Mika 自己的手机工具(白名单子集),由外部 Agent 一步步调用,不经手机端模型、不需要任何 API Key。
 * 端口链仿自检(AndroidSelfCheck)与任务(TaskRun)的应用访问层:共用助手连接、等无障碍、自建虚拟屏,
 * 外包一层 [AppAccessScreenPort],用户在设置里禁用的 App 直连同样进不去。
 * 会话长驻、跨调用复用(读屏的 snapshotId 与元素编号在会话内连续有效);进程被杀后下次调用懒重开。
 */
class DirectControl(private val context: Context) : DirectPhoneControl {
    private val app = context.applicationContext as MikaApp
    private val phoneServices by lazy { PhoneServices.of(context) }

    private var connection: ShellBinding? = null
    private var builtScreen: AndroidScreen? = null

    private val lazyPhone: LazyPhoneScreen by lazy {
        val metrics = displayMetrics()
        LazyPhoneScreen(metrics.widthPixels, metrics.heightPixels,
            listApps = { withContext(Dispatchers.IO) { launchableApps(context.packageManager, context.packageName) } },
        ) { connect() }
    }

    private val accessed: AppAccessScreenPort by lazy {
        AppAccessScreenPort(lazyPhone, app.appAccess,
            packages = { builtScreen?.accessPackages().orEmpty() },
            protectedUi = { app.appAccess.sensitiveUi }, ownPackage = context.packageName,
            windowToken = { ScreenAccessibility.windowGeneration() })
    }

    private val server: McpServer by lazy {
        McpServer(assemble = { spec -> toolDispatcher(accessed, runSpec = spec) }, tasks = app.remoteTasks)
    }

    @Volatile private var current: McpToolSession? = null

    private fun session(): McpToolSession = current ?: synchronized(this) {
        current ?: server.openToolSession(THREAD_ID).also { current = it }
    }

    override fun specs(): List<DirectPhoneControl.Spec> = session().tools()
        .filter { it.name in WHITELIST }
        .map { t -> DirectPhoneControl.Spec(t.name, t.description,
            json.parseToJsonElement(t.parametersJsonSchema) as JsonObject, t.name in READ_ONLY) }

    override suspend fun call(name: String, args: JsonObject): JsonObject {
        if (name !in WHITELIST) throw McpTaskTools.ToolError("Unknown tool: $name")
        return session().call(name, args.toString())
    }

    /** 接上手机:连内置助手、等无障碍、备好(用到才建的)虚拟屏;缺一样报 [PhoneUnavailable],由 [LazyPhoneScreen] 转成带提示的失败。 */
    private suspend fun connect(): ScreenPort {
        if (Build.VERSION.SDK_INT < Prerequisites.MIN_SDK) {
            throw PhoneUnavailable("This Android version is too old for phone control.")
        }
        val conn = connection ?: phoneServices.sharedShell.acquire().also { connection = it }
        val shell = withTimeoutOrNull(SHELL_WAIT_MS) { conn.shell.filterNotNull().first() }
            ?: run {
                phoneServices.setupPrompt.request()
                throw PhoneUnavailable("Mika's built-in helper is not running. Open Mika and finish phone setup (pairing) first.")
            }
        val a11y = AccessibilityRepair.awaitService(
            ScreenAccessibility.instance, phoneServices.recovery.accessibilityRepair.phase, A11Y_WAIT_MS)
            ?: throw PhoneUnavailable("Mika's accessibility service is not connected. Enable it in system Settings > Accessibility.")
        val metrics = displayMetrics()
        val display = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) {
            AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
        return AndroidScreen(a11y, shell, display, appAccess = app.appAccess).also { builtScreen = it }
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics = DisplayMetrics().also {
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    companion object {
        private const val THREAD_ID = "direct-control"
        private const val SHELL_WAIT_MS = 15_000L
        private const val A11Y_WAIT_MS = 5_000L

        /** 直连开放的手机工具(与 Mika 内部工具同名);其余手机工具(系统命令、分享、闹钟等)本轮不开。 */
        private val WHITELIST = setOf(
            "phone_observe", "phone_list_apps", "phone_launch_app",
            "phone_tap", "phone_swipe", "phone_scroll", "phone_type_text",
            "phone_press_key", "phone_back", "phone_wait",
        )
        private val READ_ONLY = setOf("phone_observe", "phone_list_apps", "phone_wait")

        private val json = Json { ignoreUnknownKeys = true }
    }
}
