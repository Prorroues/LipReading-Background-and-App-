package com.rokid.cxrmsamples.models

/**
 * 大模型厂商枚举
 */
enum class LLMProvider(
    val displayName: String,
    val baseUrl: String,
    val textModel: String,
    val visionModel: String,
    val supportsFunctionCalling: Boolean,
    val supportsVision: Boolean
) {
    QWEN(
        displayName = "阿里云千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        textModel = "qwen-max",
        visionModel = "qwen-vl-max",
        supportsFunctionCalling = true,
        supportsVision = true
    ),
    
    OPENAI(
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        textModel = "gpt-4",
        visionModel = "gpt-4-vision-preview",
        supportsFunctionCalling = true,
        supportsVision = true
    ),
    
    DEEPSEEK(
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        textModel = "deepseek-chat",
        visionModel = "deepseek-chat",  // DeepSeek使用同一个模型
        supportsFunctionCalling = true,
        supportsVision = false  // 目前不支持视觉
    ),
    
    ZHIPU(
        displayName = "智谱AI (GLM)",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        textModel = "glm-4",
        visionModel = "glm-4v",
        supportsFunctionCalling = true,
        supportsVision = true
    ),
    
    MOONSHOT(
        displayName = "月之暗面 (Kimi)",
        baseUrl = "https://api.moonshot.cn/v1",
        textModel = "moonshot-v1-8k",
        visionModel = "moonshot-v1-8k",  // Kimi目前不支持视觉
        supportsFunctionCalling = true,
        supportsVision = false
    ),
    
    BAIDU(
        displayName = "百度文心一言",
        baseUrl = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1",
        textModel = "ernie-4.0",
        visionModel = "ernie-bot-4",
        supportsFunctionCalling = true,
        supportsVision = true
    ),
    
    CUSTOM(
        displayName = "自定义 (OpenAI兼容)",
        baseUrl = "",  // 用户自定义
        textModel = "",  // 用户自定义
        visionModel = "",  // 用户自定义
        supportsFunctionCalling = true,
        supportsVision = false
    );
    
    companion object {
        fun fromName(name: String): LLMProvider {
            return values().find { it.name == name } ?: QWEN
        }
    }
}

/**
 * LLM配置数据类
 */
data class LLMConfig(
    val provider: LLMProvider,
    val apiKey: String,
    val baseUrl: String = provider.baseUrl,
    val textModel: String = provider.textModel,
    val visionModel: String = provider.visionModel
) {
    fun isValid(): Boolean {
        return apiKey.isNotEmpty() && baseUrl.isNotEmpty() && textModel.isNotEmpty()
    }
}
