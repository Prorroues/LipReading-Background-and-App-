package com.rokid.cxrmsamples.services.router

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.sqrt

/**
 * 本地语义路由器
 * 使用ONNX Runtime进行离线意图识别
 */
class LocalSemanticRouter(private val context: Context) {
    private val TAG = "LocalSemanticRouter"

    data class RouteResult(
        val intent: IntentType,
        val score: Float,
        val threshold: Float
    )

    companion object {
        private const val DEFAULT_THRESHOLD = 0.75f
    }

    var matchThreshold: Float = DEFAULT_THRESHOLD
    
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var vocab: Map<String, Int>? = null
    private var isInitialized = false
    
    /**
     * 意图类型
     */
    enum class IntentType {
        DEVICE_OPERATION,  // 设备操作（拍照、音量、亮度等）
        WEATHER_QUERY,     // 天气查询
        LOCATION_QUERY,    // 定位/位置查询
        LIP_READING,       // 唇语识别
        CLOUD_CHAT,        // 复杂对话（需要云端LLM）
        UNKNOWN            // 未知
    }
    
    /**
     * 锚点库：标准例句及其对应的意图类型
     */
    private val anchors = mapOf(
        IntentType.DEVICE_OPERATION to listOf(
            "拍照", "拍个照", "拍一张", "照片", "截图", "拍摄",
            "开始录像", "停止录像", "录像", "录视频", "停止"
        ),
        IntentType.WEATHER_QUERY to listOf(
            "天气", "今天天气", "明天天气", "气温", "下雨",
            "温度", "天气预报", "天气怎么样", "天气如何"
        ),
        IntentType.LOCATION_QUERY to listOf(
            "定位", "位置", "我在哪", "我在哪里",
            "当前位置", "现在在哪", "在哪儿"
        ),
        IntentType.LIP_READING to listOf(
            "唇语识别", "唇语", "读唇", "读唇语",
            "看嘴", "看嘴型", "嘴型识别", "口型识别"
        ),
        IntentType.CLOUD_CHAT to listOf(
            "解释", "分析", "为什么", "如何", "告诉我",
            "什么是", "介绍一下", "详细说明"
        )
    )
    
