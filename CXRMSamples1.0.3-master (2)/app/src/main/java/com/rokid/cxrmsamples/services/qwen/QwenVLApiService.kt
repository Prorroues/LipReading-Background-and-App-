package com.rokid.cxrmsamples.services.qwen

import android.util.Log
import com.google.gson.Gson
import com.rokid.cxrmsamples.services.qwen.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 阿里云千问视觉API服务（多模态问答）
 * 支持图片+文本的多模态输入
 */
class QwenVLApiService(
    private val apiKey: String,
    private val model: String = "qwen-vl-max"
) {
    private val TAG = "QwenVLApiService"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)  // 视觉模型响应较慢
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    
    /**
     * 发送多模态请求（图片+文本）
     * @param imageBase64 Base64编码的图片（data:image/jpeg;base64,... 格式）
     * @param question 用户问题
     * @param systemPrompt 系统提示词
     */
    suspend fun analyzeImage(
        imageBase64: String,
        question: String,
        systemPrompt: String = buildSystemPrompt()
    ): QwenChatResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "发送视觉分析请求，问题: $question")
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
                "model" to model,
                "messages" to messages,
                "max_tokens" to 1500,
                "temperature" to 0.7
            )
            
            val jsonBody = gson.toJson(requestBody)
            Log.d(TAG, "请求体大小: ${jsonBody.length / 1024}KB")
            
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            Log.d(TAG, "开始发送视觉API请求...")
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "视觉API请求失败: ${response.code}, ${response.message}")
                Log.e(TAG, "响应体: $responseBody")
                throw Exception("视觉API请求失败: ${response.code} ${response.message}")
            }
            
            Log.d(TAG, "响应成功: ${responseBody?.take(300)}...")
            
            val chatResponse = gson.fromJson(responseBody, QwenChatResponse::class.java)
            Log.d(TAG, "视觉分析完成，回复: ${chatResponse.choices.firstOrNull()?.message?.content?.take(100)}")
            
            // 记录Token使用情况（视觉模型消耗较多）
            chatResponse.usage?.let {
                Log.d(TAG, "Token使用: 输入=${it.promptTokens}, 输出=${it.completionTokens}, 总计=${it.totalTokens}")
            }
            
            chatResponse
        } catch (e: Exception) {
            Log.e(TAG, "视觉分析请求异常", e)
            throw e
        }
    }
    
    /**
     * 批量分析图片（用于连续视觉对话）
     * @param images 多张图片的Base64列表
     * @param question 用户问题
     */
    suspend fun analyzeMultipleImages(
        images: List<String>,
        question: String
    ): QwenChatResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "发送批量视觉分析请求，图片数量: ${images.size}")
            
            // 构建包含多张图片的内容
            val contentList = mutableListOf<Map<String, Any>>()
            
            // 添加所有图片
            images.forEach { imageBase64 ->
                contentList.add(
                    mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to imageBase64)
                    )
                )
            }
            
            // 添加文本问题
            contentList.add(
                mapOf(
                    "type" to "text",
                    "text" to question
                )
            )
            
            val messages = listOf(
                mapOf(
                    "role" to "system",
                    "content" to buildSystemPrompt()
                ),
                mapOf(
                    "role" to "user",
                    "content" to contentList
                )
            )
            
            val requestBody = mapOf(
                "model" to model,
                "messages" to messages,
                "max_tokens" to 2000,
                "temperature" to 0.7
            )
            
            val jsonBody = gson.toJson(requestBody)
            
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                throw Exception("批量视觉API请求失败: ${response.code}")
            }
            
            gson.fromJson(responseBody, QwenChatResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "批量视觉分析异常", e)
            throw e
        }
    }
    
    /**
     * 构建视觉任务的System Prompt
     */
    private fun buildSystemPrompt(): String {
        return """你是Rokid智能眼镜的视觉助手“小乐”。你的任务是：
1. 识别图片中的物体、场景、文字等信息
2. 用简洁、准确的语言描述所见内容（尽量30字内）
3. 优先回答用户的具体问题
4. 若存在危险或异常，简短提醒
5. 不确定时说明“无法确认”

要求：直接给出结论，不要解释过程。"""
    }
}
