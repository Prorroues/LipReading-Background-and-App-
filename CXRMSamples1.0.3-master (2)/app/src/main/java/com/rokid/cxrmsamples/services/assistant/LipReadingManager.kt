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
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager
import com.rokid.cxrmsamples.managers.GlobalVideoSyncQueue
import com.rokid.cxrmsamples.managers.ErrorReporter
import com.rokid.cxrmsamples.network.NetworkModule
import com.rokid.cxrmsamples.network.VideoUploadApi
import com.rokid.cxrmsamples.network.VideoUploadCoordinator
import com.rokid.cxrmsamples.utils.AliyunTokenHelper
import com.rokid.cxrmsamples.utils.MediaPathProvider
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 唇语识别管理器
 */
class LipReadingManager(private val context: Context) {
    private val TAG = "LipReadingManager"
    
    private val syncPath = MediaPathProvider.getRootPath()
    private val ttsPath = "/sdcard/Download/Rokid/lipReading"
    private var uploadUrl = "http://88bill99.top:25000"
    private var videoUploadApi: VideoUploadApi = NetworkModule.createVideoUploadApi(uploadUrl)
    
    private var nlsClient: NlsClient? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var mediaPlayer: MediaPlayer? = null
    
    private var nlsAppKey: String = ""
    private var nlsAccessKeyId: String = ""
    private var nlsAccessKeySecret: String = ""
    
    fun configure(appKey: String, accessKeyId: String, accessKeySecret: String) {
        this.nlsAppKey = appKey
        this.nlsAccessKeyId = accessKeyId
        this.nlsAccessKeySecret = accessKeySecret
        Log.d(TAG, "唇语识别管理器配置完成")
    }
    
    fun setUploadUrl(url: String) {
        this.uploadUrl = url
        this.videoUploadApi = NetworkModule.createVideoUploadApi(url)
        Log.d(TAG, "上传地址设置为: $url")
    }
    
