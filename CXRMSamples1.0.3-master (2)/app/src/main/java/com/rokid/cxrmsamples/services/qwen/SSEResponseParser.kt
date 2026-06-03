package com.rokid.cxrmsamples.services.qwen

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rokid.cxrmsamples.services.qwen.models.QwenChatChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.ResponseBody
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * SSE (Server-Sent Events) 响应解析器
 * 解析流式响应，提取文本内容
 */
class SSEResponseParser {
    private val TAG = "SSEResponseParser"
    private val gson = Gson()
    
    /**
     * 解析SSE流，返回文本内容的Flow
     * @param responseBody HTTP响应体
     * @return Flow<String> 文本内容流
     */
    fun parseStream(responseBody: ResponseBody): Flow<String> = flow {
        try {
            val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), "UTF-8"))
            var line: String?
            var buffer = StringBuilder()
            
            reader.use { r ->
                while (r.readLine().also { line = it } != null) {
                    val trimmedLine = line!!.trim()
                    
                    // 跳过空行
                    if (trimmedLine.isEmpty()) {
                        continue
                    }
                    
                    // 处理 data: 开头的行
                    if (trimmedLine.startsWith("data: ")) {
                        val dataContent = trimmedLine.substring(6) // 移除 "data: " 前缀
                        
                        // 检查是否是结束标记
                        if (dataContent == "[DONE]") {
                            Log.d(TAG, "收到结束标记 [DONE]")
                            break
                        }
                        
                        try {
                            // 解析JSON数据
                            val jsonObject = JsonParser.parseString(dataContent).asJsonObject
                            
                            // 检查是否有choices数组
                            if (jsonObject.has("choices") && jsonObject.get("choices").isJsonArray) {
                                val choices = jsonObject.getAsJsonArray("choices")
                                
                                if (choices.size() > 0) {
                                    val firstChoice = choices[0].asJsonObject
                                    
                                    // 检查是否有delta字段（流式响应）
                                    if (firstChoice.has("delta")) {
                                        val delta = firstChoice.getAsJsonObject("delta")
                                        
                                        // 提取content字段
                                        if (delta.has("content") && !delta.get("content").isJsonNull) {
                                            val content = delta.get("content").asString
                                            if (content.isNotEmpty()) {
                                                buffer.append(content)
                                                emit(content)
                                                Log.d(TAG, "收到文本块: $content")
                                            }
                                        }
                                        
                                        // 检查是否有finish_reason（表示完成）
                                        if (firstChoice.has("finish_reason") && 
                                            !firstChoice.get("finish_reason").isJsonNull) {
                                            val finishReason = firstChoice.get("finish_reason").asString
                                            if (finishReason != null && finishReason != "null") {
                                                Log.d(TAG, "流式响应完成，原因: $finishReason")
                                                break
                                            }
                                        }
                                    }
                                    
                                    // 兼容非流式响应格式（message字段）
                                    if (firstChoice.has("message")) {
                                        val message = firstChoice.getAsJsonObject("message")
                                        if (message.has("content") && !message.get("content").isJsonNull) {
                                            val content = message.get("content").asString
                                            if (content.isNotEmpty()) {
                                                buffer.append(content)
                                                emit(content)
                                                Log.d(TAG, "收到完整消息: $content")
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // 检查是否有error字段
                            if (jsonObject.has("error")) {
                                val error = jsonObject.getAsJsonObject("error")
                                val errorMessage = error.get("message")?.asString ?: "未知错误"
                                Log.e(TAG, "SSE响应包含错误: $errorMessage")
                                throw Exception("SSE响应错误: $errorMessage")
                            }
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "解析SSE数据失败: ${e.message}", e)
                            // 继续处理下一行，不中断流
                        }
                    }
                }
            }
            
            Log.d(TAG, "SSE流解析完成，总文本长度: ${buffer.length}")
            
        } catch (e: Exception) {
            Log.e(TAG, "解析SSE流失败", e)
            throw e
        }
    }
    
    /**
     * 解析SSE流，返回完整的响应对象（用于调试）
     */
    fun parseStreamToChunks(responseBody: ResponseBody): Flow<QwenChatChunk> = flow {
        try {
            val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), "UTF-8"))
            var line: String?
            
            reader.use { r ->
                while (r.readLine().also { line = it } != null) {
                    val trimmedLine = line!!.trim()
                    
                    if (trimmedLine.isEmpty()) {
                        continue
                    }
                    
                    if (trimmedLine.startsWith("data: ")) {
                        val dataContent = trimmedLine.substring(6)
                        
                        if (dataContent == "[DONE]") {
                            break
                        }
                        
                        try {
                            val chunk = gson.fromJson(dataContent, QwenChatChunk::class.java)
                            emit(chunk)
                        } catch (e: Exception) {
                            Log.e(TAG, "解析SSE块失败: ${e.message}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析SSE流到Chunks失败", e)
            throw e
        }
    }
}
