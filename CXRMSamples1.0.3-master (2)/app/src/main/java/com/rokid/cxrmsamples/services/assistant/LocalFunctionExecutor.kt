package com.rokid.cxrmsamples.services.assistant

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.managers.MediaCaptureManager
import com.rokid.cxrmsamples.services.weather.WeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地功能执行器
 * 解析Function Calling指令并调用Android系统API
 */
class LocalFunctionExecutor(private val context: Context) {
    private val TAG = "LocalFunctionExecutor"
    private val gson = Gson()
    private val weatherService = WeatherService(context)
    private val mediaCaptureManager = MediaCaptureManager.getInstance(context)
    private var videoStartAt: Long? = null
    
    init {
        weatherService.initialize()
    }
    
    /**
     * 执行工具调用
     * @param functionName 函数名称
     * @param argumentsJson JSON格式的参数
     * @return 执行结果描述
     */
    suspend fun execute(functionName: String, argumentsJson: String): String = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "执行工具: $functionName, 参数: $argumentsJson")
            
            val arguments = gson.fromJson(argumentsJson, JsonObject::class.java)
            
            val result = when (functionName) {
                "adjust_volume" -> executeVolumeControl(arguments)
                "adjust_brightness" -> executeBrightnessControl(arguments)
                "take_photo_and_analyze" -> executeTakePhoto(arguments)
                "control_video_recording" -> executeVideoControl(arguments)
                "perform_lip_reading" -> executeLipReading(arguments)
                "query_weather" -> executeWeatherQuery(arguments)
                else -> {
                    Log.w(TAG, "未知的工具名称: $functionName")
                    "错误：不支持的功能"
                }
            }
            
            Log.d(TAG, "执行结果: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "执行工具失败: $functionName", e)
            "执行失败: ${e.message}"
        }
    }
    
    /**
     * 执行音量控制
     */
    private fun executeVolumeControl(arguments: JsonObject): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = AudioManager.STREAM_MUSIC
        
        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        val currentVolume = audioManager.getStreamVolume(streamType)
        
        val action = arguments.get("action")?.asString ?: return "缺少action参数"
        
        return when (action) {
            "up" -> {
                val newVolume = (currentVolume + maxVolume / 10).coerceAtMost(maxVolume)
                audioManager.setStreamVolume(streamType, newVolume, 0)
                val percentage = (newVolume * 100 / maxVolume)
                Log.d(TAG, "音量调高: $currentVolume -> $newVolume ($percentage%)")
                "已将音量调高至${percentage}%"
            }
            "down" -> {
                val newVolume = (currentVolume - maxVolume / 10).coerceAtLeast(0)
                audioManager.setStreamVolume(streamType, newVolume, 0)
                val percentage = (newVolume * 100 / maxVolume)
                Log.d(TAG, "音量调低: $currentVolume -> $newVolume ($percentage%)")
                "已将音量调低至${percentage}%"
            }
            "set" -> {
                val level = arguments.get("level")?.asInt ?: return "缺少level参数"
                if (level !in 0..100) return "音量值必须在0-100之间"
                
                val targetVolume = (level * maxVolume / 100)
                audioManager.setStreamVolume(streamType, targetVolume, 0)
                Log.d(TAG, "设置音量: $level% (实际值: $targetVolume)")
                "已将音量设置为${level}%"
            }
            else -> "不支持的操作: $action"
        }
    }
    
    /**
     * 执行亮度控制
     */
    private fun executeBrightnessControl(arguments: JsonObject): String {
        try {
            val action = arguments.get("action")?.asString ?: return "缺少action参数"
            
            // 检查是否有修改系统设置权限
            if (!Settings.System.canWrite(context)) {
                Log.w(TAG, "没有修改系统设置权限")
                return "需要授予修改系统设置的权限"
            }
            
            val currentBrightness = try {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                )
            } catch (e: Exception) {
                128  // 默认值
            }
            
            val maxBrightness = 255
            
            return when (action) {
                "up" -> {
                    val newBrightness = (currentBrightness + 25).coerceAtMost(maxBrightness)
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        newBrightness
                    )
                    val percentage = (newBrightness * 100 / maxBrightness)
                    Log.d(TAG, "亮度调高: $currentBrightness -> $newBrightness ($percentage%)")
                    "已将亮度调高至${percentage}%"
                }
                "down" -> {
                    val newBrightness = (currentBrightness - 25).coerceAtLeast(0)
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        newBrightness
                    )
                    val percentage = (newBrightness * 100 / maxBrightness)
                    Log.d(TAG, "亮度调低: $currentBrightness -> $newBrightness ($percentage%)")
                    "已将亮度调低至${percentage}%"
                }
                "set" -> {
                    val level = arguments.get("level")?.asInt ?: return "缺少level参数"
                    if (level !in 0..100) return "亮度值必须在0-100之间"
                    
                    val targetBrightness = (level * maxBrightness / 100)
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        targetBrightness
                    )
                    Log.d(TAG, "设置亮度: $level% (实际值: $targetBrightness)")
                    "已将亮度设置为${level}%"
                }
                else -> "不支持的操作: $action"
            }
        } catch (e: Exception) {
            Log.e(TAG, "调整亮度失败", e)
            return "调整亮度失败: ${e.message}"
        }
    }
    
    /**
     * 执行拍照（占位符，实际由VoiceAssistantManager调用相机）
     */
    private fun executeTakePhoto(arguments: JsonObject): String {
        val question = arguments.get("question")?.asString ?: "这是什么？"
        Log.d(TAG, "拍照请求，问题: $question")
        // 这个函数只是返回一个标记，实际拍照由VoiceAssistantManager处理
        return "CAMERA_TRIGGER:$question"
    }
    
    /**
     * 执行唇语识别（占位符，实际由VoiceAssistantManager调用）
     */
    private fun executeLipReading(arguments: JsonObject): String {
        val duration = arguments.get("duration")?.asInt ?: 6
        Log.d(TAG, "唇语识别请求，时长: ${duration}秒")
        // 返回标记，实际处理由VoiceAssistantManager完成
        return "LIP_READING_TRIGGER:$duration"
    }
    
    /**
     * 执行录像控制
     */
    private fun executeVideoControl(arguments: JsonObject): String {
        try {
            // 检查蓝牙连接
            if (!CxrApi.getInstance().isBluetoothConnected) {
                Log.w(TAG, "蓝牙未连接，无法录像")
                return "眼镜未连接，无法录像"
            }
            
            val action = arguments.get("action")?.asString ?: return "缺少action参数"
            
            return when (action) {
                "start" -> {
                    val duration = arguments.get("duration")?.asInt ?: 10
                    if (duration !in 1..300) {
                        return "录制时长必须在1-300秒之间"
                    }
                    
                    // 设置录像参数
                    // 参数：时长(秒), 帧率, 宽度, 高度, 时长单位(1=秒,0=分钟)
                    CxrApi.getInstance().setVideoParams(
                        duration,  // 时长
                        30,        // 帧率30fps
                        1920,      // 宽度
                        1080,      // 高度
                        1          // 单位：秒
                    )
                    Log.d(TAG, "设置录像参数: ${duration}秒, 1920x1080, 30fps")
                    
                    // 开始录像
                    CxrApi.getInstance().controlScene(
                        ValueUtil.CxrSceneType.VIDEO_RECORD,
                        true,
                        null
                    )
                    Log.d(TAG, "开始录像，时长: ${duration}秒")
                    videoStartAt = System.currentTimeMillis()
                    mediaCaptureManager.addVideoRecord("工具调用：开始录像")
                    
                    "已开始录像，时长${duration}秒"
                }
                "stop" -> {
                    // 停止录像
                    CxrApi.getInstance().controlScene(
                        ValueUtil.CxrSceneType.VIDEO_RECORD,
                        false,
                        null
                    )
                    Log.d(TAG, "停止录像")
                    val startAt = videoStartAt
                    val note = if (startAt != null) {
                        val seconds = ((System.currentTimeMillis() - startAt) / 1000).coerceAtLeast(1)
                        "工具调用：录像完成，时长${seconds}秒"
                    } else {
                        "工具调用：录像完成"
                    }
                    mediaCaptureManager.addVideoRecord(note)
                    videoStartAt = null
                    
                    "已停止录像"
                }
                else -> "不支持的操作: $action"
            }
        } catch (e: Exception) {
            Log.e(TAG, "录像控制失败", e)
            return "录像控制失败: ${e.message}"
        }
    }
    
    /**
     * 执行天气查询
     */
    private suspend fun executeWeatherQuery(arguments: JsonObject): String = withContext(Dispatchers.IO) {
        try {
            val city = arguments.get("city")?.asString
            
            Log.d(TAG, "查询天气，城市: ${city ?: "当前位置"}")
            
            val result = if (city.isNullOrBlank()) {
                val locationWeather = weatherService.queryLocationAndWeather()
                "当前在${locationWeather.address}，天气${locationWeather.weatherInfo.weather}，气温${locationWeather.weatherInfo.temperature}度，风力${locationWeather.weatherInfo.windPower}级。"
            } else {
                val weatherInfo = weatherService.queryWeather(city)
                weatherInfo.toFormattedString()
            }
            
            Log.d(TAG, "天气查询结果: $result")
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "查询天气失败", e)
            return@withContext "查询天气失败: ${e.message}"
        }
    }
}
