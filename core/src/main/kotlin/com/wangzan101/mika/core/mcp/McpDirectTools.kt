package com.wangzan101.mika.core.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 外部 Agent 直连手机动作的来源:不经手机端模型,由 app 侧实现(在进程里养一条无模型的工具会话)。
 * core 只认识这个接口:给哪些工具、怎么执行,都由实现方(端口与会话的装配)决定。
 * 工具名与 Mika 内部手机工具同名,参数模式原样,执行结果原样透传。
 */
interface DirectPhoneControl {
    /** 直连工具的一个定义:名字、说明、参数模式(JSON Schema 对象)、是否只读。与会话状态无关,随时可给。 */
    data class Spec(val name: String, val description: String, val schema: JsonObject, val readOnly: Boolean)

    fun specs(): List<Spec>

    /** 执行一次直连工具调用;参数内容不对、动作失败时抛 [McpTaskTools.ToolError]。 */
    suspend fun call(name: String, args: JsonObject): JsonObject
}

/** 把 [DirectPhoneControl] 包成 MCP 工具表的一项来源,放法与 [McpDeviceTools] 一致。 */
internal class McpDirectTools(private val direct: DirectPhoneControl) {
    val tools: List<McpTaskTools.Tool> by lazy {
        direct.specs().map { s -> McpTaskTools.Tool(s.name, s.description, s.schema, s.readOnly) }
    }

    fun has(name: String): Boolean = tools.any { it.name == name }

    suspend fun call(name: String, args: JsonObject): JsonObject {
        val out = direct.call(name, args)
        // 动作层的失败以 {ok:false, error:...} 回来:在 MCP 层转成工具错误,免得对面的 Agent 当成功。
        val ok = (out["ok"] as? JsonPrimitive)?.contentOrNull
        if (ok == "false") {
            val why = (out["message"] as? JsonPrimitive)?.contentOrNull
                ?: (out["error"] as? JsonPrimitive)?.contentOrNull
                ?: out.toString()
            throw McpTaskTools.ToolError(why)
        }
        return out
    }
}
