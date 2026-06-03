package com.rokid.cxrmsamples.services.assistant

import android.util.Log

/**
 * 意图路由器
 * 分析用户输入，判断是否需要特殊处理（如视觉任务）
 */
class IntentRouter {
    private val TAG = "IntentRouter"
    
    /**
     * 检测是否为视觉意图
     * 优化：更精确的匹配，避免误判
     */
    fun detectVisualIntent(text: String): Boolean {
        // 精确的视觉短语（优先级高）
        val visualPhrases = listOf(
            "看看", "看一下", "看到了",
            "拍照", "拍个照", "照片",
            "前面有什么", "这是什么", "那是什么",
            "帮我看看", "什么东西", "扫描", "扫一下",
            "眼前", "面前是什么", "周围有什么"
        )
        
        // 检查是否包含精确短语
        if (visualPhrases.any { text.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "检测到视觉意图（精确短语）: $text")
            return true
        }
        
        // 单字关键词需要配合其他词（避免误判）
        val singleKeywords = listOf("看", "拍", "认", "识别")
        val contextKeywords = listOf("前面", "后面", "左边", "右边", "这", "那", "什么", "哪")
        
        for (keyword in singleKeywords) {
            if (text.contains(keyword)) {
                // 检查是否有上下文关键词
                if (contextKeywords.any { text.contains(it) }) {
                    Log.d(TAG, "检测到视觉意图（组合匹配）: $text")
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * 检测是否为控制意图
     */
    fun detectControlIntent(text: String): Boolean {
        val controlPhrases = listOf(
            // 音量相关
            "音量", "声音", "大声一点", "小声一点", "静音",
            "调高音量", "调低音量", "音量大", "音量小",
            
            // 亮度相关
            "亮度", "屏幕亮度", "亮一点", "暗一点", "调亮", "调暗",
            "调高亮度", "调低亮度", "屏幕太亮", "屏幕太暗",
            
            // 录像相关
            "录像", "录视频", "开始录", "停止录",
            "录制", "录影"
        )
        
        val hasControlPhrase = controlPhrases.any { 
            text.contains(it, ignoreCase = true) 
        }
        
        if (hasControlPhrase) {
            Log.d(TAG, "检测到控制意图: $text")
        }
        
        return hasControlPhrase
    }
    
    /**
     * 提取视觉问题
     */
    fun extractVisualQuestion(text: String): String {
        // 尝试提取问句
        val questionPatterns = listOf(
            "这是什么",
            "那是什么",
            "前面有什么",
            "看到什么",
            "什么东西"
        )
        
        for (pattern in questionPatterns) {
            if (text.contains(pattern)) {
                return pattern
            }
        }
        
        // 如果没有明确问题，返回通用描述请求
        return "请描述你看到的内容"
    }
    
    /**
     * 检测是否为唇语识别意图（包括相似发音）
     */
    fun detectLipReadingIntent(text: String): Boolean {
        val lipReadingKeywords = listOf(
            // 标准说法
            "唇语", "唇语识别", "开启唇语", "开启唇语识别",
            "识别唇语", "读唇语", "读唇",
            "看嘴", "看嘴型", "看我说", "看我说什么",
            "嘴巴识别", "口型识别", "嘴型识别",
            
            // 相似发音（拼音相近）
            "纯语", "纯语识别", "春雨识别", "唇雨识别",
            "淳语", "淳语识别", "唇鱼识别", "纯鱼识别",
            "春语", "春语识别",
            
            // 简化说法
            "唇", "嘴唇", "嘴巴", "口型",
            
            // 命令式
            "识别我说的", "识别嘴巴", "看看我说啥",
            "帮我看看嘴", "读一下嘴型"
        )
        
        val hasLipReadingKeyword = lipReadingKeywords.any { 
            text.contains(it, ignoreCase = true) 
        }
        
        if (hasLipReadingKeyword) {
            Log.d(TAG, "检测到唇语识别意图: $text")
        }
        
        return hasLipReadingKeyword
    }
    
    /**
     * 判断意图类型
     */
    enum class IntentType {
        LIP_READING, // 唇语识别
        VISUAL,      // 视觉任务
        CONTROL,     // 设备控制
        CHAT,        // 普通对话
        UNKNOWN      // 未知
    }
    
    fun classifyIntent(text: String): IntentType {
        return when {
            detectLipReadingIntent(text) -> IntentType.LIP_READING
            detectVisualIntent(text) -> IntentType.VISUAL
            detectControlIntent(text) -> IntentType.CONTROL
            text.isNotEmpty() -> IntentType.CHAT
            else -> IntentType.UNKNOWN
        }
    }
}
