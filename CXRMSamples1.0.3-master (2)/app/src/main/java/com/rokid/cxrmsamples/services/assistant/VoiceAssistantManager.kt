package com.rokid.cxrmsamples.services.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.alibaba.nls.client.protocol.NlsClient
import com.alibaba.nls.client.protocol.OutputFormatEnum
import com.alibaba.nls.client.protocol.SampleRateEnum
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager
import com.rokid.cxrmsamples.managers.GlobalVideoUploadManager
import com.rokid.cxrmsamples.managers.GlobalVoiceRecognitionManager
import com.rokid.cxrmsamples.managers.GlobalWifiManager
import com.rokid.cxrmsamples.managers.MediaCaptureManager
import com.rokid.cxrmsamples.models.FunctionCall
import com.rokid.cxrmsamples.models.LLMConfig
import com.rokid.cxrmsamples.models.LLMProvider
import com.rokid.cxrmsamples.models.Message
import com.rokid.cxrmsamples.models.MessageRole
import com.rokid.cxrmsamples.models.MessageMediaType
import com.rokid.cxrmsamples.models.ToolCall
import com.rokid.cxrmsamples.services.camera.CameraManager
import com.rokid.cxrmsamples.services.qwen.UniversalLLMService
import com.rokid.cxrmsamples.services.router.LocalSemanticRouter
import com.rokid.cxrmsamples.services.tts.StreamingTTSManager
import com.rokid.cxrmsamples.services.weather.WeatherService
import com.rokid.cxrmsamples.utils.AliyunTokenHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * 语音助手管理器（核心编排层）
 * 协调ASR→LLM→TTS的完整流程
 */