    /**
     * 初始化：加载ONNX模型和词表
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始初始化LocalSemanticRouter")
            
            // 加载词表
            val vocabMap = loadVocab()
            if (vocabMap == null) {
                Log.w(TAG, "词表加载失败，将使用降级策略")
                isInitialized = false
                return@withContext false
            }
            vocab = vocabMap
            Log.d(TAG, "词表加载成功，词汇量: ${vocabMap.size}")
            
            // 初始化ONNX Runtime环境
            ortEnvironment = OrtEnvironment.getEnvironment()
            
            // 加载模型
            try {
                val modelStream = context.assets.open("all-MiniLM-L6-v2-quantized.onnx")
                val modelBytes = modelStream.readBytes()
                modelStream.close()
                
                val sessionOptions = OrtSession.SessionOptions()
                ortSession = ortEnvironment!!.createSession(modelBytes, sessionOptions)
                Log.d(TAG, "ONNX模型加载成功")
                
                isInitialized = true
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "ONNX模型加载失败: ${e.message}", e)
                Log.w(TAG, "将使用降级策略（关键词匹配）")
                isInitialized = false
                return@withContext false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败", e)
            isInitialized = false
            return@withContext false
        }
    }
    
    /**
     * 加载词表文件
     */
    private suspend fun loadVocab(): Map<String, Int>? = withContext(Dispatchers.IO) {
        try {
            val vocabStream = context.assets.open("vocab.txt")
            val reader = BufferedReader(InputStreamReader(vocabStream, "UTF-8"))
            val vocabMap = mutableMapOf<String, Int>()
            
            var index = 0
            reader.useLines { lines ->
                lines.forEach { line ->
                    val token = line.trim()
                    if (token.isNotEmpty()) {
                        vocabMap[token] = index++
                    }
                }
            }
            
            vocabStream.close()
            return@withContext vocabMap
        } catch (e: Exception) {
            Log.e(TAG, "加载词表失败: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * 文本转向量（使用ONNX模型）
     */
    private suspend fun textToVector(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (!isInitialized || ortSession == null || vocab == null) {
            return@withContext null
        }
        
        try {
            // Tokenization（简化版，实际应该使用更完整的tokenizer）
            val tokens = tokenize(text)
            val tokenIds = tokens.mapNotNull { vocab!![it] }.toIntArray()
            
            if (tokenIds.isEmpty()) {
                return@withContext null
            }
            
            // 将IntArray转换为LongArray（ONNX Runtime需要Long类型）
            val tokenIdsLong = tokenIds.map { it.toLong() }.toLongArray()
            // 使用正确的API创建tensor - ONNX Runtime需要二维数组 [batch][sequence]
            val inputArray = arrayOf(tokenIdsLong)
            val inputTensor = OnnxTensor.createTensor(ortEnvironment!!, inputArray)
            
            // 运行推理
            val inputs = mapOf("input_ids" to inputTensor)
            val outputs = ortSession!!.run(inputs)
            
            // 获取输出（通常是pooler_output或last_hidden_state）
            val outputValue = outputs[0].value
            val outputTensor = outputValue as Array<Array<FloatArray>>
            val embedding = outputTensor[0][0] // 取第一个batch，第一个token的embedding
            
            // 清理资源
            inputTensor.close()
            // outputs是OrtSession.Result，不需要close，会自动清理
            
            return@withContext embedding
        } catch (e: Exception) {
            Log.e(TAG, "文本转向量失败: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * 简化的tokenization（实际应该使用完整的BERT tokenizer）
     */
    private fun tokenize(text: String): List<String> {
        // 简化版：按字符和常见分隔符分割
        val tokens = mutableListOf<String>()
        var currentToken = StringBuilder()
        
        for (char in text) {
            when {
                char.isWhitespace() -> {
                    if (currentToken.isNotEmpty()) {
                        tokens.add(currentToken.toString())
                        currentToken.clear()
                    }
                }
                char in "，。！？；：、".toCharArray() -> {
                    if (currentToken.isNotEmpty()) {
                        tokens.add(currentToken.toString())
                        currentToken.clear()
                    }
                }
                else -> {
                    currentToken.append(char)
                }
            }
        }
        
        if (currentToken.isNotEmpty()) {
            tokens.add(currentToken.toString())
        }
        
        // 添加特殊token
        tokens.add(0, "[CLS]")
        tokens.add("[SEP]")
        
        return tokens
    }
    
    /**
     * 计算余弦相似度
     */
    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) {
            return 0f
        }
        
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f
        
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }
        
        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0) dotProduct / denominator else 0f
    }
    
    /**
     * 意图路由：判断用户输入的意图类型
     */
    suspend fun routeIntent(text: String): RouteResult = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext RouteResult(IntentType.UNKNOWN, 0f, matchThreshold)
        }
        
        // 如果模型未初始化，使用降级策略（关键词匹配）
        if (!isInitialized) {
            return@withContext routeIntentFallback(text)
        }
        
        try {
            // 获取输入文本的向量
            val inputVector = textToVector(text) ?: return@withContext routeIntentFallback(text)
            
            // 计算与各锚点的相似度
            var maxSimilarity = 0f
            var bestIntent = IntentType.UNKNOWN
            
            for ((intentType, anchorTexts) in anchors) {
                for (anchorText in anchorTexts) {
                    val anchorVector = textToVector(anchorText)
                    if (anchorVector != null) {
                        val similarity = cosineSimilarity(inputVector, anchorVector)
                        if (similarity > maxSimilarity) {
                            maxSimilarity = similarity
                            bestIntent = intentType
                        }
                    }
                }
            }
            
            Log.d(TAG, "路由结果: $bestIntent (相似度: $maxSimilarity, 阈值: $matchThreshold)")
            return@withContext RouteResult(bestIntent, maxSimilarity, matchThreshold)
            
        } catch (e: Exception) {
            Log.e(TAG, "意图路由失败，使用降级策略", e)
            return@withContext routeIntentFallback(text)
        }
    }
    
    /**
     * 降级策略：关键词匹配
     */
    private fun routeIntentFallback(text: String): RouteResult {
        // 设备操作关键词
        val deviceKeywords = listOf(
            "拍照", "拍个照", "拍一张", "照片", "截图", "拍摄",
            "录像", "录视频", "开始录像", "停止录像", "停止"
        )
        
        // 天气查询关键词
        val weatherKeywords = listOf(
            "天气", "气温", "温度", "下雨", "天气预报"
        )

        val locationKeywords = listOf(
            "定位", "位置", "我在哪", "我在哪里", "当前位置", "在哪"
        )

        val lipReadingKeywords = listOf(
            "唇语", "唇语识别", "读唇", "读唇语",
            "看嘴", "看嘴型", "嘴型识别", "口型识别"
        )
        
        // 检查设备操作
        if (deviceKeywords.any { text.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "降级策略：检测到设备操作意图")
            return RouteResult(IntentType.DEVICE_OPERATION, 1f, matchThreshold)
        }
        
        // 检查天气查询
        if (weatherKeywords.any { text.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "降级策略：检测到天气查询意图")
            return RouteResult(IntentType.WEATHER_QUERY, 1f, matchThreshold)
        }

        if (locationKeywords.any { text.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "降级策略：检测到定位查询意图")
            return RouteResult(IntentType.LOCATION_QUERY, 1f, matchThreshold)
        }

        if (lipReadingKeywords.any { text.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "降级策略：检测到唇语识别意图")
            return RouteResult(IntentType.LIP_READING, 1f, matchThreshold)
        }
        
        // 默认返回云端对话
        Log.d(TAG, "降级策略：默认返回云端对话")
        return RouteResult(IntentType.CLOUD_CHAT, 0f, matchThreshold)
    }
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            ortSession?.close()
            ortSession = null
            ortEnvironment = null
            vocab = null
            isInitialized = false
            Log.d(TAG, "资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        }
    }
}
