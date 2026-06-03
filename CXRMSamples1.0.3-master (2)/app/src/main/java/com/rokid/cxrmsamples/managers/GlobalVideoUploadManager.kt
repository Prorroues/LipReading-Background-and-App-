package com.rokid.cxrmsamples.managers

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
import com.rokid.cxr.client.extend.listeners.MediaFilesUpdateListener
import com.rokid.cxr.client.extend.listeners.SceneStatusUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.network.NetworkModule
import com.rokid.cxrmsamples.network.VideoUploadApi
import com.rokid.cxrmsamples.network.VideoUploadCoordinator
import com.rokid.cxrmsamples.services.assistant.VoiceAssistantManager
import com.rokid.cxrmsamples.utils.AliyunTokenHelper
import com.rokid.cxrmsamples.utils.MediaPathProvider
import com.rokid.cxrmsamples.managers.ErrorReporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 全局视频上传管理器
 * 复用VideoUploadViewModel的逻辑，供语音助手使用
 */
class GlobalVideoUploadManager private constructor(private val context: Context) {
    private val TAG = "GlobalVideoUploadManager"
    
    companion object {
        @Volatile
        private var instance: GlobalVideoUploadManager? = null
        
        fun getInstance(context: Context): GlobalVideoUploadManager {
            return instance ?: synchronized(this) {
                instance ?: GlobalVideoUploadManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val syncPath = MediaPathProvider.getRootPath(context)
    private val ttsPath = "/sdcard/Download/Rokid/lipReading"
    
    private var uploadUrl = "http://88bill99.top:25000"
    private var videoUploadApi: VideoUploadApi = NetworkModule.createVideoUploadApi(uploadUrl)
    
    private var nlsAppKey = ""
    private var nlsAccessKeyId = ""
    private var nlsAccessKeySecret = ""
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    private val _lipReadingResult = MutableStateFlow<String?>(null)
    val lipReadingResult: StateFlow<String?> = _lipReadingResult.asStateFlow()

    private val _lipReadingStatus = MutableStateFlow<String?>(null)
    val lipReadingStatus: StateFlow<String?> = _lipReadingStatus.asStateFlow()
    private var lastRecordingFinishedAt: Long = 0L
    private var lipReadingSessionId: Long = 0L
    
    private var nlsClient: NlsClient? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var mediaPlayer: MediaPlayer? = null
    
    private val uploadQueue = mutableListOf<String>()
    private val uploadedFiles = mutableSetOf<String>()
    private var isUploading = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var listenersRegistered = false
    private var recordEndJob: Job? = null
    
    // 场景状态监听器（仅更新状态，不自动上传）
    private val sceneStatusListener = SceneStatusUpdateListener { sceneStatus ->
        sceneStatus?.isVideoRecordRunning?.let { isRunning ->
            if (!isRunning && _isRecording.value) {
                Log.d(TAG, "录像完成")
                onRecordingFinished()
            }
        }
    }
    
    // 媒体文件更新监听器（仅记录日志，不自动上传）
    private val mediaFilesListener = MediaFilesUpdateListener {
        Log.d(TAG, "检测到新媒体文件（不自动上传）")
        // 不再自动检查并上传
    }
    
    // 同步回调（与VideoUploadViewModel完全一致）
    private val syncCallback = object : SyncStatusCallback {
        override fun onSyncStart() {
            Log.d(TAG, "视频同步开始")
            emitStatus("正在同步视频...", "#FFC107")
        }
        
        override fun onSingleFileSynced(fileName: String?) {
            Log.d(TAG, "视频文件已同步: $fileName")
            fileName?.let {
                val cleanFileName = if (it.contains("/")) File(it).name else it
                if (cleanFileName.lowercase().endsWith(".mp4") || 
                    cleanFileName.lowercase().endsWith(".webm")) {
                    addToUploadQueue(cleanFileName)
                }
            }
        }
        
        override fun onSyncFailed() {
            Log.e(TAG, "视频同步失败")
            emitStatus("同步失败", "#FF0000")
            scheduleScreenRefresh()
        }
        
        override fun onSyncFinished() {
            Log.d(TAG, "视频同步完成")
            emitStatus("同步完成，准备上传...", "#FFC107")
            enqueueRecentSyncedVideos()
        }
    }
    
    fun initialize() {
        if (!listenersRegistered) {
            GlobalCustomViewManager.getInstance().registerSceneStatusListener(sceneStatusListener)
            listenersRegistered = true
        }
        Log.d(TAG, "全局视频上传管理器已初始化（已禁用自动上传）")
    }
    
    fun configure(appKey: String, accessKeyId: String, accessKeySecret: String) {
        this.nlsAppKey = appKey
        this.nlsAccessKeyId = accessKeyId
        this.nlsAccessKeySecret = accessKeySecret
    }
    
    fun setUploadUrl(url: String) {
        this.uploadUrl = url
        this.videoUploadApi = NetworkModule.createVideoUploadApi(url)
    }
    
    fun currentLipReadingSessionId(): Long = lipReadingSessionId

    /**
     * 开始录像（只负责触发，后续自动处理）
     * @return 本次录像会话 ID，失败时返回 -1
     */
    fun startRecording(durationSeconds: Int): Long {
        if (_isRecording.value) {
            Log.w(TAG, "已在录像中")
            return lipReadingSessionId
        }
        
        if (!CxrApi.getInstance().isBluetoothConnected) {
            Log.e(TAG, "蓝牙未连接")
            showGlassText("眼镜未连接", "#FF0000")
            return -1L
        }
        
        lipReadingSessionId = System.currentTimeMillis()
        Log.d(TAG, "开始录像: ${durationSeconds}秒, session=$lipReadingSessionId")
        
        emitStatus("正在录像 ${durationSeconds}秒", "#FF0000")
        
        // 设置录像参数（与VideoUploadViewModel一致）
        CxrApi.getInstance().setVideoParams(durationSeconds, 30, 1280, 720, 1)
        
        // 开始录像
        CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.VIDEO_RECORD, true, null)
        _isRecording.value = true

        // 兜底：按时长结束后触发后续流程
        recordEndJob?.cancel()
        recordEndJob = scope.launch {
            delay((durationSeconds + 1) * 1000L)
            if (_isRecording.value) {
                Log.d(TAG, "录像超时触发结束")
                onRecordingFinished()
            }
        }
        
        // 后续流程由sceneStatusListener自动触发
        return lipReadingSessionId
    }

    private fun onRecordingFinished() {
        recordEndJob?.cancel()
        recordEndJob = null
        _isRecording.value = false
        lastRecordingFinishedAt = System.currentTimeMillis()
        emitStatus("录像完成，等待同步...", "#FFC107")
        
        // 不再主动同步，改为监听全局同步队列
        scope.launch {
            waitForVideoInQueue()
        }
    }
    
    /**
     * 等待队列中的最新视频（轮询+回调结合）
     * WiFi连接时，视频会自动同步并由GlobalWifiManager添加到队列
     */
    private suspend fun waitForVideoInQueue() {
        try {
            emitStatus("等待视频同步...", "#FFC107", toChat = false)
            
            // 方案1：先尝试通过Flow监听队列更新（超时5秒）
            var videoPath: String? = null
            try {
                videoPath = withTimeout(5000) {
                    // 监听队列更新，等待第一个符合条件的视频
                    GlobalVideoSyncQueue.latestVideo
                        .filter { it != null }
                        .filter { path ->
                            // 检查是否是录制后的视频（通过文件修改时间）
                            try {
                                val file = File(path ?: return@filter false)
                                if (file.exists() && file.lastModified() >= lastRecordingFinishedAt - 2000) {
                                    // 检查是否已上传
                                    val fileName = file.name
                                    synchronized(uploadedFiles) {
                                        !uploadedFiles.contains(fileName)
                                    }
                                } else {
                                    false
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "检查文件时间失败", e)
                                false
                            }
                        }
                        .take(1)
                        .first()
                }
            } catch (e: TimeoutCancellationException) {
                Log.d(TAG, "Flow监听超时，使用轮询方式")
            } catch (e: NoSuchElementException) {
                Log.d(TAG, "Flow未找到符合条件的视频，使用轮询方式")
            }
            
            // 方案2：如果Flow超时，使用轮询方式（最多10秒）
            if (videoPath == null) {
                val maxWaitTime = 10000L // 最多等待10秒
                val pollInterval = 500L // 每0.5秒检查一次
                val startTime = System.currentTimeMillis()
                var found = false
                
                while (!found && System.currentTimeMillis() - startTime < maxWaitTime) {
                    // 从队列获取最新视频
                    val candidatePath = GlobalVideoSyncQueue.getLatestVideoAfter(lastRecordingFinishedAt - 2000)
                    
                    if (candidatePath != null) {
                        // 检查是否已上传
                        val fileName = File(candidatePath).name
                        val notUploaded = synchronized(uploadedFiles) {
                            !uploadedFiles.contains(fileName)
                        }
                        
                        if (notUploaded) {
                            Log.d(TAG, "从队列获取到视频: $candidatePath")
                            videoPath = candidatePath
                            found = true
                        }
                    }
                    
                    if (!found) {
                        delay(pollInterval)
                    }
                }
            }

            // 方案3：最后使用队列中最新的视频（不校验时间），确保优先取最新路径
            if (videoPath == null) {
                val latestPath = GlobalVideoSyncQueue.getLatestVideo()
                if (latestPath != null) {
                    val fileName = File(latestPath).name
                    val notUploaded = synchronized(uploadedFiles) {
                        !uploadedFiles.contains(fileName)
                    }
                    if (notUploaded) {
                        Log.d(TAG, "使用队列最新视频: $latestPath")
                        videoPath = latestPath
                    }
                }
            }
            
            if (videoPath != null && File(videoPath).exists()) {
                Log.d(TAG, "找到视频，开始上传: $videoPath")
                // 使用完整路径上传
                uploadVideoFileByPath(videoPath)
            } else {
                Log.e(TAG, "未在队列中找到视频（超时）")
                ErrorReporter.report(
                    source = TAG,
                    stage = "waitForVideoInQueue",
                    message = "未在队列中找到视频（超时）",
                    detail = "queueSize=${GlobalVideoSyncQueue.size()}, lastRecordAt=$lastRecordingFinishedAt"
                )
                emitStatus("未找到同步视频，请检查WiFi连接", "#FF0000")
                scheduleScreenRefresh()
                delay(3000)
                withContext(Dispatchers.Main) {
                    GlobalCustomViewManager.getInstance().closeView()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "等待视频队列异常", e)
            ErrorReporter.report(
                source = TAG,
                stage = "waitForVideoInQueue",
                message = "等待视频队列异常",
                detail = e.message
            )
            emitStatus("等待视频失败: ${e.message}", "#FF0000")
            scheduleScreenRefresh()
            delay(3000)
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().closeView()
            }
        }
    }
    
    /**
     * 手动检查并上传视频（已废弃，保留以防其他地方调用）
     * 唇语识别不再使用此方法
     */
    @Deprecated("唇语识别已改为从队列获取视频")
    fun checkAndUploadVideos() {
        Log.w(TAG, "checkAndUploadVideos已被废弃，唇语识别应从队列获取视频")
    }
    
    /**
     * 主动同步视频（已废弃，不再使用）
     * 现在由GlobalWifiManager在WiFi连接时自动同步
     */
    @Deprecated("不再主动同步，改为监听队列")
    private fun startVideoSync() {
        Log.w(TAG, "startVideoSync已被废弃，不再主动同步")
    }
    
    private fun addToUploadQueue(fileName: String) {
        val lowerFileName = fileName.lowercase()
        val isVideoFile = lowerFileName.endsWith(".mp4") ||
            lowerFileName.endsWith(".mov") ||
            lowerFileName.endsWith(".avi") ||
            lowerFileName.endsWith(".mkv") ||
            lowerFileName.endsWith(".webm")
        if (!isVideoFile) {
            Log.w(TAG, "跳过非视频文件: $fileName")
            return
        }
        synchronized(uploadQueue) {
            if (!uploadQueue.contains(fileName)) {
                uploadQueue.add(fileName)
                Log.d(TAG, "添加到上传队列: $fileName, 队列长度: ${uploadQueue.size}")
            }
        }
        processUploadQueue()
    }
    
    private fun processUploadQueue() {
        if (isUploading) {
            Log.d(TAG, "正在上传中，跳过")
            return
        }
        
        scope.launch {
            isUploading = true
            
            while (uploadQueue.isNotEmpty()) {
                val fileName = synchronized(uploadQueue) {
                    if (uploadQueue.isNotEmpty()) uploadQueue.removeAt(0) else null
                }
                
                fileName?.let {
                    uploadVideoFile(it)
                }
            }
            
            isUploading = false
        }
    }
    
    /**
     * 通过完整路径上传视频（用于从队列获取的视频）
     */
    private suspend fun uploadVideoFileByPath(fullPath: String) = withContext(Dispatchers.IO) {
        val file = File(fullPath)
        val fileName = file.name
        
        if (!file.exists()) {
            Log.e(TAG, "文件不存在: $fullPath")
            ErrorReporter.report(
                source = TAG,
                stage = "uploadVideoFileByPath",
                message = "文件不存在，上传失败",
                detail = "path=$fullPath"
            )
            emitStatus("文件不存在，上传失败", "#FF0000")
            scheduleScreenRefresh()
            return@withContext
        }
        var success = false
        var retryCount = 0
        val maxRetries = 3
        
        while (!success && retryCount < maxRetries) {
            try {
                Log.d(TAG, "尝试上传 (${retryCount + 1}/$maxRetries): $fileName")
                emitStatus("正在上传... (${retryCount + 1}/$maxRetries)", "#FFC107", toChat = false)
                
                val requestFile = file.asRequestBody("video/mp4".toMediaType())
                val videoPart = MultipartBody.Part.createFormData("file", file.name, requestFile)

                val resolve = VideoUploadCoordinator.uploadAndResolve(
                    videoUploadApi,
                    uploadUrl,
                    videoPart
                ) { phase -> emitStatus(phase, "#FFC107", toChat = false) }

                if (resolve.success && !resolve.responseBody.isNullOrBlank()) {
                    success = true
                    synchronized(uploadedFiles) {
                        uploadedFiles.add(fileName)
                    }
                    parseAndHandleResponse(resolve.responseBody!!)
                } else {
                    Log.e(TAG, "上传失败: ${resolve.error}")
                    ErrorReporter.report(
                        source = TAG,
                        stage = "uploadVideoFileByPath",
                        message = "上传失败",
                        detail = resolve.error ?: "unknown"
                    )
                    retryCount++
                    if (retryCount < maxRetries) {
                        emitStatus("上传失败，2秒后重试...", "#FFA000", toChat = false)
                        delay(2000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传异常", e)
                ErrorReporter.report(
                    source = TAG,
                    stage = "uploadVideoFileByPath",
                    message = "上传异常",
                    detail = e.message
                )
                retryCount++
                if (retryCount < maxRetries) {
                    emitStatus("上传异常，2秒后重试...", "#FFA000", toChat = false)
                    delay(2000)
                }
            }
        }
        
        if (!success) {
            ErrorReporter.report(
                source = TAG,
                stage = "uploadVideoFileByPath",
                message = "上传失败（已重试）",
                detail = "file=$fileName"
            )
            emitStatus("上传失败（已重试${maxRetries}次）", "#FF0000")
            scheduleScreenRefresh()
            delay(3000)
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().closeView()
            }
        }
    }
    
    /**
     * 解析并处理服务器响应（优先解析message字段，其次src_recognition_results）
     */
    private suspend fun parseAndHandleResponse(responseBody: String) {
        try {
            val json = JSONObject(responseBody)
            
            // 优先检查message字段
            var result: String? = null
            val message = json.optString("message", "")
            
            if (message.isNotBlank()) {
                result = message
                Log.d(TAG, "识别结果（来自message字段）: $result")
            } else {
                // 如果没有message，再检查src_recognition_results字段
                val recog = json.optString("src_recognition_results", "")
                if (recog.isNotBlank()) {
                    result = recog
                    Log.d(TAG, "识别结果（来自src_recognition_results字段）: $result")
                }
            }
            
            if (result != null && result.isNotBlank()) {
                _lipReadingResult.value = result
                
                // 显示结果并播报
                emitStatus("识别结果: $result", "#00FFFF")
                synthesizeAndPlay(result)
            } else {
                Log.w(TAG, "响应中未找到message或src_recognition_results字段: $responseBody")
                ErrorReporter.report(
                    source = TAG,
                    stage = "parseAndHandleResponse",
                    message = "响应中未找到message或src_recognition_results字段",
                    detail = responseBody
                )
                emitStatus("服务器响应格式异常", "#FFA000")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析响应失败", e)
            ErrorReporter.report(
                source = TAG,
                stage = "parseAndHandleResponse",
                message = "解析响应失败",
                detail = e.message
            )
            emitStatus("解析服务器响应失败: ${e.message}", "#FF0000")
        }
    }
    
    private suspend fun uploadVideoFile(fileName: String) = withContext(Dispatchers.IO) {
        // 完全复用VideoUploadViewModel的uploadVideoFileInternal逻辑
        emitStatus("正在上传视频...", "#FFC107")
        
        val file = File(syncPath, fileName)
        if (!file.exists()) {
            Log.e(TAG, "文件不存在: ${file.absolutePath}")
            emitStatus("文件不存在，上传失败", "#FF0000")
            scheduleScreenRefresh()
            return@withContext
        }
        var success = false
        var retryCount = 0
        val maxRetries = 3
        
        while (!success && retryCount < maxRetries) {
            try {
                Log.d(TAG, "尝试上传 (${retryCount + 1}/$maxRetries): $fileName")
                emitStatus("正在上传... (${retryCount + 1}/$maxRetries)", "#FFC107", toChat = false)
                
                val requestFile = file.asRequestBody("video/mp4".toMediaType())
                val videoPart = MultipartBody.Part.createFormData("file", file.name, requestFile)

                val resolve = VideoUploadCoordinator.uploadAndResolve(
                    videoUploadApi,
                    uploadUrl,
                    videoPart
                ) { phase -> emitStatus(phase, "#FFC107", toChat = false) }

                if (resolve.success && !resolve.responseBody.isNullOrBlank()) {
                    success = true
                    synchronized(uploadedFiles) {
                        uploadedFiles.add(fileName)
                    }
                    parseAndHandleResponse(resolve.responseBody!!)
                } else {
                    Log.e(TAG, "上传失败: ${resolve.error}")
                    retryCount++
                    if (retryCount < maxRetries) {
                        emitStatus("上传失败，2秒后重试...", "#FFA000", toChat = false)
                        delay(2000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传异常", e)
                retryCount++
                if (retryCount < maxRetries) {
                    emitStatus("上传异常，2秒后重试...", "#FFA000", toChat = false)
                    delay(2000)
                }
            }
        }
        
        if (!success) {
            emitStatus("上传失败（已重试${maxRetries}次）", "#FF0000")
            scheduleScreenRefresh()
            delay(3000)
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().closeView()
            }
        }
    }

    private fun enqueueRecentSyncedVideos() {
        val dir = File(syncPath)
        if (!dir.exists()) return
        val cutoff = if (lastRecordingFinishedAt > 0L) lastRecordingFinishedAt - 120_000L else 0L
        val files = listVideoFiles(dir)
            .filter { it.lastModified() >= cutoff }
            .sortedByDescending { it.lastModified() }

        val target = files.firstOrNull { file ->
            synchronized(uploadedFiles) { !uploadedFiles.contains(file.name) }
        } ?: files.firstOrNull()

        if (target != null) {
            addToUploadQueue(target.name)
        } else {
            emitStatus("未找到可上传的视频，请检查同步路径", "#FF0000")
            scheduleScreenRefresh()
        }
    }

    private fun listVideoFiles(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        val result = mutableListOf<File>()
        val files = dir.listFiles().orEmpty()
        for (file in files) {
            if (file.isDirectory) {
                result.addAll(listVideoFiles(file))
            } else {
                val lower = file.name.lowercase()
                if (lower.endsWith(".mp4") ||
                    lower.endsWith(".mov") ||
                    lower.endsWith(".avi") ||
                    lower.endsWith(".mkv") ||
                    lower.endsWith(".webm")
                ) {
                    result.add(file)
                }
            }
        }
        return result
    }
    
    private fun showGlassText(text: String, color: String = "#00FFFF") {
        CoroutineScope(Dispatchers.Main).launch {
            GlobalCustomViewManager.getInstance().showTextView(text, textColor = color)
        }
    }

    private fun emitStatus(text: String, color: String = "#00FFFF", toChat: Boolean = true) {
        _lipReadingStatus.value = text
        // 中央显示更新（对话界面）
        GlobalCustomViewManager.getInstance().updateDialogText(text)
        if (!GlobalCustomViewManager.getInstance().isCustomViewOpen.value) {
            GlobalCustomViewManager.getInstance().showDialogView(text)
        }
        if (toChat) {
            VoiceAssistantManager.getInstance(context).addExternalMessage(text)
        }
    }

    private fun scheduleScreenRefresh() {
        scope.launch {
            delay(5000)
            GlobalCustomViewManager.getInstance().refreshScreen()
        }
    }
    
    private suspend fun synthesizeAndPlay(text: String) = withContext(Dispatchers.IO) {
        // 完全复用VideoUploadViewModel的TTS逻辑
        try {
            val token = AliyunTokenHelper(nlsAccessKeyId, nlsAccessKeySecret).getToken()
            nlsClient = NlsClient(token)
            
            val outputFile = File(ttsPath, "lip_${System.currentTimeMillis()}.wav")
            outputFile.parentFile?.mkdirs()
            
            val listener = object : SpeechSynthesizerListener() {
                override fun onComplete(response: SpeechSynthesizerResponse) {
                    playAudio(outputFile.absolutePath)
                }
                
                override fun onMessage(message: ByteBuffer) {
                    val bytes = ByteArray(message.remaining())
                    message.get(bytes)
                    FileOutputStream(outputFile, true).use { it.write(bytes) }
                }
                
                override fun onFail(response: SpeechSynthesizerResponse) {
                    Log.e(TAG, "TTS失败: ${response.statusText}")
                }
                
                override fun onMetaInfo(response: SpeechSynthesizerResponse) {}
            }
            
            synthesizer = SpeechSynthesizer(nlsClient, listener)
            synthesizer?.setAppKey(nlsAppKey)
            synthesizer?.setFormat(OutputFormatEnum.WAV)
            synthesizer?.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K)
            synthesizer?.setVoice("siyue")
            synthesizer?.setText(text)
            synthesizer?.start()
            synthesizer?.waitForComplete()
            synthesizer?.close()
            
            delay(10000)
            withContext(Dispatchers.Main) {
                GlobalCustomViewManager.getInstance().closeView()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS异常", e)
        }
    }
    
    private fun playAudio(path: String) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(path)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { it.release(); mediaPlayer = null }
            prepareAsync()
        }
    }
    
    fun cleanup() {
        CxrApi.getInstance().setMediaFilesUpdateListener(null)
        synthesizer?.close()
        mediaPlayer?.release()
    }
}
