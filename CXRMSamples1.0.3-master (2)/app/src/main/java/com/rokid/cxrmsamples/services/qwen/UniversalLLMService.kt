package com.rokid.cxrmsamples.services.qwen

import android.util.Log
import com.google.gson.Gson
import com.rokid.cxrmsamples.models.LLMConfig
import com.rokid.cxrmsamples.models.Message
import com.rokid.cxrmsamples.models.ToolDefinitions
import com.rokid.cxrmsamples.services.qwen.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * 通用LLM API服务
 * 支持多个厂商的OpenAI兼容API
 */
class UniversalLLMService(private val config: LLMConfig) {
    private val TAG = "UniversalLLMService"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val sseParser = SSEResponseParser()
    
    /**
     * 发送聊天请求（非流式）
     */
    suspend fun chat(
        messages: List<Message>,
        enableTools: Boolean = true,
        temperature: Double = 0.7
    ): QwenChatResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "发送聊天请求到 ${config.provider.displayName}，消息数量: ${messages.size}")
            
            val requestBody = QwenChatRequest(
                model = config.textModel,
                messages = messages.map { it.toApiFormat() },
                tools = if (enableTools && config.provider.supportsFunctionCalling) {
                    ToolDefinitions.toApiFormat()
                } else null,
                stream = false,
                maxTokens = 2000,
                temperature = temperature
            )
            
            val jsonBody = gson.toJson(requestBody)
            Log.d(TAG, "请求体: ${jsonBody.take(500)}...")
            
            val request = Request.Builder()
                .url("${config.baseUrl}/chat/completions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "API请求失败: ${response.code}, ${response.message}")
                Log.e(TAG, "响应体: $responseBody")
                throw Exception("API请求失败 [${config.provider.displayName}]: ${response.code} ${response.message}")
            }
            
            Log.d(TAG, "响应成功: ${responseBody?.take(500)}...")
            
            val chatResponse = gson.fromJson(responseBody, QwenChatResponse::class.java)
            Log.d(TAG, "解析响应成功，choices数量: ${chatResponse.choices.size}")
            
            // 记录Token使用情况
            chatResponse.usage?.let {
                Log.d(TAG, "Token使用 [${config.provider.displayName}]: 输入=${it.promptTokens}, 输出=${it.completionTokens}, 总计=${it.totalTokens}")
            }
            
            chatResponse
        } catch (e: Exception) {
            Log.e(TAG, "聊天请求异常 [${config.provider.displayName}]", e)
            throw e
        }
    }
    
    /**
     * 发送聊天请求（流式，返回Flow）
     */
    fun chatStreaming(
        messages: List<Message>,
        enableTools: Boolean = true,
        temperature: Double = 0.7
    ): Flow<String> = flow {
        try {
            Log.d(TAG, "发送流式聊天请求到 ${config.provider.displayName}，消息数量: ${messages.size}")
            
            val requestBody = QwenChatRequest(
                model = config.textModel,
                messages = messages.map { it.toApiFormat() },
                tools = if (enableTools && config.provider.supportsFunctionCalling) {
                    ToolDefinitions.toApiFormat()
                } else null,
                stream = true,
                maxTokens = 2000,
                temperature = temperature
            )
            
            val jsonBody = gson.toJson(requestBody)
            Log.d(TAG, "流式请求体: ${jsonBody.take(500)}...")
            
            val request = Request.Builder()
                .url("${config.baseUrl}/chat/completions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e(TAG, "流式请求失败: ${response.code}, $errorBody")
                throw Exception("API请求失败 [${config.provider.displayName}]: ${response.code} ${response.message}")
            }
            
            // 使用SSEResponseParser解析流
            val responseBody = response.body
            if (responseBody != null) {
                sseParser.parseStream(responseBody).collect { chunk ->
                    emit(chunk)
                }
            } else {
                throw Exception("响应体为空")
            }
            
            Log.d(TAG, "流式响应完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "流式聊天请求异常 [${config.provider.displayName}]", e)
            throw e
        }
    }
    
    /**
     * 发送聊天请求（流式，回调方式，保留兼容性）
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
            Log.d(TAG, "发送流式聊天请求到 ${config.provider.displayName}")
            
            val requestBody = QwenChatRequest(
                model = config.textModel,
                messages = messages.map { it.toApiFormat() },
                tools = if (enableTools && config.provider.supportsFunctionCalling) {
                    ToolDefinitions.toApiFormat()
                } else null,
                stream = true,
                maxTokens = 2000,
                temperature = temperature
            )
            
            val jsonBody = gson.toJson(requestBody)
            
            val request = Request.Builder()
                .url("${config.baseUrl}/chat/completions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e(TAG, "流式请求失败: ${response.code}, $errorBody")
                onError(Exception("API请求失败 [${config.provider.displayName}]: ${response.code}"))
                return@withContext
            }
            
            // 解析Server-Sent Events (SSE)流
            response.body?.byteStream()?.bufferedReader()?.use { reader ->
                parseSSEStream(reader, onChunk, onComplete, onError)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "流式聊天请求异常 [${config.provider.displayName}]", e)
            onError(e)
        }
    }
    
    /**
     * 发送多模态请求（图片+文本）
     */
    suspend fun analyzeImage(
        imageBase64: String,
        question: String,
        systemPrompt: String = buildVisionSystemPrompt()
    ): QwenChatResponse = withContext(Dispatchers.IO) {
        try {
            if (!config.provider.supportsVision) {
                throw Exception("${config.provider.displayName} 不支持视觉功能")
            }
            
            Log.d(TAG, "发送视觉分析请求到 ${config.provider.displayName}")
            Log.d(TAG, "图片大小: ${imageBase64.length / 1024}KB")
            
            // 构建多模态消息
            val messages = listOf(
                mapOf(
                    "role" to "system",
                    "content" to systemPrompt
                ),
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to imageBase64
                            )
                        ),
                        mapOf(
                            "type" to "text",
                            "text" to question
                        )
                    )
                )
            )
            
            val requestBody = mapOf(
                "model" to config.visionModel,
                "messages" to messages,
                "max_tokens" to 1500,
                "temperature" to 0.7
            )
            
            val jsonBody = gson.toJson(requestBody)
            
            val request = Request.Builder()
                .url("${config.baseUrl}/chat/completions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            Log.d(TAG, "开始发送视觉API请求...")
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "视觉API请求失败: ${response.code}, ${response.message}")
                Log.e(TAG, "响应体: $responseBody")
                throw Exception("视觉API请求失败 [${config.provider.displayName}]: ${response.code}")
            }
            
            Log.d(TAG, "响应成功: ${responseBody?.take(300)}...")
            
            val chatResponse = gson.fromJson(responseBody, QwenChatResponse::class.java)
            Log.d(TAG, "视觉分析完成，回复: ${chatResponse.choices.firstOrNull()?.message?.content?.take(100)}")
            
            chatResponse
        } catch (e: Exception) {
            Log.e(TAG, "视觉分析请求异常 [${config.provider.displayName}]", e)
            throw e
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
                
                if (currentLine.startsWith("data: ")) {
                    val jsonData = currentLine.substring(6).trim()
                    
                    if (jsonData == "[DONE]") {
                        Log.d(TAG, "流式响应完成")
                        onComplete()
                        break
                    }
                    
                    try {
                        val chunk = gson.fromJson(jsonData, QwenChatChunk::class.java)
                        val delta = chunk.choices.firstOrNull()?.delta
                        
                        delta?.content?.let { content ->
                            if (content.isNotEmpty()) {
                                contentBuilder.append(content)
                                onChunk(content)
                            }
                        }
                        
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
        // 优化：禁止思维链输出，直接回答结果
        return """你是Rokid智能眼镜的语音助手，你的名字是"小乐"。你的职责是：
1. 理解用户的语音指令，并给出友好、简洁的回复
2. 当用户需要调整设备设置（如音量、亮度）时，调用相应的工具函数
3. 当用户询问视觉相关问题（如"前面有什么"）时，使用拍照工具获取视觉信息
4. 当用户询问天气时，使用天气查询工具
5. 保持回复简短（30字以内），适合语音播报
6. 用友好、自然的语气交流

重要：直接回答结果，禁止输出思维链或分析过程。不要解释你的思考过程，直接给出答案。

当前可用工具：
- adjust_volume: 调整音量
- adjust_brightness: 调整屏幕亮度
- take_photo_and_analyze: 拍照并分析图片内容
- control_video_recording: 控制视频录制（开始/停止）
- perform_lip_reading: 执行唇语识别（录像并上传服务器识别）
- query_weather: 查询天气信息（可指定城市或使用当前位置）
"""
    }
    
    /**
     * 构建视觉任务的System Prompt
     */
    private fun buildVisionSystemPrompt(): String {
        return """你是Rokid智能眼镜的视觉助手。你的任务是：
1. 识别图片中的物体、场景、文字等信息
2. 用简洁、准确的语言描述所见内容（30字以内）
3. 优先回答用户的具体问题
4. 如果看到危险情况，及时提醒用户

重要：直接回答结果，禁止输出思维链或分析过程。不要解释你的识别过程，直接描述看到的内容。"""
    }
}
