package com.rokid.cxrmsamples.managers

import android.content.Context
import android.util.Log
import com.alibaba.nls.client.protocol.InputFormatEnum
import com.alibaba.nls.client.protocol.NlsClient
import com.alibaba.nls.client.protocol.SampleRateEnum
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.listeners.AudioStreamListener
import com.rokid.cxrmsamples.utils.AliyunTokenHelper
import com.rokid.cxrmsamples.utils.MediaPathProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 全局语音识别管理器
 * 用于在AI助手启动时自动开始语音识别并流式显示结果
 */
class GlobalVoiceRecognitionManager private constructor() {
    
    private val TAG = "GlobalVoiceRecognition"
    
    companion object {
        @Volatile
        private var instance: GlobalVoiceRecognitionManager? = null
        
        fun getInstance(): GlobalVoiceRecognitionManager {
            return instance ?: synchronized(this) {
                instance ?: GlobalVoiceRecognitionManager().also { instance = it }
            }
        }
    }
    
    // 配置信息
    private var appKey: String = ""
    private var accessKeyId: String = ""
    private var accessKeySecret: String = ""
    
    // 识别状态
    private val _isRecognizing = MutableStateFlow(false)
    val isRecognizing: StateFlow<Boolean> = _isRecognizing.asStateFlow()
    
    // 识别文字（实时更新，用于显示）
    private val _recognitionText = MutableStateFlow("")
    val recognitionText: StateFlow<String> = _recognitionText.asStateFlow()
    
    // 最终识别结果（句子完成后才更新，用于提交给助手）
    private val _finalRecognitionResult = MutableStateFlow<String?>(null)
    val finalRecognitionResult: StateFlow<String?> = _finalRecognitionResult.asStateFlow()
    
    // NLS客户端和识别器
    private var nlsClient: NlsClient? = null
    private var recognizer: SpeechTranscriber? = null
    private var audioListener: AudioStreamListener? = null
    
    // 录音文件
    private var recordName = ""
    private val recordPath = MediaPathProvider.getRootPath()
    
    // 自动停止识别定时器（初始超时 No-Input Timeout）
    private var autoStopJob: Job? = null
    private val autoStopTimeoutMs = 4000L  // 4秒无语音输入自动停止（参考Siri/小爱标准）
    
    // 最大语音时长定时器（防止在嘈杂环境下无限录音）
    private var maxDurationJob: Job? = null
    private val maxSpeechDurationMs = 10000L  // 最大语音时长：10秒
    
    // VAD 调优参数 - 指令控制模式
    private val maxSentenceSilenceMs = 800     // 句尾静音检测阈值：800ms
    private val speechNoiseThreshold = 0.0     // 噪声阈值
    private val enableSemanticSentence = false // 使用VAD断句而非语义断句
    
    // 自动关闭界面定时器
    private var autoCloseViewJob: Job? = null
    private val autoCloseViewDelayMs = 5000L  // 识别完成后5秒自动关闭界面
    
    /**
     * 配置语音识别参数
     */
    fun configure(appKey: String, accessKeyId: String, accessKeySecret: String) {
        this.appKey = appKey
        this.accessKeyId = accessKeyId
        this.accessKeySecret = accessKeySecret
        Log.d(TAG, "语音识别配置已更新")
    }
    
