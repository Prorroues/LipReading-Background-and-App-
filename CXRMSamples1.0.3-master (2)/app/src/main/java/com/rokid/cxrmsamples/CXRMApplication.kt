package com.rokid.cxrmsamples

import android.app.Application
import android.content.Context
import android.util.Log
import com.rokid.cxrmsamples.managers.GlobalWifiManager
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager
import com.rokid.cxrmsamples.managers.GlobalVideoUploadManager
import com.rokid.cxrmsamples.managers.GlobalVoiceRecognitionManager
import com.rokid.cxrmsamples.managers.MediaCaptureManager
import com.rokid.cxrmsamples.services.KeepAliveService
import com.rokid.cxrmsamples.services.assistant.VoiceAssistantManager
import com.rokid.cxrmsamples.services.weather.WeatherService
import kotlinx.coroutines.launch

class CXRMApplication : Application() {
    
    private val TAG = "CXRMApplication"
    private var locationRefreshJob: kotlinx.coroutines.Job? = null
    private var weatherService: WeatherService? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application启动")
        
        // 初始化全局WiFi管理器
        GlobalWifiManager.initialize(this)
        
        // 初始化媒体捕获管理器（相册）
        MediaCaptureManager.initialize(this)

        // 启动前台保活服务，确保后台保持连接与功能可用
        KeepAliveService.start(this)
        
        // 启动设备状态监控
        GlobalWifiManager.getInstance().startMonitoring()
        
        // 初始化全局自定义View管理器（需要在其他管理器之前初始化，以便保存Context）
        GlobalCustomViewManager.getInstance().initialize(this)
        
        // 初始化全局视频上传管理器
        GlobalVideoUploadManager.getInstance(this).initialize()
        
        // 初始化全局语音助手服务
        initializeVoiceAssistant()

        // 启动时缓存一次定位信息
        preloadLocation()
        
        Log.d(TAG, "全局管理器已初始化")
    }
    
    /**
     * 初始化全局语音助手服务
     */
    private fun initializeVoiceAssistant() {
        try {
            // 从配置中读取密钥
            val nlsPrefs = getSharedPreferences("voice_service_config", Context.MODE_PRIVATE)
            val llmPrefs = getSharedPreferences("llm_config", Context.MODE_PRIVATE)
            val aiPrefs = getSharedPreferences("ai_interaction_config", Context.MODE_PRIVATE)
            
            val nlsAppKey = nlsPrefs.getString("app_key", "") ?: ""
            val nlsAccessKeyId = nlsPrefs.getString("access_key_id", "") ?: ""
            val nlsAccessKeySecret = nlsPrefs.getString("access_key_secret", "") ?: ""
            
            // 配置语音识别
            if (nlsAppKey.isNotEmpty() && nlsAccessKeyId.isNotEmpty() && nlsAccessKeySecret.isNotEmpty()) {
                GlobalVoiceRecognitionManager.getInstance().configure(
                    nlsAppKey, nlsAccessKeyId, nlsAccessKeySecret
                )
                Log.d(TAG, "全局语音识别已配置")
                // 即使未配置LLM，也初始化本地语音助手能力
                VoiceAssistantManager.getInstance(this).configureNlsOnly(
                    nlsAppKey, nlsAccessKeyId, nlsAccessKeySecret
                )
            }
            
            // 配置视频上传管理器
            val lipReadingUrl = aiPrefs.getString("lip_reading_server_url", "http://88bill99.top:25000") ?: ""
            GlobalVideoUploadManager.getInstance(this).apply {
                configure(nlsAppKey, nlsAccessKeyId, nlsAccessKeySecret)
                setUploadUrl(lipReadingUrl)
            }
            
            // 配置语音助手
            val apiKey = llmPrefs.getString("api_key", "")
            val provider = llmPrefs.getString("provider", "")
            
            if (!apiKey.isNullOrEmpty() && !provider.isNullOrEmpty() && 
                nlsAppKey.isNotEmpty() && nlsAccessKeyId.isNotEmpty()) {
                
                val llmConfig = com.rokid.cxrmsamples.models.LLMConfig(
                    provider = com.rokid.cxrmsamples.models.LLMProvider.fromName(provider),
                    apiKey = apiKey
                )
                
                VoiceAssistantManager.getInstance(this).apply {
                    configureLLM(
                    config = llmConfig,
                    nlsAppKey = nlsAppKey,
                    nlsAccessKeyId = nlsAccessKeyId,
                    nlsAccessKeySecret = nlsAccessKeySecret
                )
                    // 配置完成后自动启用
                    enable()
                }
                
                Log.d(TAG, "全局语音助手已配置并启用")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化语音助手失败", e)
        }
    }

    private fun preloadLocation() {
        try {
            weatherService = WeatherService(this).also { it.initialize() }
            locationRefreshJob?.cancel()
            locationRefreshJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                while (true) {
                    try {
                        weatherService?.preloadLocation()
                    } catch (e: Exception) {
                        Log.e(TAG, "定位缓存刷新失败", e)
                    }
                    kotlinx.coroutines.delay(5 * 60 * 1000L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动定位缓存异常", e)
        }
    }
}
