package com.rokid.cxrmsamples.services.qwen.models

import com.google.gson.annotations.SerializedName

/**
 * Qwen API请求
 */
data class QwenChatRequest(
    val model: String,
    val messages: List<Map<String, Any>>,
    val tools: List<Map<String, Any>>? = null,
    val stream: Boolean = false,
    @SerializedName("max_tokens")
    val maxTokens: Int = 2000,
    val temperature: Double = 0.7,
    @SerializedName("top_p")
    val topP: Double = 0.9
)

/**
 * Qwen API响应（非流式）
 */
data class QwenChatResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage?,
    val created: Long
)

data class Choice(
    val index: Int,
    val message: MessageResponse,
    @SerializedName("finish_reason")
    val finishReason: String?
)

data class MessageResponse(
    val role: String,
    val content: String?,
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCallResponse>?
)

data class ToolCallResponse(
    val id: String,
    val type: String,
    val function: FunctionResponse
)

data class FunctionResponse(
    val name: String,
    val arguments: String
)

data class Usage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int,
    @SerializedName("completion_tokens")
    val completionTokens: Int,
    @SerializedName("total_tokens")
    val totalTokens: Int
)

/**
 * 流式响应块
 */
data class QwenChatChunk(
    val id: String,
    val choices: List<ChunkChoice>,
    val created: Long
)

data class ChunkChoice(
    val index: Int,
    val delta: DeltaMessage,
    @SerializedName("finish_reason")
    val finishReason: String?
)

data class DeltaMessage(
    val role: String?,
    val content: String?,
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCallResponse>?
)

/**
 * 多模态内容
 */
data class MultiModalContent(
    val type: String,  // "text" or "image_url"
    val text: String? = null,
    @SerializedName("image_url")
    val imageUrl: ImageUrl? = null
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>("type" to type)
        text?.let { map["text"] = it }
        imageUrl?.let { map["image_url"] = mapOf("url" to it.url) }
        return map
    }
}

data class ImageUrl(
    val url: String  // data:image/jpeg;base64,... 或 http://...
)
