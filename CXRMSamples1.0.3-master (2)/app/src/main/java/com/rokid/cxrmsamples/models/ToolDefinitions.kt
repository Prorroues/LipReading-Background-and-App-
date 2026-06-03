package com.rokid.cxrmsamples.models

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 工具定义（Function Calling）
 */
data class Tool(
    val type: String = "function",
    val function: FunctionDef
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "type" to type,
            "function" to mapOf(
                "name" to function.name,
                "description" to function.description,
                "parameters" to function.parameters.toMap()
            )
        )
    }
}

data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: Parameters
)

data class Parameters(
    val type: String = "object",
    val properties: Map<String, Property>,
    val required: List<String> = emptyList()
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "type" to type,
            "properties" to properties.mapValues { it.value.toMap() },
            "required" to required
        )
    }
}

data class Property(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val minimum: Int? = null,
    val maximum: Int? = null
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "type" to type,
            "description" to description
        )
        enum?.let { map["enum"] = it }
        minimum?.let { map["minimum"] = it }
        maximum?.let { map["maximum"] = it }
        return map
    }
}

/**
 * 预定义的工具集
 */
object ToolDefinitions {
    
    /**
     * 音量控制工具
     */
    val VOLUME_CONTROL = Tool(
        type = "function",
        function = FunctionDef(
            name = "adjust_volume",
            description = "调整设备音量大小。可以调高、调低或设置到指定音量值。",
            parameters = Parameters(
                type = "object",
                properties = mapOf(
                    "action" to Property(
                        type = "string",
                        description = "操作类型：up表示调高，down表示调低，set表示设置到指定值",
                        enum = listOf("up", "down", "set")
                    ),
                    "level" to Property(
                        type = "integer",
                        description = "目标音量值，范围0-100，仅在action为set时需要",
                        minimum = 0,
                        maximum = 100
                    )
                ),
                required = listOf("action")
            )
        )
    )
    
    /**
     * 亮度控制工具
     */
    val BRIGHTNESS_CONTROL = Tool(
        type = "function",
        function = FunctionDef(
            name = "adjust_brightness",
            description = "调整屏幕亮度。可以调高、调低或设置到指定亮度值。",
            parameters = Parameters(
                type = "object",
                properties = mapOf(
                    "action" to Property(
                        type = "string",
                        description = "操作类型：up表示调高，down表示调低，set表示设置到指定值",
                        enum = listOf("up", "down", "set")
                    ),
                    "level" to Property(
                        type = "integer",
                        description = "目标亮度值，范围0-100，仅在action为set时需要",
                        minimum = 0,
                        maximum = 100
                    )
                ),
                required = listOf("action")
            )
        )
    )
    
    /**
     * 拍照并分析工具
     */
    val TAKE_PHOTO = Tool(
        type = "function",
        function = FunctionDef(
            name = "take_photo_and_analyze",
            description = "使用相机拍摄照片并进行视觉分析。适用于用户询问前面有什么、这是什么等需要视觉信息的场景。",
            parameters = Parameters(
                type = "object",
                properties = mapOf(
                    "question" to Property(
                        type = "string",
                        description = "用户想要了解的问题，例如：这是什么？前面有什么东西？"
                    )
                ),
                required = listOf("question")
            )
        )
    )
    
    /**
     * 录像控制工具
     */
    val VIDEO_CONTROL = Tool(
        type = "function",
        function = FunctionDef(
            name = "control_video_recording",
            description = "控制视频录制。可以开始录制或停止录制。适用于用户说录像、录视频、开始录制、停止录制等场景。",
            parameters = Parameters(
                type = "object",
                properties = mapOf(
                    "action" to Property(
                        type = "string",
                        description = "操作类型：start表示开始录制，stop表示停止录制",
                        enum = listOf("start", "stop")
                    ),
                    "duration" to Property(
                        type = "integer",
                        description = "录制时长（秒），仅在action为start时需要，默认10秒",
                        minimum = 1,
                        maximum = 300
                    )
                ),
                required = listOf("action")
            )
        )
    )
    
    /**
     * 唇语识别工具
     */
    val LIP_READING = Tool(
        type = "function",
        function = FunctionDef(
            name = "perform_lip_reading",
            description = "执行唇语识别。录制视频并上传到服务器进行唇语识别，返回识别结果。适用于用户说开启唇语识别、唇语、识别嘴唇等场景。",
            parameters = Parameters(
                type = "object",
                properties = mapOf(
                    "duration" to Property(
                        type = "integer",
                        description = "录像时长（秒），默认6秒",
                        minimum = 3,
                        maximum = 30
                    )
                ),
                required = emptyList()
            )
        )
    )
    
    /**
     * 天气查询工具
     */
    val WEATHER_QUERY = Tool(
        type = "function",
        function = FunctionDef(
            name = "query_weather",
            description = "查询天气信息。可以查询当前城市或指定城市的天气情况，包括温度、天气状况、风向、风力、湿度等信息。",
            parameters = Parameters(
                type = "object",
                properties = mapOf(
                    "city" to Property(
                        type = "string",
                        description = "城市名称，例如：北京、上海、深圳。如果不提供，则查询当前位置的天气（需要定位权限）"
                    )
                ),
                required = emptyList()
            )
        )
    )
    
    /**
     * 获取所有工具定义
     */
    fun getAllTools(): List<Tool> {
        return listOf(
            VOLUME_CONTROL,
            BRIGHTNESS_CONTROL,
            TAKE_PHOTO,
            VIDEO_CONTROL,
            LIP_READING,
            WEATHER_QUERY
        )
    }
    
    /**
     * 转换为API格式
     */
    fun toApiFormat(): List<Map<String, Any>> {
        return getAllTools().map { it.toMap() }
    }
}
