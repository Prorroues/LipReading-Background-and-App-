package com.rokid.cxrmsamples.models

/**
 * 消息角色
 */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

enum class MessageMediaType {
    PHOTO,
    VIDEO
}

/**
 * 消息数据类
 */
data class Message(
    val role: MessageRole,
    val content: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val mediaType: MessageMediaType? = null,
    val mediaPath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 转换为API格式
     */
    fun toApiFormat(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["role"] = when (role) {
            MessageRole.SYSTEM -> "system"
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.TOOL -> "tool"
        }
        
        content?.let { map["content"] = it }
        name?.let { map["name"] = it }
        toolCallId?.let { map["tool_call_id"] = it }
        toolCalls?.let { 
            map["tool_calls"] = it.map { call -> call.toMap() }
        }
        
        return map
    }
}

/**
 * 工具调用
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "type" to type,
            "function" to mapOf(
                "name" to function.name,
                "arguments" to function.arguments
            )
        )
    }
}

/**
 * 函数调用
 */
data class FunctionCall(
    val name: String,
    val arguments: String  // JSON格式的参数
)
