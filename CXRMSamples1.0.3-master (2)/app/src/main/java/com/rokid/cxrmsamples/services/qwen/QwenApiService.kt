package com.rokid.cxrmsamples.services.qwen

import android.util.Log
import com.google.gson.Gson
import com.rokid.cxrmsamples.models.Message
import com.rokid.cxrmsamples.models.ToolDefinitions
import com.rokid.cxrmsamples.services.qwen.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * 阿里云千问API服务（文本对话）
 * 支持标准对话和Function Calling
 */
class QwenApiService(
    private val apiKey: String,
    private val model: String = "qwen-max"
) {
    private val TAG = "QwenApiService"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    
    /**
     * 发送聊天请求（非流式）
     */
    suspend fun chat(
        messages: List<Message>,
        enableTools: Boolean = true,
        temperature: Double = 0.7
    ): QwenChatResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "发送聊天请求，消息数量: ${messages.size}, 启用工具: $enableTools")
            
            val requestBody = QwenChatRequest(
                model = model,
                messages = messages.map { it.toApiFormat() },
                tools = if (enableTools) ToolDefinitions.toApiFormat() else null,
                stream = false,
                maxTokens = 2000,
                temperature = temperature
            )
            
            val jsonBody = gson.toJson(requestBody)
            Log.d(TAG, "请求体: ${jsonBody.take(500)}...")
            
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "API请求失败: ${response.code}, ${response.message}")
                Log.e(TAG, "响应体: $responseBody")
                throw Exception("API请求失败: ${response.code} ${response.message}")
            }
            
            Log.d(TAG, "响应成功: ${responseBody?.take(500)}...")
            
            val chatResponse = gson.fromJson(responseBody, QwenChatResponse::class.java)
            Log.d(TAG, "解析响应成功，choices数量: ${chatResponse.choices.size}")
            
            // 记录Token使用情况
            chatResponse.usage?.let {
                Log.d(TAG, "Token使用: 输入=${it.promptTokens}, 输出=${it.completionTokens}, 总计=${it.totalTokens}")
            }
            
            chatResponse
        } catch (e: Exception) {
            Log.e(TAG, "聊天请求异常", e)
            throw e
        }
    }
    
    /**
     * 发送聊天请求（流式）
     * @param onChunk 接收每个流式块的回调
     * @param onComplete 完成回调
     * @param onError 错误回调
     */
    suspend fun chatStream(
        messages: List<Message>,
        enableTools: Boolean = true,
        temperature: Double = 0.7,
        onChunk: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "发送流式聊天请求，消息数量: ${messages.size}")
            
            val requestBody = QwenChatRequest(
                model = model,
                messages = messages.map { it.toApiFormat() },
                tools = if (enableTools) ToolDefinitions.toApiFormat() else null,
                stream = true,
                maxTokens = 2000,
                temperature = temperature
            )
            
            val jsonBody = gson.toJson(requestBody)
            
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e(TAG, "流式请求失败: ${response.code}, $errorBody")
                onError(Exception("API请求失败: ${response.code}"))
                return@withContext
            }
            
            // 解析Server-Sent Events (SSE)流
            response.body?.byteStream()?.bufferedReader()?.use { reader ->
                parseSSEStream(reader, onChunk, onComplete, onError)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "流式聊天请求异常", e)
            onError(e)
        }
    }
    
    /**
     * 解析SSE流
     */
    private fun parseSSEStream(
        reader: BufferedReader,
        onChunk: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            var line: String?
            val contentBuilder = StringBuilder()
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                
                // SSE格式: data: {...}
                if (currentLine.startsWith("data: ")) {
                    val jsonData = currentLine.substring(6).trim()
                    
                    // 结束标记
                    if (jsonData == "[DONE]") {
                        Log.d(TAG, "流式响应完成")
                        onComplete()
                        break
                    }
                    
                    try {
                        // 解析流式块
                        val chunk = gson.fromJson(jsonData, QwenChatChunk::class.java)
                        val delta = chunk.choices.firstOrNull()?.delta
                        
                        delta?.content?.let { content ->
                            if (content.isNotEmpty()) {
                                contentBuilder.append(content)
                                onChunk(content)
                            }
                        }
                        
                        // 检查是否完成
                        chunk.choices.firstOrNull()?.finishReason?.let {
                            if (it == "stop" || it == "tool_calls") {
                                Log.d(TAG, "流式响应完成，原因: $it")
                                onComplete()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析流式数据块失败: $jsonData", e)
                    }
                }
            }
            
            Log.d(TAG, "流式读取完成，总内容长度: ${contentBuilder.length}")
            
        } catch (e: Exception) {
            Log.e(TAG, "解析SSE流异常", e)
            onError(e)
        }
    }
    
    /**
     * 构建System Prompt
     */
    fun buildSystemPrompt(): String {
        return """## 角色设定
你叫“小乐”，是Rokid智能眼镜的语音助手。你的载体是佩戴在用户眼前的智能眼镜，请始终保持“高效、聪明、贴心”的形象。

## 核心原则
1. 结果导向：直接输出回复或工具调用指令，严禁输出思维链或推理过程。
2. 语气风格：自然、像朋友一样对话（口语化），避免像机器人或写长篇大论。
3. 真实性：严禁编造工具的返回结果。不知道就说“无法确认”。
4. 追问机制：如果无法执行指令，仅追问1个最核心的问题。

## 工具能力 (Tools)
当用户意图涉及以下功能时，必须调用对应工具函数（不要输出固定格式文本）：
- 视觉/环境感知：
  - take_photo_and_analyze: 用户问“前面是什么”、“读一下这个”、“我的车在哪”等涉及视觉的问题。
  - perform_lip_reading: 用户环境嘈杂或要求读唇语时。
- 硬件控制：
  - adjust_volume: 调大/调小声音。
  - adjust_brightness: 调亮/调暗屏幕。
  - control_video_recording: 开始或停止录像。
- 信息查询：
  - query_weather: 问天气（默认使用当前定位）。

## 响应逻辑
1. 视觉优先：涉及“看”、“读”、“环境”的问题，直接调用 take_photo_and_analyze。
2. 本地优先：涉及地点、天气的，直接调用 query_weather。
3. 闲聊与知识：若无须工具，直接用简练的中文回答，200字以内。

## 示例 (Few-Shot)
User: 今天出门要带伞吗？
Assistant: 调用query_weather

User: 帮我看看这瓶药怎么吃。
Assistant: 调用take_photo_and_analyze

User: 声音太吵了。
Assistant: 调用adjust_volume

User: 给我讲个笑话。
Assistant: 有个程序员买了一斤肉，他拿起刀切了一刀，说：“这就是切片技术。”

User: 帮我定个明天早上8点的闹钟。
Assistant: 目前我还没学会定闹钟的功能，需要我帮你记录到备忘录吗？

## 当前对话
User: {{user_input}}
Assistant:
"""
    }
}