    /**
     * 开始语音识别
     */
    fun startRecognition() {
        if (appKey.isEmpty() || accessKeyId.isEmpty() || accessKeySecret.isEmpty()) {
            Log.e(TAG, "语音识别配置未设置")
            return
        }
        
        if (_isRecognizing.value) {
            Log.w(TAG, "语音识别已在进行中")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 获取Token
                val token = try {
                    Log.d(TAG, "开始获取Token")
                    val tokenHelper = AliyunTokenHelper(accessKeyId, accessKeySecret)
                    tokenHelper.getToken()
                } catch (e: Exception) {
                    Log.e(TAG, "获取Token失败", e)
                    withContext(Dispatchers.Main) {
                        GlobalCustomViewManager.getInstance().updateDialogText("获取Token失败")
                    }
                    return@launch
                }
                
                _isRecognizing.value = true
                _recognitionText.value = ""
                
                Log.d(TAG, "初始化NLS客户端")
                nlsClient = NlsClient(token)
                
                // 创建识别器监听器
                val listener = object : SpeechTranscriberListener() {
                    override fun onTranscriptionResultChange(response: SpeechTranscriberResponse) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val text = response.transSentenceText
                            if (text != null && text.isNotEmpty()) {
                                // 仅用于实时显示，不触发处理
                                _recognitionText.value = text
                                GlobalCustomViewManager.getInstance().updateDialogText(text)
                                Log.d(TAG, "识别中（实时）: $text")
                            }
                        }
                    }
                    
                    override fun onTranscriberStart(response: SpeechTranscriberResponse) {
                        Log.d(TAG, "识别已开始")
                        CoroutineScope(Dispatchers.Main).launch {
                            _recognitionText.value = ""
                            _finalRecognitionResult.value = null  // 清空上次结果
                            GlobalCustomViewManager.getInstance().updateDialogText("正在聆听...")
                        }
                        // 启动自动停止定时器（4秒无输入）
                        startAutoStopTimer()
                        // 启动最大时长定时器（10秒总时长）
                        startMaxDurationTimer()
                    }
                    
                    override fun onSentenceBegin(response: SpeechTranscriberResponse) {
                        Log.d(TAG, "检测到语音输入，开始新句子")
                        startAutoStopTimer()
                    }
                    
                    override fun onSentenceEnd(response: SpeechTranscriberResponse) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val text = response.transSentenceText
                            if (text != null && text.isNotEmpty()) {
                                _recognitionText.value = text
                                // ⭐ 句子结束，设置最终结果，触发助手处理
                                _finalRecognitionResult.value = text
                                GlobalCustomViewManager.getInstance().updateDialogText(text)
                                Log.d(TAG, "⭐ 句子完成，提交给助手: $text")
                            }
                        }
                    }
                    
                    override fun onTranscriptionComplete(response: SpeechTranscriberResponse) {
                        Log.d(TAG, "识别流程完成")
                        cancelAutoStopTimer()
                        cancelMaxDurationTimer()
                        
                        CoroutineScope(Dispatchers.Main).launch {
                            // 如果有未提交的文本，现在提交
                            val currentText = _recognitionText.value
                            if (currentText.isNotEmpty() && _finalRecognitionResult.value != currentText) {
                                Log.d(TAG, "⭐ 识别完成，提交最终结果: $currentText")
                                _finalRecognitionResult.value = currentText
                            }
                        }
                        
                        // UI关闭由VoiceAssistantManager统一管理，不再自动关闭
                        // startAutoCloseViewTimer() // 已移除：由VoiceAssistantManager的状态机控制
                    }
                    
                    override fun onFail(response: SpeechTranscriberResponse) {
                        Log.e(TAG, "识别失败: ${response.statusText}")
                        cancelAutoStopTimer()
                        cancelMaxDurationTimer()
                        CoroutineScope(Dispatchers.Main).launch {
                            GlobalCustomViewManager.getInstance().updateDialogText("识别失败")
                            _isRecognizing.value = false
                        }
                    }
                }
                
                recognizer = SpeechTranscriber(nlsClient, listener)
                recognizer?.setAppKey(appKey)
                recognizer?.setFormat(InputFormatEnum.PCM)
                recognizer?.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K)
                recognizer?.setEnableIntermediateResult(true)
                recognizer?.setEnablePunctuation(true)
                
                // VAD参数配置（与VoiceServiceViewModel保持一致）
                recognizer?.addCustomedParam("enable_semantic_sentence_detection", enableSemanticSentence)
                recognizer?.addCustomedParam("max_sentence_silence", maxSentenceSilenceMs)
                recognizer?.addCustomedParam("speech_noise_threshold", speechNoiseThreshold)
                Log.d(TAG, "VAD参数: max_sentence_silence=${maxSentenceSilenceMs}ms, speech_noise_threshold=$speechNoiseThreshold")
                
                Log.d(TAG, "启动识别器")
                recognizer?.start()
                
                // 开始录音
                recordName = "voice_${System.currentTimeMillis()}.pcm"
                audioListener = object : AudioStreamListener {
                    override fun onStartAudioStream(streamId: Int, codec: Int, mode: Int, cmd: String?) {
                        Log.d(TAG, "音频流已启动 streamId=$streamId")
                    }
                    
                    override fun onAudioStream(streamId: Int, data: ByteArray?, offset: Int, size: Int) {
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
                                val dataToSend = if (offset == 0 && size == it.size) {
                                    it
                                } else {
                                    it.copyOfRange(offset, offset + size)
                                }
                                recognizer?.send(dataToSend)
                            } catch (e: Exception) {
                                Log.e(TAG, "发送音频数据失败", e)
                            }
                        }
                    }
                    override fun onAudioStreamFinish(streamId: Int) {
                        Log.d(TAG, "音频流结束 streamId=$streamId")
                    }
                }
                
                CxrApi.getInstance().setAudioStreamListener(audioListener)
                CxrApi.getInstance().openAudioRecord(1, 0, "voice_recognition")
                Log.d(TAG, "音频录制已启动")
                
            } catch (e: Exception) {
                Log.e(TAG, "启动识别失败", e)
                _isRecognizing.value = false
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().updateDialogText("启动识别失败")
                }
            }
        }
    }
    
    /**
     * 停止语音识别
     */
    fun stopRecognition() {
        cancelAutoStopTimer()
        cancelMaxDurationTimer()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "停止识别")
                
                // 在停止前，如果有未提交的文本，提交最终结果
                val currentText = _recognitionText.value
                if (currentText.isNotEmpty() && _finalRecognitionResult.value != currentText) {
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "⭐ 手动停止时提交最终结果: $currentText")
                        _finalRecognitionResult.value = currentText
                    }
                }
                
                CxrApi.getInstance().closeAudioRecord("voice_recognition")
                CxrApi.getInstance().setAudioStreamListener(null)
                
                recognizer?.stop()
                recognizer?.close()
                recognizer = null
                
                _isRecognizing.value = false
                
                // UI关闭由VoiceAssistantManager统一管理，不再自动关闭
                // startAutoCloseViewTimer() // 已移除：由VoiceAssistantManager的状态机控制
            } catch (e: Exception) {
                Log.e(TAG, "停止识别失败", e)
            }
        }
    }
    
    /**
     * 清空识别结果（助手处理完成后调用）
     */
    fun clearRecognitionResult() {
        _recognitionText.value = ""
        _finalRecognitionResult.value = null
        Log.d(TAG, "识别结果已清空")
    }
    
    /**
     * 启动自动停止定时器
     */
    private fun startAutoStopTimer() {
        autoStopJob?.cancel()
        autoStopJob = CoroutineScope(Dispatchers.Main).launch {
            delay(autoStopTimeoutMs)
            Log.d(TAG, "无语音输入，自动停止识别")
            
            // 检查是否有识别到的文本
            val currentText = _recognitionText.value
            if (currentText.isEmpty()) {
                _finalRecognitionResult.value = null  // 不触发处理
            } else {
                // 有识别文本，正常提交
                Log.d(TAG, "⭐ 超时停止，但有识别文本，提交: $currentText")
                _finalRecognitionResult.value = currentText
            }
            
            stopRecognition()
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
        maxDurationJob = CoroutineScope(Dispatchers.Main).launch {
            delay(maxSpeechDurationMs)
            Log.d(TAG, "最大时长限制：已录音${maxSpeechDurationMs}ms，强制停止")
            
            // 检查是否有识别到的文本
            val currentText = _recognitionText.value
            if (currentText.isNotEmpty()) {
                Log.d(TAG, "⭐ 达到最大时长，但有识别文本，提交: $currentText")
                _finalRecognitionResult.value = currentText
            } else {
                GlobalCustomViewManager.getInstance().updateDialogText("录音时长超限")
                _finalRecognitionResult.value = null
            }
            
            stopRecognition()
        }
    }
    
    /**
     * 取消最大时长定时器
     */
    private fun cancelMaxDurationTimer() {
        maxDurationJob?.cancel()
        maxDurationJob = null
    }
    
    /**
     * 启动自动关闭界面定时器（识别完成后5秒自动关闭）
     */
    private fun startAutoCloseViewTimer() {
        autoCloseViewJob?.cancel()
        autoCloseViewJob = CoroutineScope(Dispatchers.Main).launch {
            delay(autoCloseViewDelayMs)
            Log.d(TAG, "识别完成${autoCloseViewDelayMs}ms后，自动关闭对话界面")
            GlobalCustomViewManager.getInstance().closeView()
        }
    }
    
    /**
     * 取消自动关闭界面定时器
     */
    private fun cancelAutoCloseViewTimer() {
        autoCloseViewJob?.cancel()
        autoCloseViewJob = null
    }
    
    /**
     * 取消自动关闭界面定时器（公开方法，供语音助手调用）
     */
    fun cancelAutoCloseView() {
        cancelAutoCloseViewTimer()
        Log.d(TAG, "已取消自动关闭界面定时器")
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopRecognition()
        cancelAutoStopTimer()
        cancelMaxDurationTimer()
        cancelAutoCloseViewTimer()
        nlsClient?.shutdown()
    }
}