    suspend fun performLipReading(
        durationSeconds: Int,
        onStatusUpdate: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始唇语识别流程，录像时长: ${durationSeconds}秒")
            
            // 1. 检查蓝牙连接
            if (!CxrApi.getInstance().isBluetoothConnected) {
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().showTextView(
                        "眼镜未连接，无法录像",
                        textColor = "#FF0000"
                    )
                }
                ErrorReporter.report(
                    source = TAG,
                    stage = "performLipReading",
                    message = "眼镜未连接，无法录像"
                )
                onError("眼镜未连接，无法录像")
                delay(2000)
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().closeView()
                }
                return@withContext
            }
            
            // 2. 开始录像 - 眼镜显示红色"录像中"
            onStatusUpdate("录像中...")
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().showTextView(
                    "正在录像 ${durationSeconds}秒",
                    textColor = "#FF0000",  // 红色表示录像中
                    backgroundColor = "#FF000000"
                )
            }
            
            startRecording(durationSeconds)
            Log.d(TAG, "⏺️ 录像开始")
            
            // 等待录像完成
            delay((durationSeconds * 1000).toLong() + 500)
            
            // 3. 录像结束 - 眼镜提示
            Log.d(TAG, "⏹️ 录像结束")
            val recordEndAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().showTextView("录像结束，正在处理...")
            }
            onStatusUpdate("录像结束，正在同步...")
            delay(1000)
            
            // 4. 同步视频 - 眼镜提示
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().showTextView("正在同步视频到手机...")
            }
            onStatusUpdate("正在同步视频...")

            // 等待系统同步后的队列更新
            val videoFile = waitForSyncedVideo(recordEndAt)
            
            if (videoFile == null) {
                // 同步失败 - 最终失败
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().showTextView(
                        text = "视频同步失败",
                        textColor = "#FF0000"
                    )
                }
                ErrorReporter.report(
                    source = TAG,
                    stage = "performLipReading",
                    message = "未获取到同步视频",
                    detail = "recordEndAt=$recordEndAt"
                )
                onError("视频同步失败")
                delay(3000)  // 显示3秒
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().closeView()
                }
                return@withContext
            }
            
            Log.d(TAG, "✓ 视频同步成功: ${videoFile.name}")
            
            // 5. 上传服务器（带重试，持续显示上传状态）
            onStatusUpdate("正在上传视频...")
            
            val uploadResult = uploadVideoFile(videoFile)
            
            if (uploadResult == null) {
                // 上传最终失败（已经在uploadVideoFile中显示了）
                ErrorReporter.report(
                    source = TAG,
                    stage = "performLipReading",
                    message = "视频上传失败",
                    detail = videoFile.name
                )
                onError("视频上传失败")
                delay(3000)  // 显示3秒
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().closeView()
                }
                return@withContext
            }
            
            Log.d(TAG, "✓ 上传成功")
            
            // 6. 等待服务器响应 - 眼镜提示（黄色，持续显示）
            Log.d(TAG, "✓ 上传完成，等待服务器识别")
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().showTextView(
                    text = "等待服务器识别中...",
                    textColor = "#FFC107"  // 黄色表示等待
                )
            }
            onStatusUpdate("等待服务器识别中...")
            
            val recognitionResult = uploadResult.recognitionResult
            if (recognitionResult.isNullOrEmpty()) {
                // 服务器未返回结果 - 最终失败
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().showTextView(
                        text = "服务器未返回识别结果",
                        textColor = "#FF0000"
                    )
                }
                ErrorReporter.report(
                    source = TAG,
                    stage = "performLipReading",
                    message = "服务器未返回识别结果",
                    detail = uploadResult.videoUrl ?: ""
                )
                onError("服务器未返回识别结果")
                delay(3000)
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().closeView()
                }
                return@withContext
            }
            
            Log.d(TAG, "✓ 识别结果: $recognitionResult")
            
            // 7. 显示识别结果到眼镜（青色，醒目）
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().showTextView(
                    text = recognitionResult,
                    textColor = "#00FFFF"  // 青色表示结果
                )
            }
            onStatusUpdate("识别完成: $recognitionResult")
            
            // 8. TTS播报结果
            speakTextAndDisplay(recognitionResult)
            
            onComplete(recognitionResult)
            
        } catch (e: Exception) {
            Log.e(TAG, "唇语识别流程异常", e)
            ErrorReporter.report(
                source = TAG,
                stage = "performLipReading",
                message = "唇语识别流程异常",
                detail = e.message
            )
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().showTextView("识别失败: ${e.message}")
            }
            onError("唇语识别失败: ${e.message}")
            delay(2000)
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().closeView()
            }
        }
    }
    
    private fun startRecording(durationSeconds: Int) {
        CxrApi.getInstance().setVideoParams(durationSeconds, 30, 1280, 720, 1)
        CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.VIDEO_RECORD, true, null)
        Log.d(TAG, "开始录像: ${durationSeconds}秒")
    }
    
    private suspend fun waitForSyncedVideo(recordEndAt: Long): File? {
        return try {
            val maxWaitTime = 15000L
            val pollInterval = 500L
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                val candidatePath = GlobalVideoSyncQueue.getLatestVideoAfter(recordEndAt - 2000)
                if (candidatePath != null) {
                    val file = File(candidatePath)
                    if (file.exists()) {
                        return file
                    }
                }
                delay(pollInterval)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "等待同步视频异常", e)
            ErrorReporter.report(
                source = TAG,
                stage = "waitForSyncedVideo",
                message = "等待同步视频异常",
                detail = e.message
            )
            null
        }
    }
    
    private suspend fun uploadVideoFile(file: File): UploadResult? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                Log.e(TAG, "文件不存在: ${file.absolutePath}")
                return@withContext null
            }
            Log.d(TAG, "上传视频: ${file.name}, 大小: ${file.length() / 1024}KB")
            
            // 重试逻辑（与VideoUploadViewModel一致）
            var success = false
            var retryCount = 0
            val maxRetries = 3
            var lastError: String? = null
            var uploadResult: UploadResult? = null
            
            while (!success && retryCount < maxRetries) {
                try {
                    Log.d(TAG, "尝试上传 (${retryCount + 1}/$maxRetries): ${file.name}")
                    
                    // 眼镜端显示上传进度
                    withContext(Dispatchers.Main) {
                        GlobalCustomViewManager.getInstance().showTextView(
                            text = "正在上传... (${retryCount + 1}/$maxRetries)",
                            textColor = "#FFC107"
                        )
                    }
                    
                    val requestFile = file.asRequestBody("video/mp4".toMediaType())
                    val videoPart = MultipartBody.Part.createFormData("file", file.name, requestFile)

                    val resolve = VideoUploadCoordinator.uploadAndResolve(
                        videoUploadApi,
                        uploadUrl,
                        videoPart
                    ) { phase ->
                        CoroutineScope(Dispatchers.Main).launch {
                            GlobalCustomViewManager.getInstance().showTextView(
                                text = phase,
                                textColor = "#FFC107"
                            )
                        }
                    }

                    if (resolve.success && !resolve.responseBody.isNullOrBlank()) {
                        Log.d(TAG, "视频上传成功: ${file.name}")
                        success = true
                        val responseBody = resolve.responseBody!!
                        Log.d(TAG, "服务器返回: $responseBody")
                        val json = JSONObject(responseBody)
                        val recognitionResult = json.optString("src_recognition_results", "")
                        val videoUrl = json.optString("video", "")
                        uploadResult = UploadResult(recognitionResult, videoUrl)
                    } else {
                        lastError = resolve.error ?: "上传或识别失败"
                        Log.e(TAG, "上传失败: $lastError")
                        
                        retryCount++
                        if (retryCount < maxRetries) {
                            Log.d(TAG, "2秒后重试...")
                            // 眼镜端显示重试提示
                            withContext(Dispatchers.Main) {
                                GlobalCustomViewManager.getInstance().showTextView(
                                    text = "上传失败，2秒后重试...",
                                    textColor = "#FFA000"
                                )
                            }
                            delay(2000)
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    lastError = "连接超时: ${e.message}"
                    Log.e(TAG, lastError, e)
                    retryCount++
                    if (retryCount < maxRetries) {
                        withContext(Dispatchers.Main) {
                            GlobalCustomViewManager.getInstance().showTextView(
                                text = "超时，2秒后重试...",
                                textColor = "#FFA000"
                            )
                        }
                        delay(2000)
                    }
                } catch (e: java.net.UnknownHostException) {
                    lastError = "无法连接到服务器: ${e.message}"
                    Log.e(TAG, lastError, e)
                    retryCount = maxRetries // 网络错误不重试
                } catch (e: java.net.ConnectException) {
                    lastError = "连接被拒绝: ${e.message}"
                    Log.e(TAG, lastError, e)
                    retryCount = maxRetries // 连接错误不重试
                } catch (e: Exception) {
                    lastError = "上传异常: ${e.javaClass.simpleName} - ${e.message}"
                    Log.e(TAG, lastError, e)
                    retryCount++
                    if (retryCount < maxRetries) {
                        withContext(Dispatchers.Main) {
                            GlobalCustomViewManager.getInstance().showTextView(
                                text = "上传异常，2秒后重试...",
                                textColor = "#FFA000"
                            )
                        }
                        delay(2000)
                    }
                }
            }
            
            if (!success) {
                val errorMessage = "上传最终失败: 已重试 $maxRetries 次\n最后错误: $lastError"
                Log.e(TAG, errorMessage)
                
                // 只有最终失败才显示失败信息
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().showTextView(
                        text = "上传失败（已重试${maxRetries}次）",
                        textColor = "#FF0000"
                    )
                }
            }
            
            return@withContext uploadResult
            
        } catch (e: Exception) {
            val errorMsg = "上传过程异常: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, errorMsg, e)
            return@withContext null
        }
    }
    
    private suspend fun speakTextAndDisplay(text: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔊 开始TTS播报: $text")
            
            // 结果已经在上面显示了，这里只需要TTS
            
            val token = try {
                val tokenHelper = AliyunTokenHelper(nlsAccessKeyId, nlsAccessKeySecret)
                tokenHelper.getToken()
            } catch (e: Exception) {
                Log.e(TAG, "获取Token失败", e)
                return@withContext
            }
            
            nlsClient = NlsClient(token)
            
            val outputFile = File(ttsPath, "lip_reading_${System.currentTimeMillis()}.wav")
            val parent = outputFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            
            // 创建合成监听器（与VideoUploadViewModel完全一致）
            val listener = object : SpeechSynthesizerListener() {
                private var firstRecvBinary = true
                
                override fun onComplete(response: SpeechSynthesizerResponse) {
                    Log.d(TAG, "语音合成完成: ${response.taskId}")
                    // 自动播放
                    playAudio(outputFile.absolutePath)
                }
                
                override fun onMessage(message: ByteBuffer) {
                    try {
                        if (firstRecvBinary) {
                            firstRecvBinary = false
                        }
                        val bytesArray = ByteArray(message.remaining())
                        message.get(bytesArray, 0, bytesArray.size)
                        FileOutputStream(outputFile, true).use { fos ->
                            fos.write(bytesArray)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "写入合成数据失败", e)
                    }
                }
                
                override fun onFail(response: SpeechSynthesizerResponse) {
                    Log.e(TAG, "语音合成失败: ${response.statusText}")
                }
                
                override fun onMetaInfo(response: SpeechSynthesizerResponse) {
                    Log.d(TAG, "TTS MetaInfo: ${response.taskId}")
                }
            }
            
            synthesizer = SpeechSynthesizer(nlsClient, listener)
            synthesizer?.setAppKey(nlsAppKey)
            synthesizer?.setFormat(OutputFormatEnum.WAV)
            synthesizer?.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K)
            synthesizer?.setVoice("siyue")
            synthesizer?.setText(text)
            
            Log.d(TAG, "启动语音合成: $text")
            synthesizer?.start()
            synthesizer?.waitForComplete()
            
            synthesizer?.close()
            nlsClient?.shutdown()
            synthesizer = null
            nlsClient = null
            
            Log.d(TAG, "✓ TTS合成完成")
            
            // 结果在眼镜端显示10秒后关闭
            delay(10000)
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().closeView()
                Log.d(TAG, "📺 关闭眼镜显示")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "语音合成异常", e)
        }
    }
    
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
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    Log.d(TAG, "音频播放完成")
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "音频播放失败: what=$what")
                    mp.release()
                    mediaPlayer = null
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放音频异常", e)
        }
    }
    
    fun cleanup() {
        synthesizer?.close()
        synthesizer = null
        mediaPlayer?.release()
        mediaPlayer = null
        nlsClient?.shutdown()
        nlsClient = null
    }
    
    data class UploadResult(
        val recognitionResult: String?,
        val videoUrl: String?
    )
}
