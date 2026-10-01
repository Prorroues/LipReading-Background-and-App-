package com.rokid.cxrmsamples.activities.voiceService

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.nls.client.protocol.InputFormatEnum
import com.alibaba.nls.client.protocol.NlsClient
import com.alibaba.nls.client.protocol.OutputFormatEnum
import com.alibaba.nls.client.protocol.SampleRateEnum
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.listeners.AudioStreamListener
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.managers.GlobalWifiManager
import com.rokid.cxrmsamples.utils.MediaPathProvider
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager
import com.rokid.cxrmsamples.managers.GlobalVoiceRecognitionManager
import com.rokid.cxrmsamples.utils.AliyunTokenHelper
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class VoiceServiceViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "VoiceServiceViewModel"
    
    private val sharedPreferences = application.getSharedPreferences("voice_service_config", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_APP_KEY = "app_key"
        private const val KEY_ACCESS_KEY_ID = "access_key_id"
        private const val KEY_ACCESS_KEY_SECRET = "access_key_secret"
        private const val KEY_QWEN_API_KEY = "qwen_api_key"
    }

    // 配置状态 - 从 SharedPreferences 加载
    private val _appKey = MutableStateFlow(sharedPreferences.getString(KEY_APP_KEY, "") ?: "")
    val appKey = _appKey.asStateFlow()

    private val _accessKeyId = MutableStateFlow(sharedPreferences.getString(KEY_ACCESS_KEY_ID, "") ?: "")
    val accessKeyId = _accessKeyId.asStateFlow()

    private val _accessKeySecret = MutableStateFlow(sharedPreferences.getString(KEY_ACCESS_KEY_SECRET, "") ?: "")
    val accessKeySecret = _accessKeySecret.asStateFlow()
    
    // 千问API密钥配置
    private val _qwenApiKey = MutableStateFlow(sharedPreferences.getString(KEY_QWEN_API_KEY, "") ?: "")
    val qwenApiKey = _qwenApiKey.asStateFlow()

    // 识别状态
    private val _isRecognizing = MutableStateFlow(false)
    val isRecognizing = _isRecognizing.asStateFlow()

    private val _recognitionText = MutableStateFlow("")
    val recognitionText = _recognitionText.asStateFlow()

    // 合成状态
    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing = _isSynthesizing.asStateFlow()

    private val _synthesisText = MutableStateFlow("")
    val synthesisText = _synthesisText.asStateFlow()

    // 状态消息
    private val _statusMessage = MutableStateFlow("未开始")
    val statusMessage = _statusMessage.asStateFlow()

    // 最近一次合成音频文件路径
    private val _lastTtsFilePath = MutableStateFlow<String?>(null)
    val lastTtsFilePath = _lastTtsFilePath.asStateFlow()

    // WiFi P2P 连接状态
    enum class ConnectionStatus {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private val _wifiConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val wifiConnectionStatus = _wifiConnectionStatus.asStateFlow()

    // 录音相关
    private var recordName = ""
    private val recordPath = MediaPathProvider.getRootPath()
    private var audioListener: AudioStreamListener? = null

    // NLS客户端和识别器/合成器
    private var nlsClient: NlsClient? = null
    private var recognizer: SpeechTranscriber? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var mediaPlayer: MediaPlayer? = null
    
    // 自动停止识别的定时器（初始超时 No-Input Timeout）
    private var autoStopJob: Job? = null
    private val autoStopTimeoutMs = 4000L  // 4秒无语音输入自动停止（参考Siri/小爱标准，平衡响应与误触）
    
    // 最大语音时长定时器（防止在嘈杂环境下无限录音）
    private var maxDurationJob: Job? = null

    // VAD 调优参数 - 指令控制模式（Command & Control Mode）
    // 参考：Siri 600-700ms, Google 400-800ms, 小爱 800-1000ms
    private val maxSentenceSilenceMs = 800     // 句尾静音检测阈值：800ms（行业最佳实践，追求极致响应）
    private val maxSpeechDurationMs = 10000    // 最大语音时长：10秒（防止嘈杂环境一直录音）
    private val speechNoiseThreshold = 0.0     // 噪声阈值：-1到+1，默认0.0（环境吵时可调0.1~0.3）
    private val enableSemanticSentence = false // 使用VAD断句而非语义断句（语义断句会让max_sentence_silence失效）
    
    // 同步全局WiFi状态到本地
    init {
        CoroutineScope(Dispatchers.Main).launch {
            GlobalWifiManager.getInstance().wifiStatus.collect { globalStatus ->
                _wifiConnectionStatus.value = when (globalStatus) {
                    GlobalWifiManager.WifiStatus.CONNECTED -> ConnectionStatus.CONNECTED
                    GlobalWifiManager.WifiStatus.CONNECTING -> ConnectionStatus.CONNECTING
                    GlobalWifiManager.WifiStatus.DISCONNECTED -> ConnectionStatus.DISCONNECTED
                }
            }
        }
        
        // 同步配置到全局识别管理器
        updateGlobalRecognitionConfig()
    }

    // WiFi P2P 回调
    private val wifiP2PStatusCallback = object : WifiP2PStatusCallback {
        override fun onConnected() {
            Log.d(TAG, "WiFi P2P connected successfully")
            CoroutineScope(Dispatchers.Main).launch {
                _wifiConnectionStatus.value = ConnectionStatus.CONNECTED
                _statusMessage.value = "WiFi已连接"
            }
        }

        override fun onDisconnected() {
            Log.d(TAG, "WiFi P2P disconnected")
            CoroutineScope(Dispatchers.Main).launch {
                _wifiConnectionStatus.value = ConnectionStatus.DISCONNECTED
                _statusMessage.value = "WiFi已断开"
            }
        }

        override fun onP2pDeviceAvailable(name: String?, address: String?, info: String?) {
            Log.d(TAG, "WiFi P2P device available: $name $address")
        }

        override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
            Log.e(TAG, "WiFi P2P connection failed: $errorCode")
            CoroutineScope(Dispatchers.Main).launch {
                _wifiConnectionStatus.value = ConnectionStatus.DISCONNECTED
                _statusMessage.value = "WiFi连接失败: $errorCode"
            }
        }
    }

    fun setAppKey(value: String) {
        _appKey.value = value
        sharedPreferences.edit().putString(KEY_APP_KEY, value).apply()
        // 同步配置到全局识别管理器
        updateGlobalRecognitionConfig()
    }

    fun setAccessKeyId(value: String) {
        _accessKeyId.value = value
        sharedPreferences.edit().putString(KEY_ACCESS_KEY_ID, value).apply()
        // 同步配置到全局识别管理器
        updateGlobalRecognitionConfig()
    }

    fun setAccessKeySecret(value: String) {
        _accessKeySecret.value = value
        sharedPreferences.edit().putString(KEY_ACCESS_KEY_SECRET, value).apply()
        // 同步配置到全局识别管理器
        updateGlobalRecognitionConfig()
    }
    
    fun setQwenApiKey(value: String) {
        _qwenApiKey.value = value
        sharedPreferences.edit().putString(KEY_QWEN_API_KEY, value).apply()
        // 同步配置到语音助手
        updateVoiceAssistantConfig()
    }
    
    /**
     * 更新全局识别管理器配置
     */
    private fun updateGlobalRecognitionConfig() {
        if (_appKey.value.isNotEmpty() && _accessKeyId.value.isNotEmpty() && _accessKeySecret.value.isNotEmpty()) {
            GlobalVoiceRecognitionManager.getInstance().configure(
                _appKey.value,
                _accessKeyId.value,
                _accessKeySecret.value
            )
        }
    }
    
    /**
     * 更新全局语音助手配置
     */
    private fun updateVoiceAssistantConfig() {
        // 同步NLS配置到全局服务
        if (_appKey.value.isNotEmpty() && _accessKeyId.value.isNotEmpty() && _accessKeySecret.value.isNotEmpty()) {
            // 配置全局视频上传管理器
            com.rokid.cxrmsamples.managers.GlobalVideoUploadManager.getInstance(getApplication()).configure(
                _appKey.value,
                _accessKeyId.value,
                _accessKeySecret.value
            )
            
            // 如果有千问API Key，配置语音助手
            if (_qwenApiKey.value.isNotEmpty()) {
                com.rokid.cxrmsamples.services.assistant.VoiceAssistantManager.getInstance(getApplication()).configure(
                    qwenApiKey = _qwenApiKey.value,
                    nlsAppKey = _appKey.value,
                    nlsAccessKeyId = _accessKeyId.value,
                    nlsAccessKeySecret = _accessKeySecret.value
                )
                Log.d(TAG, "全局语音助手配置已同步")
            }
        }
    }

    fun setSynthesisText(value: String) {
        _synthesisText.value = value
    }
    
    /**
     * 启动自动停止定时器：如果超过指定时间无语音输入，自动停止识别
     */
    private fun startAutoStopTimer() {
        autoStopJob?.cancel()
        autoStopJob = viewModelScope.launch {
            delay(autoStopTimeoutMs)
            Log.d(TAG, "自动停止：${autoStopTimeoutMs}ms 内无语音输入，自动停止识别")
            withContext(Dispatchers.Main) {
                _statusMessage.value = "无语音输入，自动停止"
                stopRecognition()
            }
        }
    }
    
    /**
     * 取消自动停止定时器
     */
    private fun cancelAutoStopTimer() {
        autoStopJob?.cancel()
        autoStopJob = null
    }
    
    /**
     * 启动最大语音时长定时器：防止在嘈杂环境下无限录音
     */
    private fun startMaxDurationTimer() {
        maxDurationJob?.cancel()
        maxDurationJob = viewModelScope.launch {
            delay(maxSpeechDurationMs.toLong())
            Log.d(TAG, "最大时长限制：已录音${maxSpeechDurationMs}ms，强制停止")
            withContext(Dispatchers.Main) {
                _statusMessage.value = "录音时长超限，自动停止"
                stopRecognition()
            }
        }
    }
    
    /**
     * 取消最大时长定时器
     */
    private fun cancelMaxDurationTimer() {
        maxDurationJob?.cancel()
        maxDurationJob = null
    }

    fun startRecognition() {
        if (_appKey.value.isEmpty() || _accessKeyId.value.isEmpty() || _accessKeySecret.value.isEmpty()) {
            _statusMessage.value = "请先配置AppKey和AccessKey信息"
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 获取 Token 放到 IO 线程，避免 NetworkOnMainThreadException
                val token = try {
                    Log.d(TAG, "开始获取Token, AccessKeyId: ${_accessKeyId.value.take(10)}***")
                    val tokenHelper = AliyunTokenHelper(_accessKeyId.value, _accessKeySecret.value)
                    tokenHelper.getToken()
                } catch (e: Exception) {
                    Log.e(TAG, "获取Token失败 - 详细错误", e)
                    val errorMsg = when (e) {
                        is IOException -> "网络错误: ${e.message}"
                        is SecurityException -> "签名错误: 请检查AccessKey是否正确"
                        else -> "未知错误: ${e.javaClass.simpleName} - ${e.message}"
                    }
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "获取Token失败: $errorMsg"
                    }
                    return@launch
                }

                _isRecognizing.value = true
                _recognitionText.value = ""
                _statusMessage.value = "正在启动识别..."
                
                Log.d(TAG, "开始初始化NLS客户端")

                // 初始化NLS客户端
                nlsClient = NlsClient(token)
                Log.d(TAG, "NLS客户端初始化成功")

                // 创建识别器监听器
                val listener = object : SpeechTranscriberListener() {
                    override fun onTranscriptionResultChange(response: SpeechTranscriberResponse) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val text = response.transSentenceText
                            if (text != null && text.isNotEmpty()) {
                                _recognitionText.value = text
                                _statusMessage.value = "识别中..."
                                
                                // 流式更新对话文字
                                GlobalCustomViewManager.getInstance().updateDialogText(text)
                            }
                        }
                    }

                    override fun onTranscriberStart(response: SpeechTranscriberResponse) {
                        Log.d(TAG, "Recognition started: ${response.taskId}")
                        CoroutineScope(Dispatchers.Main).launch {
                            _statusMessage.value = "识别已开始，等待语音输入..."
                            // 启动自动停止定时器（4秒无输入）
                            startAutoStopTimer()
                            // 启动最大时长定时器（10秒总时长）
                            startMaxDurationTimer()
                        }
                    }

                    override fun onSentenceBegin(response: SpeechTranscriberResponse) {
                        Log.d(TAG, "Sentence begin: ${response.taskId}")
                        // 检测到新的语音输入，重置自动停止定时器
                        startAutoStopTimer()
                    }

                    override fun onSentenceEnd(response: SpeechTranscriberResponse) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val text = response.transSentenceText
                            if (text != null && text.isNotEmpty()) {
                                _recognitionText.value = text
                                _statusMessage.value = "识别完成一句"
                                
                                // 流式更新对话文字
                                GlobalCustomViewManager.getInstance().updateDialogText(text)
                            }
                        }
                    }

                    override fun onTranscriptionComplete(response: SpeechTranscriberResponse) {
                        Log.d(TAG, "Recognition completed: ${response.taskId}")
                        cancelAutoStopTimer()
                        cancelMaxDurationTimer()
                        CoroutineScope(Dispatchers.Main).launch {
                            _statusMessage.value = "识别完成"
                        }
                    }

                    override fun onFail(response: SpeechTranscriberResponse) {
                        Log.e(TAG, "Recognition failed: ${response.statusText}")
                        cancelAutoStopTimer()
                        cancelMaxDurationTimer()
                        CoroutineScope(Dispatchers.Main).launch {
                            _statusMessage.value = "识别失败: ${response.statusText}"
                            _isRecognizing.value = false
                        }
                    }
                }

                recognizer = SpeechTranscriber(nlsClient, listener)
                Log.d(TAG, "识别器创建成功")
                
                recognizer?.setAppKey(_appKey.value)
                recognizer?.setFormat(InputFormatEnum.PCM)
                recognizer?.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K)
                recognizer?.setEnableIntermediateResult(true)
                recognizer?.setEnablePunctuation(true)
                
                // VAD 调优参数配置
                // 关闭语义断句，启用VAD断句（语义断句会让max_sentence_silence失效）
                recognizer?.addCustomedParam("enable_semantic_sentence_detection", enableSemanticSentence)
                // 句尾静音检测阈值：静音超过此时长认为句子结束，范围200~6000ms
                recognizer?.addCustomedParam("max_sentence_silence", maxSentenceSilenceMs)
                // 噪声阈值：-1到+1，默认0.0（环境吵时可调0.1~0.3）
                recognizer?.addCustomedParam("speech_noise_threshold", speechNoiseThreshold)
                Log.d(TAG, "VAD参数已配置: enable_semantic_sentence_detection=$enableSemanticSentence, max_sentence_silence=${maxSentenceSilenceMs}ms, speech_noise_threshold=$speechNoiseThreshold")
                
                Log.d(TAG, "识别器参数配置完成，准备启动")
                recognizer?.start()
                Log.d(TAG, "识别器启动成功，等待音频数据")

                // 开始录音
                recordName = "voice_${System.currentTimeMillis()}.pcm"
                Log.d(TAG, "准备开始录音: $recordName")
                audioListener = object : AudioStreamListener {
                    override fun onStartAudioStream(streamId: Int, codec: Int, mode: Int, cmd: String?) {
                        Log.d(TAG, "Audio stream started, streamId=$streamId codec=$codec cmd=$cmd")
                    }

                    override fun onAudioStream(streamId: Int, data: ByteArray?, offset: Int, size: Int) {
                        Log.v(TAG, "收到音频数据: size=$size, offset=$offset, dataLength=${data?.size}")
                        // 保存音频数据
                        val file = File(recordPath, recordName)
                        val parent = file.parentFile
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs()
                        }
                        if (!file.exists()) {
                            file.createNewFile()
                        }
                        FileOutputStream(file, true).use { fos ->
                            data?.let { fos.write(it, offset, size) }
                        }

                        // 发送到识别服务
                        data?.let {
                            try {
                                // 如果offset不是0或size不是数组长度，需要创建子数组
                                val dataToSend = if (offset == 0 && size == it.size) {
                                    it
                                } else {
                                    it.copyOfRange(offset, offset + size)
                                }
                                // 直接调用 send(byte[]) - NLS SDK的正确API
                                recognizer?.send(dataToSend)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send audio data", e)
                                CoroutineScope(Dispatchers.Main).launch {
                                    _statusMessage.value = "音频发送失败: ${e.message}"
                                }
                            }
                        }
                    }
                    override fun onAudioStreamFinish(streamId: Int) {
                        Log.d(TAG, "Audio stream finished: $streamId")
                    }
                }

                CxrApi.getInstance().setAudioStreamListener(audioListener)
                Log.d(TAG, "设置音频监听器成功")
                
                CxrApi.getInstance().openAudioRecord(1, 0, "voice_recognition")
                Log.d(TAG, "打开音频录制成功")

            } catch (e: Exception) {
                Log.e(TAG, "启动识别失败 - 详细错误", e)
                val errorDetail = when {
                    e.message?.contains("token", ignoreCase = true) == true -> 
                        "Token错误: ${e.message}"
                    e.message?.contains("appkey", ignoreCase = true) == true -> 
                        "AppKey错误: ${e.message}"
                    e.message?.contains("network", ignoreCase = true) == true -> 
                        "网络错误: ${e.message}"
                    else -> 
                        "${e.javaClass.simpleName}: ${e.message}"
                }
                _statusMessage.value = "启动识别失败: $errorDetail"
                _isRecognizing.value = false
            }
        }
    }

    fun stopRecognition() {
        // 取消所有定时器
        cancelAutoStopTimer()
        cancelMaxDurationTimer()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "开始停止识别")
                CxrApi.getInstance().closeAudioRecord("voice_recognition")
                Log.d(TAG, "关闭音频录制成功")
                
                CxrApi.getInstance().setAudioStreamListener(null)
                Log.d(TAG, "移除音频监听器成功")
                
                recognizer?.stop()
                Log.d(TAG, "识别器停止成功")
                
                recognizer?.close()
                recognizer = null
                Log.d(TAG, "识别器关闭成功")
                
                _isRecognizing.value = false
                _statusMessage.value = "识别已停止"
            } catch (e: Exception) {
                Log.e(TAG, "停止识别失败 - 详细错误", e)
                _statusMessage.value = "停止识别失败: ${e.message}"
            }
        }
    }

    fun startSynthesis() {
        if (_appKey.value.isEmpty() || _accessKeyId.value.isEmpty() || _accessKeySecret.value.isEmpty()) {
            _statusMessage.value = "请先配置AppKey和AccessKey信息"
            return
        }

        if (_synthesisText.value.isEmpty()) {
            _statusMessage.value = "请输入要合成的文本"
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 获取 Token 放到 IO 线程，避免主线程网络访问
                val token = try {
                    Log.d(TAG, "开始获取Token(合成), AccessKeyId: ${_accessKeyId.value.take(10)}***")
                    val tokenHelper = AliyunTokenHelper(_accessKeyId.value, _accessKeySecret.value)
                    tokenHelper.getToken()
                } catch (e: Exception) {
                    Log.e(TAG, "获取Token失败(合成) - 详细错误", e)
                    val errorMsg = when (e) {
                        is IOException -> "网络错误: ${e.message}"
                        is SecurityException -> "签名错误: 请检查AccessKey是否正确"
                        else -> "未知错误: ${e.javaClass.simpleName} - ${e.message}"
                    }
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "获取Token失败: $errorMsg"
                    }
                    return@launch
                }

                _isSynthesizing.value = true
                _statusMessage.value = "正在启动合成..."
                
                Log.d(TAG, "开始初始化NLS客户端（合成）")

                // 初始化NLS客户端
                nlsClient = NlsClient(token)
                Log.d(TAG, "NLS客户端初始化成功（合成）")

                // 创建合成器监听器
                val outputFile = File(recordPath, "tts_${System.currentTimeMillis()}.wav")
                val parent = outputFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }

                val listener = object : SpeechSynthesizerListener() {
                    private var firstRecvBinary = true

                    override fun onComplete(response: SpeechSynthesizerResponse) {
                        Log.d(TAG, "Synthesis completed: ${response.taskId}")
                        CoroutineScope(Dispatchers.Main).launch {
                            _statusMessage.value = "合成完成: ${outputFile.absolutePath}"
                            _isSynthesizing.value = false
                            _lastTtsFilePath.value = outputFile.absolutePath
                        }
                    }

                    override fun onMessage(message: ByteBuffer) {
                        try {
                            if (firstRecvBinary) {
                                firstRecvBinary = false
                                CoroutineScope(Dispatchers.Main).launch {
                                    _statusMessage.value = "正在合成..."
                                }
                            }
                            val bytesArray = ByteArray(message.remaining())
                            message.get(bytesArray, 0, bytesArray.size)
                            FileOutputStream(outputFile, true).use { fos ->
                                fos.write(bytesArray)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to write synthesis data", e)
                        }
                    }

                    override fun onFail(response: SpeechSynthesizerResponse) {
                        Log.e(TAG, "Synthesis failed: ${response.statusText}")
                        CoroutineScope(Dispatchers.Main).launch {
                            _statusMessage.value = "合成失败: ${response.statusText}"
                            _isSynthesizing.value = false
                        }
                    }

                    override fun onMetaInfo(response: SpeechSynthesizerResponse) {
                        Log.d(TAG, "MetaInfo: ${response.taskId}")
                    }
                }

                synthesizer = SpeechSynthesizer(nlsClient, listener)
                Log.d(TAG, "合成器创建成功")
                
                synthesizer?.setAppKey(_appKey.value)
                synthesizer?.setFormat(OutputFormatEnum.WAV)
                synthesizer?.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K)
                synthesizer?.setVoice("siyue")
                synthesizer?.setText(_synthesisText.value)
                Log.d(TAG, "合成器参数配置完成: 文本长度=${_synthesisText.value.length}")

                Log.d(TAG, "启动合成器")
                synthesizer?.start()
                
                Log.d(TAG, "等待合成完成")
                synthesizer?.waitForComplete()
                
                Log.d(TAG, "合成完成，关闭合成器")
                synthesizer?.close()
                synthesizer = null

            } catch (e: Exception) {
                Log.e(TAG, "启动合成失败 - 详细错误", e)
                val errorDetail = when {
                    e.message?.contains("token", ignoreCase = true) == true -> 
                        "Token错误: ${e.message}"
                    e.message?.contains("appkey", ignoreCase = true) == true -> 
                        "AppKey错误: ${e.message}"
                    e.message?.contains("text", ignoreCase = true) == true -> 
                        "文本错误: ${e.message}"
                    else -> 
                        "${e.javaClass.simpleName}: ${e.message}"
                }
                _statusMessage.value = "启动合成失败: $errorDetail"
                _isSynthesizing.value = false
            }
        }
    }

    /**
     * 连接 WiFi P2P
     */
    /**
     * 手动连接 WiFi P2P（委托给全局管理器）
     */
    fun connectWifiP2P() {
        GlobalWifiManager.getInstance().connectWifi()
        _statusMessage.value = "正在连接WiFi..."
    }

    /**
     * 断开 WiFi P2P（委托给全局管理器）
     */
    fun disconnectWifiP2P() {
        GlobalWifiManager.getInstance().disconnectWifi()
        _statusMessage.value = "WiFi已断开"
    }

    /**
     * 播放最近一次合成的音频
     */
    fun playLastTts() {
        val path = _lastTtsFilePath.value
        if (path.isNullOrEmpty()) {
            _statusMessage.value = "没有可播放的合成音频"
            return
        }
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(path)
                setOnPreparedListener {
                    it.start()
                    _statusMessage.value = "正在播放合成音频..."
                }
                setOnCompletionListener {
                    _statusMessage.value = "播放完成"
                    it.release()
                    mediaPlayer = null
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "播放合成音频失败: what=$what, extra=$extra")
                    _statusMessage.value = "播放失败: $what"
                    mp.release()
                    mediaPlayer = null
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放合成音频异常", e)
            _statusMessage.value = "播放失败: ${e.message}"
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopRecognition()
        // WiFi由全局管理器管理，不在这里断开
        mediaPlayer?.release()
        mediaPlayer = null
        synthesizer?.close()
        nlsClient?.shutdown()
    }
}
