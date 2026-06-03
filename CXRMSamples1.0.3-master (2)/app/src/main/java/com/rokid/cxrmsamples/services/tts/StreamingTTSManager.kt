package com.rokid.cxrmsamples.services.tts

import android.util.Log
import com.alibaba.nls.client.protocol.NlsClient
import com.alibaba.nls.client.protocol.OutputFormatEnum
import com.alibaba.nls.client.protocol.SampleRateEnum
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse
import com.rokid.cxrmsamples.utils.AliyunTokenHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 流式TTS管理器
 * 支持文本队列和并发合成，遇到标点符号时立即触发TTS
 */
class StreamingTTSManager(
    private val appKey: String,
    private val accessKeyId: String,
    private val accessKeySecret: String
) {
    private val TAG = "StreamingTTSManager"
    
    // 文本队列
    private val textQueue = Channel<String>(Channel.UNLIMITED)
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 当前TTS任务
    private var currentSynthesizer: SpeechSynthesizer? = null
    private var currentNlsClient: NlsClient? = null
    private var isProcessing = false
    
    // TTS输出目录
    private val ttsPath = "/sdcard/Download/Rokid/assistant"
    
    // 标点符号（触发TTS的符号）
    private val punctuationMarks = setOf('。', '！', '？', '，', '；', '：', '.', '!', '?', ',', ';', ':')
    
    // 音频播放回调
    private var audioPlayCallback: ((String) -> Unit)? = null
    
    /**
     * 设置音频播放回调
     */
    fun setAudioPlayCallback(callback: (String) -> Unit) {
        audioPlayCallback = callback
    }
    
    /**
     * 启动流式TTS处理
     */
    fun start() {
        if (isProcessing) {
            Log.w(TAG, "流式TTS已在运行")
            return
        }
        
        isProcessing = true
        scope.launch {
            processTextQueue()
        }
        Log.d(TAG, "流式TTS管理器已启动")
    }
    
    /**
     * 停止流式TTS处理
     */
    fun stop() {
        isProcessing = false
        currentSynthesizer?.close()
        currentSynthesizer = null
        currentNlsClient?.shutdown()
        currentNlsClient = null
        textQueue.close()
        scope.cancel()
        Log.d(TAG, "流式TTS管理器已停止")
    }
    
    /**
     * 添加文本块到队列
     */
    suspend fun speakChunk(text: String) {
        if (!isProcessing) {
            Log.w(TAG, "流式TTS未启动，忽略文本块: $text")
            return
        }
        
        if (text.isBlank()) {
            return
        }
        
        try {
            textQueue.send(text)
            Log.d(TAG, "文本块已加入队列: $text")
        } catch (e: Exception) {
            Log.e(TAG, "添加文本块到队列失败", e)
        }
    }
    
    /**
     * 处理文本队列
     */
    private suspend fun processTextQueue() {
        var buffer = StringBuilder()
        
        while (isProcessing) {
            try {
                // 从队列接收文本块（带超时）
                val chunk = withTimeoutOrNull(1000) {
                    textQueue.receive()
                }
                
                if (chunk == null) {
                    // 超时，检查缓冲区是否有内容需要处理
                    if (buffer.isNotEmpty()) {
                        flushBuffer(buffer)
                        buffer.clear()
                    }
                    continue
                }
                
                // 将文本块添加到缓冲区
                buffer.append(chunk)
                
                // 检查是否有标点符号
                var lastPunctuationIndex = -1
                for (i in buffer.indices.reversed()) {
                    if (buffer[i] in punctuationMarks) {
                        lastPunctuationIndex = i
                        break
                    }
                }
                
                // 如果找到标点符号，处理到该位置的内容
                if (lastPunctuationIndex >= 0) {
                    val textToSynthesize = buffer.substring(0, lastPunctuationIndex + 1)
                    buffer.delete(0, lastPunctuationIndex + 1)
                    
                    // 合成并播放
                    synthesizeAndPlay(textToSynthesize)
                }
                
            } catch (e: Exception) {
                if (e is CancellationException) {
                    break
                }
                Log.e(TAG, "处理文本队列异常", e)
            }
        }
        
        // 处理剩余的缓冲区内容
        if (buffer.isNotEmpty()) {
            flushBuffer(buffer)
        }
        
        Log.d(TAG, "文本队列处理结束")
    }
    
    /**
     * 刷新缓冲区（处理剩余文本）
     */
    private suspend fun flushBuffer(buffer: StringBuilder) {
        if (buffer.isNotEmpty()) {
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) {
                synthesizeAndPlay(text)
            }
        }
    }
    
    /**
     * 合成并播放文本
     */
    private suspend fun synthesizeAndPlay(text: String) = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext
        }
        
        try {
            Log.d(TAG, "开始合成TTS: $text")
            
            // 等待当前TTS任务完成（如果存在）
            while (currentSynthesizer != null) {
                delay(100)
            }
            
            // 获取Token
            val token = try {
                val tokenHelper = AliyunTokenHelper(accessKeyId, accessKeySecret)
                tokenHelper.getToken()
            } catch (e: Exception) {
                Log.e(TAG, "获取Token失败", e)
                return@withContext
            }
            
            // 初始化NLS客户端
            currentNlsClient = NlsClient(token)
            
            // 创建输出文件
            val outputFile = File(ttsPath, "streaming_tts_${System.currentTimeMillis()}.wav")
            val parent = outputFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            
            // 创建合成器监听器
            val listener = object : SpeechSynthesizerListener() {
                override fun onComplete(response: SpeechSynthesizerResponse) {
                    Log.d(TAG, "TTS合成完成: ${response.taskId}")
                    scope.launch(Dispatchers.IO) {
                        // 播放音频
                        audioPlayCallback?.invoke(outputFile.absolutePath)
                        // 清理资源
                        currentSynthesizer = null
                        currentNlsClient?.shutdown()
                        currentNlsClient = null
                    }
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
                    scope.launch(Dispatchers.IO) {
                        currentSynthesizer = null
                        currentNlsClient?.shutdown()
                        currentNlsClient = null
                    }
                }
                
                override fun onMetaInfo(response: SpeechSynthesizerResponse) {
                    Log.d(TAG, "TTS MetaInfo: ${response.taskId}")
                }
            }
            
            currentSynthesizer = SpeechSynthesizer(currentNlsClient!!, listener)
            currentSynthesizer?.setAppKey(appKey)
            currentSynthesizer?.setFormat(OutputFormatEnum.WAV)
            currentSynthesizer?.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K)
            currentSynthesizer?.setVoice("siyue")  // 使用思悦音色
            currentSynthesizer?.setText(text)
            
            currentSynthesizer?.start()
            currentSynthesizer?.waitForComplete()
            currentSynthesizer?.close()
            currentSynthesizer = null
            
        } catch (e: Exception) {
            Log.e(TAG, "合成TTS异常", e)
            currentSynthesizer = null
            currentNlsClient?.shutdown()
            currentNlsClient = null
        }
    }
    
    /**
     * 清空队列
     */
    fun clearQueue() {
        while (!textQueue.isEmpty) {
            try {
                textQueue.tryReceive()
            } catch (e: Exception) {
                break
            }
        }
        Log.d(TAG, "文本队列已清空")
    }
}