class VoiceAssistantManager private constructor(
    private val context: Context
) {
    private val TAG = "VoiceAssistantManager"
    
    companion object {
        @Volatile
        private var instance: VoiceAssistantManager? = null
        
        fun getInstance(context: Context): VoiceAssistantManager {
            return instance ?: synchronized(this) {
                instance ?: VoiceAssistantManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // 状态枚举
    enum class AssistantState {
        IDLE,        // 待机
        LISTENING,   // 语音识别中
        THINKING,    // 大模型推理中
        SPEAKING,    // 语音播放中
        CAMERA       // 相机操作中
    }
    
    // 状态流
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    
    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()
    
    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()
    
    // 对话历史
    private val _conversationHistory = MutableStateFlow<List<Message>>(emptyList())
    val conversationHistory: StateFlow<List<Message>> = _conversationHistory.asStateFlow()
    
    // 服务实例
    private var llmService: UniversalLLMService? = null
    private var llmConfig: LLMConfig? = null
    private val cameraManager = CameraManager(context)
    private val mediaCaptureManager = MediaCaptureManager.getInstance(context)
    private var localVideoStartAt: Long? = null
    private val videoUploadManager = GlobalVideoUploadManager.getInstance(context)
    private val localFunctionExecutor = LocalFunctionExecutor(context)
    private val intentRouter = IntentRouter()
    private val localSemanticRouter = LocalSemanticRouter(context)
    private val weatherService = WeatherService(context)
    private var localRouterInitialized = false
    
    // TTS相关
    private var nlsClient: NlsClient? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var mediaPlayer: MediaPlayer? = null
    private val ttsPath = "/sdcard/Download/Rokid/assistant"
    private var streamingTTSManager: StreamingTTSManager? = null
    private var lastTtsDurationMs: Int = 0
    
    // 配置信息
    private var qwenApiKey: String = ""
    private var nlsAppKey: String = ""
    private var nlsAccessKeyId: String = ""
    private var nlsAccessKeySecret: String = ""
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 唇语识别结果监听Job（避免重复监听）
    private var lipReadingResultJob: Job? = null
    
    // UI状态机（独立于AssistantState，专门用于管理UI生命周期）
    private enum class UiState {
        IDLE,           // 空闲状态
        LISTENING,      // 正在识别
        PROCESSING,     // 正在处理（思考/拍照/分析等）
        DISPLAYING      // 正在显示结果（TTS播放中）
    }
    
    private var uiState = UiState.IDLE
    private var closeViewJob: Job? = null
    private var listeningTimeoutJob: Job? = null
    private var progressTimeoutJob: Job? = null
    private val progressTimeoutMs = 20000L
    private var progressTrackingEnabled = false
    
    // ASR回调注册标志（避免重复注册）
    private var asrCallbackRegistered = false
    
    // 唇语识别录像时长（从配置读取，默认6秒）
    private var lipReadingDuration = 6
        get() {
            // 从SharedPreferences读取最新配置
            try {
                val prefs = context.getSharedPreferences("ai_interaction_config", Context.MODE_PRIVATE)
                return prefs.getInt("lip_reading_duration", 6)
            } catch (e: Exception) {
                return 6
            }
        }
    
    /**
     * 配置语音助手（传统方式，兼容旧代码）
     */
    fun configure(
        qwenApiKey: String,
        nlsAppKey: String,
        nlsAccessKeyId: String,
        nlsAccessKeySecret: String
    ) {
        this.qwenApiKey = qwenApiKey
        this.nlsAppKey = nlsAppKey
        this.nlsAccessKeyId = nlsAccessKeyId
        this.nlsAccessKeySecret = nlsAccessKeySecret
        
        // 配置全局视频上传管理器
        videoUploadManager.configure(nlsAppKey, nlsAccessKeyId, nlsAccessKeySecret)
        
        // 使用默认的千问配置
        this.llmConfig = LLMConfig(
            provider = LLMProvider.QWEN,
            apiKey = qwenApiKey
        )
        this.llmService = UniversalLLMService(llmConfig!!)
        
        // 配置完成后，立即注册ASR回调（即使未启用，也要监听以便自动启用）
        if (!asrCallbackRegistered) {
            registerASRCallback()
        }
        
        // 初始化本地语义路由器
        scope.launch(Dispatchers.IO) {
            localSemanticRouter.initialize()
        }
        
        // 初始化流式TTS管理器
        streamingTTSManager = StreamingTTSManager(nlsAppKey, nlsAccessKeyId, nlsAccessKeySecret)
        streamingTTSManager?.setAudioPlayCallback { audioPath ->
            playAudio(audioPath)
        }
        streamingTTSManager?.start()
        
        Log.d(TAG, "语音助手配置完成（千问）")
    }

    init {
        weatherService.initialize()
    }

    /**
     * 仅配置语音能力（无LLM），用于本地意图路由与基础提示
     */
    fun configureNlsOnly(
        nlsAppKey: String,
        nlsAccessKeyId: String,
        nlsAccessKeySecret: String
    ) {
        this.nlsAppKey = nlsAppKey
        this.nlsAccessKeyId = nlsAccessKeyId
        this.nlsAccessKeySecret = nlsAccessKeySecret

        // 配置全局视频上传管理器（唇语识别/拍照上传依赖）
        videoUploadManager.configure(nlsAppKey, nlsAccessKeyId, nlsAccessKeySecret)

        // 配置完成后，立即注册ASR回调（即使未启用，也要监听以便自动启用）
        if (!asrCallbackRegistered) {
            registerASRCallback()
        }

        // 初始化本地语义路由器
        scope.launch(Dispatchers.IO) {
            localSemanticRouter.initialize()
            localRouterInitialized = true
        }

        // 初始化流式TTS管理器
        streamingTTSManager = StreamingTTSManager(nlsAppKey, nlsAccessKeyId, nlsAccessKeySecret)
        streamingTTSManager?.setAudioPlayCallback { audioPath ->
            playAudio(audioPath)
        }
        streamingTTSManager?.start()

        Log.d(TAG, "语音助手配置完成（本地模式）")
    }
    
    /**
     * 配置语音助手（使用LLMConfig）
     */
    fun configureLLM(
        config: LLMConfig,
        nlsAppKey: String,
        nlsAccessKeyId: String,
        nlsAccessKeySecret: String
    ) {
        this.llmConfig = config
        this.llmService = UniversalLLMService(config)
        this.nlsAppKey = nlsAppKey
        this.nlsAccessKeyId = nlsAccessKeyId
        this.nlsAccessKeySecret = nlsAccessKeySecret
        
        // 配置全局视频上传管理器
        videoUploadManager.configure(nlsAppKey, nlsAccessKeyId, nlsAccessKeySecret)
        
        // 配置完成后，立即注册ASR回调（即使未启用，也要监听以便自动启用）
        if (!asrCallbackRegistered) {
            registerASRCallback()
        }
        
        // 初始化本地语义路由器
        scope.launch(Dispatchers.IO) {
            localSemanticRouter.initialize()
        }
        
        // 初始化流式TTS管理器
        streamingTTSManager = StreamingTTSManager(nlsAppKey, nlsAccessKeyId, nlsAccessKeySecret)
        streamingTTSManager?.setAudioPlayCallback { audioPath ->
            playAudio(audioPath)
        }
        streamingTTSManager?.start()
        
        Log.d(TAG, "语音助手配置完成（${config.provider.displayName}）")
    }
    
    /**
     * 启用语音助手
     */
    fun enable() {
        if (_enabled.value) {
            Log.w(TAG, "语音助手已启用")
            return
        }
        
        if (nlsAppKey.isEmpty() || nlsAccessKeyId.isEmpty() || nlsAccessKeySecret.isEmpty()) {
            Log.e(TAG, "语音助手未配置语音服务")
            _statusText.value = "请先配置语音服务密钥"
            return
        }
        
        _enabled.value = true
        _state.value = AssistantState.IDLE
        _statusText.value = if (llmConfig != null && llmConfig!!.isValid()) {
            "语音助手已启用（${llmConfig?.provider?.displayName}）"
        } else {
            "语音助手已启用（本地模式）"
        }
        
        // 清空对话历史
        _conversationHistory.value = emptyList()
        
        // 注册ASR结果回调
        registerASRCallback()
        
        Log.d(TAG, "语音助手已启用：${llmConfig?.provider?.displayName ?: "本地模式"}")
    }
    
    /**
     * 禁用语音助手
     */
    fun disable() {
        _enabled.value = false
        _state.value = AssistantState.IDLE
        _statusText.value = "语音助手已禁用"
        
        // 停止所有正在进行的操作
        stopCurrentOperation()
        
        // 注意：不重置asrCallbackRegistered，因为回调需要持续监听以便自动启用
        
        Log.d(TAG, "语音助手已禁用")
    }
    
    /**
     * 注册ASR结果回调
     */
    private fun registerASRCallback() {
        // 避免重复注册
        if (asrCallbackRegistered) {
            Log.d(TAG, "ASR回调已注册，跳过")
            return
        }
        asrCallbackRegistered = true
        Log.d(TAG, "开始注册ASR回调")
        // ⭐ 监听最终识别结果（句子完成后才触发）
        scope.launch {
            GlobalVoiceRecognitionManager.getInstance().finalRecognitionResult.collect { finalText ->
                Log.d(TAG, "🔔 收到识别结果: '$finalText' (enabled=${_enabled.value}, state=${_state.value})")
                if (!finalText.isNullOrEmpty()) {
                    // 如果助手已配置但未启用，自动启用
                    if (!_enabled.value) {
                        if (nlsAppKey.isNotEmpty() && nlsAccessKeyId.isNotEmpty() && nlsAccessKeySecret.isNotEmpty()) {
                            Log.d(TAG, "✅ 检测到识别结果但助手未启用，自动启用")
                            _enabled.value = true
                            _state.value = AssistantState.LISTENING
                            _statusText.value = "语音助手已启用"
                        } else {
                            Log.w(TAG, "❌ 收到识别结果但助手未配置语音服务，忽略: $finalText")
                            return@collect
                        }
                    }
                    
                    // 如果状态是LISTENING或IDLE，都可以处理（IDLE可能是超时停止的情况）
                    // 如果状态是THINKING或SPEAKING，说明正在处理中，忽略新的识别结果
                    if (_state.value == AssistantState.LISTENING || _state.value == AssistantState.IDLE) {
                    // 句子完成，开始处理
                        Log.d(TAG, "⭐ 收到完整句子，开始处理: $finalText (状态: ${_state.value})")
                    processUserInput(finalText)
                    } else {
                        Log.d(TAG, "⚠️ 收到识别结果但状态不对，忽略: $finalText (状态: ${_state.value})")
                    }
                } else {
                    Log.d(TAG, "收到空识别结果，忽略")
                }
            }
        }
        
        // 监听识别状态
        scope.launch {
            GlobalVoiceRecognitionManager.getInstance().isRecognizing.collect { isRecognizing ->
                if (_enabled.value) {
                    if (isRecognizing) {
                        // ⭐ 识别开始：立即取消旧的关闭任务和超时任务
                        cancelCloseView()
                        cancelListeningTimeout()
                        uiState = UiState.LISTENING
                        _state.value = AssistantState.LISTENING
                        _statusText.value = "正在聆听..."
                        Log.d(TAG, "识别开始，取消自动关闭，更新UI状态为LISTENING")
                    } else {
                        // 无输入导致识别停止时，启动超时任务，如果超时后仍无输入则关闭界面
                        if (_state.value == AssistantState.LISTENING) {
                            Log.d(TAG, "识别停止，启动超时任务（2秒后关闭）")
                            startListeningTimeout()
                        }
                    }
                    // 有输入时状态会在processUserInput中更新
                } else {
                    // 即使未启用，也监听识别状态，以便在识别开始时自动启用
                    if (isRecognizing && nlsAppKey.isNotEmpty() && nlsAccessKeyId.isNotEmpty() && nlsAccessKeySecret.isNotEmpty()) {
                        Log.d(TAG, "检测到识别开始但助手未启用，自动启用")
                        // ⭐ 识别开始：立即取消旧的关闭任务和超时任务
                        cancelCloseView()
                        cancelListeningTimeout()
                        uiState = UiState.LISTENING
                        _enabled.value = true
                        _state.value = AssistantState.LISTENING
                        _statusText.value = "正在聆听..."
                        // 确保已注册回调（如果之前未启用，可能未注册）
                        if (!asrCallbackRegistered) {
                            registerASRCallback()
                        }
                    }
                }
            }
        }
        
        // ⭐ 监听实时识别文本，流式更新到眼镜屏幕
        scope.launch {
            GlobalVoiceRecognitionManager.getInstance().recognitionText.collect { text ->
                if (_enabled.value && _state.value == AssistantState.LISTENING) {
                    // 流式显示识别结果到眼镜屏幕
                    if (text.isNotEmpty()) {
                        GlobalCustomViewManager.getInstance().updateDialogText(text)
                        Log.d(TAG, "📺 流式更新眼镜屏幕: $text")
                    } else {
                        GlobalCustomViewManager.getInstance().updateDialogText("正在聆听...")
                    }
                }
            }
        }
    }
    
    /**
     * 处理用户输入
     */
    private fun processUserInput(userText: String) {
        if (!_enabled.value) return
        
        scope.launch {
            try {
                if (!localRouterInitialized) {
                    localSemanticRouter.initialize()
                    localRouterInitialized = true
                }
                // ⭐ 取消自动关闭界面定时器（确保处理过程中界面保持打开）
                GlobalVoiceRecognitionManager.getInstance().cancelAutoCloseView()
                
                // ⭐ 取消旧的关闭任务和聆听超时任务，并更新UI状态
                cancelCloseView()
                cancelListeningTimeout()
                uiState = UiState.PROCESSING
                
                // ⭐ 确保对话界面打开（如果已关闭，重新打开）
                if (!GlobalCustomViewManager.getInstance().isCustomViewOpen.value) {
                    Log.d(TAG, "对话界面已关闭，重新打开")
                    GlobalCustomViewManager.getInstance().showDialogView("正在处理...")
                }
                
                // ⭐ 清空识别结果（表示已接收）
                GlobalVoiceRecognitionManager.getInstance().clearRecognitionResult()
                
                _state.value = AssistantState.THINKING
                _statusText.value = "正在思考..."
                GlobalCustomViewManager.getInstance().updateDialogText("正在思考...")
                Log.d(TAG, "开始处理用户输入，UI状态更新为PROCESSING")
                
                Log.d(TAG, "⭐ 开始处理用户输入: $userText")
                
                // 添加用户消息到历史
                addMessageToHistory(Message(MessageRole.USER, userText))
                
                // ⭐ 优先使用本地语义路由判断意图
                val routeResult = localSemanticRouter.routeIntent(userText)
                val isLocalMatch = routeResult.score >= routeResult.threshold
                Log.d(
                    TAG,
                    "本地语义路由结果: intent=${routeResult.intent}, score=${routeResult.score}, threshold=${routeResult.threshold}, localMatch=$isLocalMatch"
                )

                if (routeResult.intent == LocalSemanticRouter.IntentType.LIP_READING) {
                    stopProgressTimeoutTracking()
                } else {
                    startProgressTimeoutTracking()
                }
                
                when (routeResult.intent) {
                    LocalSemanticRouter.IntentType.DEVICE_OPERATION -> {
                        if (isLocalMatch) {
                            handleLocalDeviceOperation(userText)
                        } else {
                            handleTextTask(userText)
                        }
                    }
                    LocalSemanticRouter.IntentType.WEATHER_QUERY -> {
                        if (isLocalMatch) {
                            handleLocalWeatherTask(userText)
                        } else {
                            handleTextTask(userText)
                        }
                    }
                    LocalSemanticRouter.IntentType.LOCATION_QUERY -> {
                        if (isLocalMatch) {
                            handleLocalLocationTask()
                        } else {
                            handleTextTask(userText)
                        }
                    }
                    LocalSemanticRouter.IntentType.LIP_READING -> {
                        if (isLocalMatch) {
                            Log.d(TAG, "本地唇语识别指令，直接执行")
                            handleLipReadingTask(lipReadingDuration)
                        } else {
                            handleTextTask(userText)
                        }
                    }
                    LocalSemanticRouter.IntentType.CLOUD_CHAT,
                    LocalSemanticRouter.IntentType.UNKNOWN -> {
                        handleTextTask(userText)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "处理用户输入异常", e)
                _state.value = AssistantState.IDLE
                val errorMsg = "处理失败: ${e.message}"
                _statusText.value = errorMsg
                uiState = UiState.PROCESSING
                addMessageToHistory(Message(MessageRole.ASSISTANT, errorMsg))
                stopProgressTimeoutTracking()
                speakText("抱歉，我遇到了问题")
            }
        }
    }

    /**
     * 本地设备指令处理（拍照/录像/停止）
     */
    private suspend fun handleLocalDeviceOperation(userText: String) {
        markProgress("local_device_operation_start")
        when {
            isPhotoCommand(userText) -> {
                Log.d(TAG, "本地拍照指令，直接执行")
                handleLocalPhotoCapture()
            }
            isVideoStopCommand(userText) -> {
                Log.d(TAG, "本地停止录像指令，直接执行")
                handleLocalVideoControl(start = false)
            }
            isVideoStartCommand(userText) -> {
                Log.d(TAG, "本地开始录像指令，直接执行")
                handleLocalVideoControl(start = true)
            }
            else -> {
                val msg = "本地暂不支持该设备指令"
                Log.w(TAG, msg)
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().updateDialogText(msg)
                }
                addMessageToHistory(Message(MessageRole.ASSISTANT, msg))
                speakText(msg)
            }
        }
        markProgress("local_device_operation_end")
        stopProgressTimeoutTracking()
    }

    /**
     * 本地拍照（不走大模型分析）
     */
    private suspend fun handleLocalPhotoCapture() {
        try {
            if (!com.rokid.cxr.client.extend.CxrApi.getInstance().isBluetoothConnected) {
                Log.e(TAG, "蓝牙未连接，无法拍照")
                addMessageToHistory(Message(MessageRole.ASSISTANT, "蓝牙未连接，无法拍照"))
                speakText("眼镜未连接，无法拍照")
                return
            }

            _state.value = AssistantState.CAMERA
            _statusText.value = "正在拍照..."
            uiState = UiState.PROCESSING
            GlobalCustomViewManager.getInstance().updateDialogText("正在拍照...")
            markProgress("photo_capture_start")

            Log.d(TAG, "本地拍照开始")
            val imageBytes = cameraManager.takePictureBytes()
            Log.d(TAG, "本地拍照完成")
            val media = mediaCaptureManager.addPhotoFromBytes(
                imageBytes,
                "webp",
                "image/webp",
                "语音拍照"
            )

            _state.value = AssistantState.IDLE
            _statusText.value = "拍照完成"
            uiState = UiState.DISPLAYING
            GlobalCustomViewManager.getInstance().updateDialogText("已拍照")
            addMessageToHistory(Message(MessageRole.ASSISTANT, "已拍照"))
            media?.filePath?.let { path ->
                addMessageToHistory(
                    Message(
                        MessageRole.ASSISTANT,
                        "拍照结果",
                        mediaType = MessageMediaType.PHOTO,
                        mediaPath = path
                    )
                )
            }
            speakText("已拍照")
            markProgress("photo_capture_done")
        } catch (e: Exception) {
            Log.e(TAG, "本地拍照失败", e)
            addMessageToHistory(Message(MessageRole.ASSISTANT, "拍照失败: ${e.message}"))
            speakText("拍照失败：${e.message}")
        }
    }

    /**
     * 本地录像控制
     */
    private suspend fun handleLocalVideoControl(start: Boolean) {
        try {
            if (!com.rokid.cxr.client.extend.CxrApi.getInstance().isBluetoothConnected) {
                Log.w(TAG, "蓝牙未连接，无法录像")
                addMessageToHistory(Message(MessageRole.ASSISTANT, "蓝牙未连接，无法录像"))
                speakText("眼镜未连接，无法录像")
                return
            }

            val actionText = if (start) "开始录像" else "停止录像"
            _statusText.value = actionText
            uiState = UiState.PROCESSING
            GlobalCustomViewManager.getInstance().updateDialogText(actionText)
            markProgress("local_video_control_$start")

            com.rokid.cxr.client.extend.CxrApi.getInstance().controlScene(
                com.rokid.cxr.client.utils.ValueUtil.CxrSceneType.VIDEO_RECORD,
                start,
                null
            )
            if (start) {
                localVideoStartAt = System.currentTimeMillis()
                mediaCaptureManager.addVideoRecord("语音录像开始")
            } else {
                val startAt = localVideoStartAt
                val note = if (startAt != null) {
                    val seconds = ((System.currentTimeMillis() - startAt) / 1000).coerceAtLeast(1)
                    "语音录像完成，时长${seconds}秒"
                } else {
                    "语音录像完成"
                }
                mediaCaptureManager.addVideoRecord(note)
                localVideoStartAt = null
            }
            addMessageToHistory(Message(MessageRole.ASSISTANT, if (start) "已开始录像" else "已停止录像"))
            speakText(if (start) "已开始录像" else "已停止录像")
            markProgress("local_video_control_done_$start")
            stopProgressTimeoutTracking()
        } catch (e: Exception) {
            Log.e(TAG, "本地录像控制失败", e)
            addMessageToHistory(Message(MessageRole.ASSISTANT, "录像操作失败: ${e.message}"))
            speakText("录像操作失败：${e.message}")
        }
    }

    /**
     * 本地天气查询
     */
    private suspend fun handleLocalWeatherTask(userText: String) {
        try {
            markProgress("weather_task_start")
            val city = extractCityFromText(userText)
            Log.d(TAG, "本地天气查询，城市: ${city ?: "当前位置"}")
            val result = if (city.isNullOrBlank()) {
                val locationWeather = weatherService.queryLocationAndWeather()
                "当前在${locationWeather.address}，天气${locationWeather.weatherInfo.weather}，气温${locationWeather.weatherInfo.temperature}度，风力${locationWeather.weatherInfo.windPower}级。"
            } else {
                val weatherInfo = weatherService.queryWeather(city)
                weatherInfo.toFormattedString()
            }
            GlobalCustomViewManager.getInstance().updateDialogText(result)
            speakText(result)
            markProgress("weather_task_done")
            stopProgressTimeoutTracking()
        } catch (e: Exception) {
            Log.e(TAG, "本地天气查询失败", e)
            val msg = "查询天气失败：${e.message}"
            GlobalCustomViewManager.getInstance().updateDialogText(msg)
            addMessageToHistory(Message(MessageRole.ASSISTANT, msg))
            stopProgressTimeoutTracking()
            speakText(msg)
        }
    }

    /**
     * 本地定位查询
     */
    private suspend fun handleLocalLocationTask() {
        try {
            markProgress("location_task_start")
            Log.d(TAG, "本地定位查询")
            val locationInfo = weatherService.queryLocationInfo()
            val result = "当前位置：$locationInfo"
            GlobalCustomViewManager.getInstance().updateDialogText(result)
            speakText(result)
            markProgress("location_task_done")
            stopProgressTimeoutTracking()
        } catch (e: Exception) {
            Log.e(TAG, "本地定位查询失败", e)
            val msg = "定位失败：${e.message}"
            GlobalCustomViewManager.getInstance().updateDialogText(msg)
            addMessageToHistory(Message(MessageRole.ASSISTANT, msg))
            stopProgressTimeoutTracking()
            speakText(msg)
        }
    }

    private fun isPhotoCommand(text: String): Boolean {
        val keywords = listOf("拍照", "拍个照", "拍一张", "拍摄", "照片", "截图")
        return keywords.any { text.contains(it) }
    }

    private fun isVideoStartCommand(text: String): Boolean {
        val keywords = listOf("开始录像", "开始录", "开始录制", "录像", "录视频", "录影")
        return keywords.any { text.contains(it) } && !isVideoStopCommand(text)
    }

    private fun isVideoStopCommand(text: String): Boolean {
        val keywords = listOf("停止录像", "停止录", "停止录制", "结束录像", "停止")
        return keywords.any { text.contains(it) }
    }

    private fun extractCityFromText(text: String): String? {
        val cleaned = text
            .replace(Regex("[，。？！?\\s]"), "")
            .replace("天气", "")
            .replace("怎么样", "")
            .replace("如何", "")
            .replace("现在", "")
            .replace("当前", "")
            .replace("这里", "")
            .replace("本地", "")
            .replace("附近", "")
            .replace("今天", "")
            .replace("明天", "")
            .replace("后天", "")
            .replace("查询", "")
            .replace("请问", "")
            .trim()
        if (cleaned.isEmpty()) return null
        val city = cleaned.removeSuffix("市").trim()
        if (city in listOf("当前", "这里", "本地", "附近")) return null
        return if (city.length in 2..10) city else null
    }
    
    /**
     * 处理视觉任务
     */
    private suspend fun handleVisualTask(userText: String) {
        try {
            markProgress("visual_task_start")
            if (llmConfig == null || !llmConfig!!.isValid() || llmService == null) {
                val msg = "未配置大模型，无法进行视觉分析"
                Log.w(TAG, msg)
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().updateDialogText(msg)
                }
                addMessageToHistory(Message(MessageRole.ASSISTANT, msg))
                stopProgressTimeoutTracking()
                speakText(msg)
                return
            }
            // 检查是否支持视觉
            if (llmConfig?.provider?.supportsVision != true) {
                addMessageToHistory(Message(MessageRole.ASSISTANT, "当前模型不支持视觉功能"))
                stopProgressTimeoutTracking()
                speakText("当前模型不支持视觉功能，请在大模型配置中选择支持视觉的厂商")
                return
            }
            
            // 检查蓝牙连接
            if (!com.rokid.cxr.client.extend.CxrApi.getInstance().isBluetoothConnected) {
                Log.e(TAG, "蓝牙未连接，无法拍照")
                addMessageToHistory(Message(MessageRole.ASSISTANT, "蓝牙未连接，无法拍照"))
                stopProgressTimeoutTracking()
                speakText("眼镜未连接，无法拍照")
                return
            }
            
            _state.value = AssistantState.CAMERA
            _statusText.value = "正在拍照..."
            uiState = UiState.PROCESSING
            GlobalCustomViewManager.getInstance().updateDialogText("正在拍照...")
            
            Log.d(TAG, "开始拍照流程")
            
            // 使用Rokid CXR API拍照
            val imageBase64 = cameraManager.takePicture()
            Log.d(TAG, "拍照完成，Base64大小: ${imageBase64.length / 1024}KB")
            addMessageToHistory(Message(MessageRole.ASSISTANT, "已拍照"))
            val media = mediaCaptureManager.addPhotoFromBase64(imageBase64, "视觉分析拍照")
            media?.filePath?.let { path ->
                addMessageToHistory(
                    Message(
                        MessageRole.ASSISTANT,
                        "拍照结果",
                        mediaType = MessageMediaType.PHOTO,
                        mediaPath = path
                    )
                )
            }
            
            _state.value = AssistantState.THINKING
            _statusText.value = "正在分析图片..."
            uiState = UiState.PROCESSING
            GlobalCustomViewManager.getInstance().updateDialogText("正在分析图片...")
            
            // 提取视觉问题
            val question = intentRouter.extractVisualQuestion(userText)
            Log.d(TAG, "视觉问题: $question")
            
            // 调用视觉API分析
            val service = llmService ?: throw Exception("LLM服务未初始化")
            val response = service.analyzeImage(imageBase64, question)
            
            val answer = response.choices.firstOrNull()?.message?.content ?: "无法识别图片内容"
            Log.d(TAG, "视觉分析结果: $answer")
            
            // 添加到历史
            addMessageToHistory(Message(MessageRole.ASSISTANT, answer))
            
            // 语音播报
            speakText(answer)
            markProgress("visual_task_done")
            stopProgressTimeoutTracking()
            
            } catch (e: Exception) {
                Log.e(TAG, "处理视觉任务异常", e)
                val errorMsg = when {
                    e.message?.contains("蓝牙") == true -> "眼镜未连接"
                    e.message?.contains("超时") == true -> "拍照超时，请重试"
                    else -> "拍照失败，${e.message}"
                }
                uiState = UiState.PROCESSING
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().updateDialogText(errorMsg)
                }
                addMessageToHistory(Message(MessageRole.ASSISTANT, errorMsg))
                stopProgressTimeoutTracking()
                speakText(errorMsg)
            }
    }
    
    /**
     * 处理文本任务（包括Function Calling）
     */
    private suspend fun handleTextTask(userText: String) {
        try {
            markProgress("text_task_start")
            if (llmConfig == null || !llmConfig!!.isValid() || llmService == null) {
                val msg = "未配置大模型，无法处理该请求"
                Log.w(TAG, msg)
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().updateDialogText(msg)
                }
                addMessageToHistory(Message(MessageRole.ASSISTANT, msg))
                stopProgressTimeoutTracking()
                speakText(msg)
                return
            }
            // 构建消息列表（包含system prompt和历史）
            val messages = buildMessageList()
            
            // 显示正在思考
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().updateDialogText("正在思考...")
            }
            
            // 调用LLM API（流式）
            val service = llmService ?: throw Exception("LLM服务未初始化")
            
            // 使用流式SSE
            var fullResponse = StringBuilder()
            var hasToolCalls = false
            var toolCalls: List<com.rokid.cxrmsamples.services.qwen.models.ToolCallResponse>? = null
            
            try {
                service.chatStreaming(messages, enableTools = true).collect { chunk ->
                    fullResponse.append(chunk)
                    markProgress("text_stream_chunk")
                    
                    // 实时显示文本
                    withContext(Dispatchers.Main) {
                        GlobalCustomViewManager.getInstance().updateDialogText(fullResponse.toString())
                    }
                    
                    // 实时送入TTS队列
                    streamingTTSManager?.speakChunk(chunk)
                }
                
                // 流式响应完成后，检查是否有tool_calls（需要重新解析完整响应）
                // 注意：流式响应可能不包含tool_calls，需要降级到非流式请求
                // 这里先尝试非流式请求检查tool_calls
                val nonStreamResponse = service.chat(messages, enableTools = true)
                val choice = nonStreamResponse.choices.firstOrNull()
                val message = choice?.message
                
                if (message?.toolCalls != null && message.toolCalls.isNotEmpty()) {
                    Log.d(TAG, "检测到Function Calling: ${message.toolCalls.size}个工具调用")
                    hasToolCalls = true
                    toolCalls = message.toolCalls
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "流式请求失败，降级到非流式: ${e.message}")
                // 清空TTS队列（因为要切换到非流式）
                streamingTTSManager?.clearQueue()
                
                // 降级到非流式请求
                try {
                    val response = service.chat(messages, enableTools = true)
                    val choice = response.choices.firstOrNull() ?: throw Exception("无响应")
                    val message = choice.message
                    
                    if (message.toolCalls != null && message.toolCalls.isNotEmpty()) {
                        hasToolCalls = true
                        toolCalls = message.toolCalls
                    } else {
                        val answer = message.content ?: "我不知道该怎么回答"
                        fullResponse.clear()
                        fullResponse.append(answer)
                        // 非流式响应，使用传统TTS
                        speakText(answer)
                    }
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "非流式请求也失败", fallbackError)
                    throw fallbackError
                }
            }
            
            if (hasToolCalls && toolCalls != null) {
                // 显示正在处理指令
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().updateDialogText("正在处理指令~")
                }
                markProgress("text_tool_calls")
                // 清空TTS队列（因为工具调用需要等待结果）
                streamingTTSManager?.clearQueue()
                handleToolCalls(toolCalls, messages)
            } else {
                // 直接回复（流式响应已完成）
                val answer = fullResponse.toString().ifEmpty { "我不知道该怎么回答" }
                Log.d(TAG, "直接回复: $answer")
                
                addMessageToHistory(Message(MessageRole.ASSISTANT, answer))
                // TTS已经在流式响应中处理，这里不需要再次调用
            }
            stopProgressTimeoutTracking()
            
        } catch (e: Exception) {
            Log.e(TAG, "处理文本任务异常", e)
            uiState = UiState.PROCESSING
            val errorMsg = "处理失败，请重试"
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().updateDialogText(errorMsg)
            }
            addMessageToHistory(Message(MessageRole.ASSISTANT, "服务异常: ${e.message}"))
            stopProgressTimeoutTracking()
            speakText("抱歉，我无法回答")
        }
    }
    
    /**
     * 处理工具调用
     */
    private suspend fun handleToolCalls(
        toolCallsResponse: List<com.rokid.cxrmsamples.services.qwen.models.ToolCallResponse>,
        messages: MutableList<Message>
    ) {
        try {
            val toolCalls = toolCallsResponse.map { tc ->
                ToolCall(
                    id = tc.id,
                    type = tc.type,
                    function = FunctionCall(tc.function.name, tc.function.arguments)
                )
            }
            
            // 添加assistant消息（包含tool_calls）
            addMessageToHistory(Message(MessageRole.ASSISTANT, content = null, toolCalls = toolCalls))
            messages.add(Message(MessageRole.ASSISTANT, content = null, toolCalls = toolCalls))
            
            // 执行每个工具调用
            for (toolCall in toolCalls) {
                Log.d(TAG, "执行工具: ${toolCall.function.name}")
                _statusText.value = "正在执行: ${toolCall.function.name}"
                markProgress("tool_call_start_${toolCall.function.name}")
                
                // 显示正在执行工具
                withContext(Dispatchers.Main) {
                    val toolDisplayName = when (toolCall.function.name) {
                        "take_picture" -> "正在拍照"
                        "start_lip_reading" -> "正在启动唇语识别"
                        else -> "正在执行: ${toolCall.function.name}"
                    }
                    GlobalCustomViewManager.getInstance().updateDialogText(toolDisplayName)
                }
                
                val result = localFunctionExecutor.execute(
                    toolCall.function.name,
                    toolCall.function.arguments
                )
                markProgress("tool_call_result_${toolCall.function.name}")
                
                // 检查是否是相机触发标记
                if (result.startsWith("CAMERA_TRIGGER:")) {
                    val question = result.substring("CAMERA_TRIGGER:".length)
                    handleVisualTask(question)
                    return  // 视觉任务会自己处理后续
                }
                
                // 检查是否是唇语识别触发标记
                if (result.startsWith("LIP_READING_TRIGGER:")) {
                    val requestedDuration = result.substring("LIP_READING_TRIGGER:".length).toIntOrNull()
                    val duration = requestedDuration ?: lipReadingDuration  // 使用配置的时长
                    handleLipReadingTask(duration)
                    return  // 唇语识别会自己处理后续
                }
                
                // 添加工具结果消息
                val toolMessage = Message(
                    role = MessageRole.TOOL,
                    content = result,
                    name = toolCall.function.name,
                    toolCallId = toolCall.id
                )
                addMessageToHistory(toolMessage)
                messages.add(toolMessage)
            }
            
            // 再次调用大模型获取最终回复
            val service = llmService ?: throw Exception("LLM服务未初始化")
            Log.d(TAG, "调用LLM获取最终自然语言回复...")
            
            // 显示正在生成回复
            uiState = UiState.PROCESSING
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().updateDialogText("正在生成回复...")
            }
            
            val finalResponse = service.chat(messages, enableTools = false)
            
            val finalAnswer = finalResponse.choices.firstOrNull()?.message?.content 
                ?: "操作已完成"
            
            Log.d(TAG, "最终回复: $finalAnswer")
            addMessageToHistory(Message(MessageRole.ASSISTANT, finalAnswer))
            stopProgressTimeoutTracking()
            speakText(finalAnswer)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理工具调用异常", e)
            val errorMsg = when {
                e.message?.contains("网络") == true -> "网络连接失败，操作未完成"
                e.message?.contains("API") == true -> "API调用失败: ${e.message}"
                else -> "执行操作失败: ${e.message}"
            }
            uiState = UiState.PROCESSING
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().updateDialogText(errorMsg)
            }
            addMessageToHistory(Message(MessageRole.ASSISTANT, errorMsg))
            stopProgressTimeoutTracking()
            speakText(errorMsg)
        }
    }
    
    /**
     * 构建消息列表
     */
    private fun buildMessageList(): MutableList<Message> {
        val messages = mutableListOf<Message>()
        
        // 添加system prompt
        val systemPrompt = llmService?.buildSystemPrompt() ?: ""
        messages.add(Message(MessageRole.SYSTEM, systemPrompt))
        
        // 添加历史对话（保留最近3轮）
        val history = _conversationHistory.value
        val recentHistory = if (history.size > 6) {  // 3轮=6条消息
            history.takeLast(6)
        } else {
            history
        }
        messages.addAll(recentHistory)
        
        return messages
    }
    
    /**
     * 语音播报
     */
    private suspend fun speakText(text: String) = withContext(Dispatchers.IO) {
        try {
            addAssistantMessageIfNeeded(text)
            _state.value = AssistantState.SPEAKING
            _statusText.value = "正在播报..."
            
            // 显示回复内容
            withContext(Dispatchers.Main) {
            GlobalCustomViewManager.getInstance().updateDialogText(text)
            }
            
            Log.d(TAG, "开始TTS: $text")
            
            // 显示正在合成语音
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().updateDialogText("正在合成语音...")
            }
            
            // 获取Token
            val token = try {
                val tokenHelper = AliyunTokenHelper(nlsAccessKeyId, nlsAccessKeySecret)
                tokenHelper.getToken()
            } catch (e: Exception) {
                Log.e(TAG, "获取Token失败", e)
                _state.value = AssistantState.IDLE
                return@withContext
            }
            
            // 初始化NLS客户端
            nlsClient = NlsClient(token)
            
            // 创建输出文件
            val outputFile = File(ttsPath, "assistant_tts_${System.currentTimeMillis()}.wav")
            val parent = outputFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            
            // 创建合成器监听器
            val listener = object : SpeechSynthesizerListener() {
                override fun onComplete(response: SpeechSynthesizerResponse) {
                    Log.d(TAG, "TTS合成完成")
                    // 显示正在播放
                    CoroutineScope(Dispatchers.Main).launch {
                        GlobalCustomViewManager.getInstance().updateDialogText("正在播放...")
                    }
                    // 播放音频
                    playAudio(outputFile.absolutePath)
                }
                
                override fun onMessage(message: ByteBuffer) {
                    try {
                        val bytesArray = ByteArray(message.remaining())
                        message.get(bytesArray, 0, bytesArray.size)
                        FileOutputStream(outputFile, true).use { fos ->
                            fos.write(bytesArray)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "写入TTS数据失败", e)
                    }
                }
                
                override fun onFail(response: SpeechSynthesizerResponse) {
                    Log.e(TAG, "TTS合成失败: ${response.statusText}")
                    _state.value = AssistantState.IDLE
                }
                
                override fun onMetaInfo(response: SpeechSynthesizerResponse) {
                    Log.d(TAG, "TTS MetaInfo: ${response.taskId}")
                }
            }
            
            synthesizer = SpeechSynthesizer(nlsClient, listener)
            synthesizer?.setAppKey(nlsAppKey)
            synthesizer?.setFormat(OutputFormatEnum.WAV)
            synthesizer?.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K)
            synthesizer?.setVoice("siyue")  // 使用思悦音色
            synthesizer?.setText(text)
            
            synthesizer?.start()
            synthesizer?.waitForComplete()
            synthesizer?.close()
            synthesizer = null
            
        } catch (e: Exception) {
            Log.e(TAG, "TTS异常", e)
            _state.value = AssistantState.IDLE
        }
    }
    
    /**
     * 播放音频
     */
    private fun playAudio(filePath: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(filePath)
                setOnPreparedListener {
                    lastTtsDurationMs = it.duration
                    it.start()
                    Log.d(TAG, "开始播放音频")
                }
                setOnCompletionListener {
                    Log.d(TAG, "音频播放完成")
                    it.release()
                    mediaPlayer = null
                    _state.value = AssistantState.IDLE
                    _statusText.value = ""
                    // 播放完成后立即关闭“正在播放”界面
                    uiState = UiState.IDLE
                    cancelCloseView()
                    CoroutineScope(Dispatchers.Main).launch {
                        GlobalCustomViewManager.getInstance().updateDialogText("")
                        GlobalCustomViewManager.getInstance().closeView()
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "音频播放失败: what=$what, extra=$extra")
                    mp.release()
                    mediaPlayer = null
                    _state.value = AssistantState.IDLE
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放音频异常", e)
            _state.value = AssistantState.IDLE
        }
    }
    
    /**
     * 添加消息到历史
     */
    private fun addMessageToHistory(message: Message) {
        val currentHistory = _conversationHistory.value.toMutableList()
        currentHistory.add(message)
        _conversationHistory.value = currentHistory
        Log.d(TAG, "添加消息到历史: ${message.role} - ${message.content?.take(50)}")
    }

    /**
     * 避免重复写入相同的助手回复
     */
    private fun addAssistantMessageIfNeeded(
        content: String,
        mediaType: MessageMediaType? = null,
        mediaPath: String? = null
    ) {
        if (content.isBlank() && mediaPath == null) return
        val history = _conversationHistory.value
        val now = System.currentTimeMillis()
        val recentSame = history.takeLast(5).firstOrNull { msg ->
            msg.role == MessageRole.ASSISTANT &&
                msg.content == content &&
                msg.mediaType == mediaType &&
                msg.mediaPath == mediaPath &&
                (now - msg.timestamp) <= 3000
        }
        if (recentSame != null) return
        addMessageToHistory(
            Message(
                role = MessageRole.ASSISTANT,
                content = content,
                mediaType = mediaType,
                mediaPath = mediaPath
            )
        )
    }

    /**
     * 外部模块写入聊天消息
     */
    fun addExternalMessage(
        content: String,
        role: MessageRole = MessageRole.ASSISTANT,
        mediaType: MessageMediaType? = null,
        mediaPath: String? = null
    ) {
        addMessageToHistory(Message(role, content, mediaType = mediaType, mediaPath = mediaPath))
    }
    
    /**
     * 处理唇语识别任务（只负责触发录像）
     */
    private suspend fun handleLipReadingTask(durationSeconds: Int) {
        try {
            stopProgressTimeoutTracking()
            Log.d(TAG, "触发唇语识别录像，时长: ${durationSeconds}秒")

            val serverError = checkLipReadingServerReady()
            if (serverError != null) {
                _state.value = AssistantState.IDLE
                _statusText.value = serverError
                uiState = UiState.IDLE
                GlobalCustomViewManager.getInstance().updateDialogText(serverError)
                addMessageToHistory(Message(MessageRole.ASSISTANT, serverError))
                speakText(serverError)
                scope.launch {
                    delay(5000)
                    GlobalCustomViewManager.getInstance().refreshScreen()
                }
                return
            }
            
            _state.value = AssistantState.CAMERA
            _statusText.value = "正在录像..."
            uiState = UiState.PROCESSING
            
            // 显示开始录像
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().updateDialogText("正在录像 ${durationSeconds}秒...")
            }
            
            videoUploadManager.initialize()

            lipReadingResultJob?.cancel()
            val sessionId = withContext(Dispatchers.Main) {
                videoUploadManager.startRecording(durationSeconds)
            }

            lipReadingResultJob = scope.launch {
                videoUploadManager.lipReadingResult
                    .filterNotNull()
                    .take(1)
                    .collect { result: String ->
                        if (videoUploadManager.currentLipReadingSessionId() != sessionId) {
                            Log.w(TAG, "忽略过期唇语结果 session=$sessionId")
                            return@collect
                        }
                        withContext(Dispatchers.Main) {
                            GlobalCustomViewManager.getInstance().updateDialogText("正在处理指令~")
                        }
                        Log.d(TAG, "收到本次唇语识别结果(session=$sessionId): $result")
                    }
            }
            
            _state.value = AssistantState.IDLE
            _statusText.value = ""
            
        } catch (e: Exception) {
            Log.e(TAG, "触发录像异常", e)
            addMessageToHistory(Message(MessageRole.ASSISTANT, "录像启动失败: ${e.message}"))
            speakText("无法开始录像")
        }
    }
    
    /**
     * 停止当前操作
     */
    private fun stopCurrentOperation() {
        try {
            // Rokid CXR API不需要手动关闭相机
            synthesizer?.close()
            synthesizer = null
            mediaPlayer?.release()
            mediaPlayer = null
            nlsClient?.shutdown()
            nlsClient = null
        } catch (e: Exception) {
            Log.e(TAG, "停止操作异常", e)
        }
    }
    
    /**
     * 设置唇语识别录像时长（实际保存在SharedPreferences，全局可用）
     */
    fun setLipReadingDuration(duration: Int) {
        if (duration in 3..30) {
            val prefs = context.getSharedPreferences("ai_interaction_config", Context.MODE_PRIVATE)
            prefs.edit().putInt("lip_reading_duration", duration).apply()
            Log.d(TAG, "唇语识别时长设置为: ${duration}秒（全局配置）")
        }
    }
    
    /**
     * 设置唇语识别服务器地址（全局可用）
     */
    fun setLipReadingServerUrl(url: String) {
        val prefs = context.getSharedPreferences("ai_interaction_config", Context.MODE_PRIVATE)
        prefs.edit().putString("lip_reading_server_url", url).apply()
        videoUploadManager.setUploadUrl(url)
        Log.d(TAG, "唇语识别服务器地址设置为: $url（全局配置）")
    }
    
    /**
     * 手动触发唇语识别（从UI触发，只负责开始录像）
     */
    suspend fun triggerLipReading(
        duration: Int,
        onStatusUpdate: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // 只负责触发录像
        withContext<Unit>(Dispatchers.Main) {
            videoUploadManager.startRecording(duration)
        }
        
        // 后续需要手动上传
        onStatusUpdate("录像已开始，请手动上传")
    }
    
    /**
     * 取消关闭界面的任务
     */
    private fun cancelCloseView() {
        closeViewJob?.cancel()
        closeViewJob = null
        Log.d(TAG, "已取消自动关闭界面任务")
    }
    
    /**
     * 启动聆听超时任务：当识别停止后，如果2秒内没有新的识别开始，则关闭"聆听中"状态
     */
    private fun startListeningTimeout() {
        cancelListeningTimeout()
        listeningTimeoutJob = scope.launch {
            delay(2000) // 2秒超时
            // 再次检查状态，如果仍然是LISTENING且没有识别文本，则关闭
            if (_state.value == AssistantState.LISTENING) {
                val hasRecognitionText = GlobalVoiceRecognitionManager.getInstance().recognitionText.value.isNotEmpty()
                if (!hasRecognitionText) {
                    Log.d(TAG, "聆听超时且无识别文本，关闭对话界面")
                    uiState = UiState.IDLE
                    _state.value = AssistantState.IDLE
                    _statusText.value = ""
                    GlobalCustomViewManager.getInstance().updateDialogText("")
                    GlobalCustomViewManager.getInstance().closeView()
                } else {
                    Log.d(TAG, "聆听超时但有识别文本，保持状态")
                }
            }
        }
    }
    
    /**
     * 取消聆听超时任务
     */
    private fun cancelListeningTimeout() {
        listeningTimeoutJob?.cancel()
        listeningTimeoutJob = null
    }

    private fun startProgressTimeoutTracking() {
        progressTrackingEnabled = true
        markProgress("start_tracking")
    }

    private fun stopProgressTimeoutTracking() {
        progressTrackingEnabled = false
        progressTimeoutJob?.cancel()
        progressTimeoutJob = null
    }

    private fun markProgress(reason: String) {
        if (!progressTrackingEnabled) return
        progressTimeoutJob?.cancel()
        progressTimeoutJob = scope.launch {
            delay(progressTimeoutMs)
            handleProgressTimeout(reason)
        }
    }

    private fun handleProgressTimeout(lastReason: String) {
        if (!progressTrackingEnabled) return
        progressTrackingEnabled = false
        Log.w(TAG, "处理超时，自动暂停（最后进展: $lastReason）")
        _state.value = AssistantState.IDLE
        _statusText.value = "已暂停"
        uiState = UiState.IDLE
        val msg = "处理超时，已暂停，请重试"
        addMessageToHistory(Message(MessageRole.ASSISTANT, msg))
        GlobalCustomViewManager.getInstance().updateDialogText(msg)
        GlobalCustomViewManager.getInstance().closeView()
        streamingTTSManager?.clearQueue()
    }

    private fun getLipReadingServerUrl(): String {
        return try {
            val prefs = context.getSharedPreferences("ai_interaction_config", Context.MODE_PRIVATE)
            prefs.getString("lip_reading_server_url", "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun checkLipReadingServerReady(): String? = withContext(Dispatchers.IO) {
        val url = getLipReadingServerUrl().trim()
        if (url.isEmpty()) {
            return@withContext "未配置唇语识别服务器地址"
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext "服务器地址格式错误"
        }
        val wifiStatus = try {
            GlobalWifiManager.getInstance().wifiStatus.value
        } catch (e: Exception) {
            null
        }
        if (wifiStatus == GlobalWifiManager.WifiStatus.DISCONNECTED) {
            return@withContext "WiFi未连接，无法上传"
        }
        return@withContext try {
            val uri = URI(url)
            val host = uri.host ?: return@withContext "服务器地址无效"
            val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2000)
            }
            null
        } catch (e: Exception) {
            "服务器无法连接，请检查地址或网络"
        }
    }
    
    /**
     * 启动延迟关闭界面（仅在DISPLAYING状态时执行）
     * 使用debounce机制，可被新的语音输入取消
     */
    private fun startAutoCloseIfIdle(delayMs: Long) {
        // 先取消旧的关闭任务
        cancelCloseView()
        
        // 只有在DISPLAYING状态时才允许自动关闭
        if (uiState != UiState.DISPLAYING) {
            Log.d(TAG, "当前状态不是DISPLAYING，不启动自动关闭 (状态: $uiState)")
            return
        }
        
        closeViewJob = scope.launch {
            delay(delayMs)
            
            // 再次检查状态（可能在等待期间状态已改变）
            if (uiState == UiState.DISPLAYING) {
                Log.d(TAG, "无新输入，自动关闭对话界面 (delay=${delayMs}ms)")
                GlobalCustomViewManager.getInstance().closeView()
                uiState = UiState.IDLE
            } else {
                Log.d(TAG, "状态已改变，取消关闭 (当前状态: $uiState)")
            }
        }
    }

    private fun computeAutoCloseDelayMs(durationMs: Int): Long {
        if (durationMs <= 0) return 2000L
        val bufferMs = 600L
        val scaled = durationMs.toLong() + bufferMs
        return scaled.coerceIn(1500L, 8000L)
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        disable()
        cancelCloseView()
        cancelListeningTimeout()
        scope.cancel()
        stopCurrentOperation()
        instance = null
    }
}
