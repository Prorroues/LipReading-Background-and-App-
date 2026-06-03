package com.rokid.cxrmsamples.activities.videoUpload

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.util.Size
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.nls.client.protocol.NlsClient
import com.alibaba.nls.client.protocol.OutputFormatEnum
import com.alibaba.nls.client.protocol.SampleRateEnum
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse
import com.rokid.cxrmsamples.services.assistant.VoiceAssistantManager
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager
import com.rokid.cxrmsamples.managers.ErrorReporter
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.UnsyncNumResultCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.listeners.MediaFilesUpdateListener
import com.rokid.cxr.client.extend.listeners.SceneStatusUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.managers.GlobalWifiManager
import com.rokid.cxrmsamples.managers.GlobalVideoSyncQueue
import com.rokid.cxrmsamples.network.NetworkModule
import com.rokid.cxrmsamples.network.VideoUploadApi
import com.rokid.cxrmsamples.network.VideoUploadCoordinator
import com.rokid.cxrmsamples.utils.AliyunTokenHelper
import com.rokid.cxrmsamples.utils.MediaPathProvider
import com.rokid.cxrmsamples.utils.UploadUrlHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.json.JSONObject
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED
}

enum class UploadStatus {
    IDLE,
    UPLOADING,
    WAITING_RESPONSE,
    SUCCESS,
    FAILED
}

class VideoUploadViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "VideoUploadViewModel"
    private val appContext: Context = application.applicationContext
    private val autoUploadMaxAttempts = 3
    private val autoUploadFindFileRetries = 15

    // 视频分辨率选项
    val videoSize: Array<Size> = arrayOf(
        Size(1920, 1080),
        Size(4032, 3024),
        Size(4000, 3000),
        Size(4032, 2268),
        Size(3264, 2448),
        Size(3200, 2400),
        Size(2268, 3024),
        Size(2876, 2156),
        Size(2688, 2016),
        Size(2582, 1936),
        Size(2400, 1800),
        Size(1800, 2400),
        Size(2560, 1440),
        Size(2400, 1350),
        Size(2048, 1536),
        Size(2016, 1512),
        Size(1600, 1200),
        Size(1440, 1080),
        Size(1280, 720),
        Size(720, 1280),
        Size(1024, 768),
        Size(800, 600),
        Size(648, 648),
        Size(854, 480),
        Size(800, 480),
        Size(640, 480),
        Size(480, 640),
        Size(352, 288),
        Size(320, 240),
        Size(320, 180),
        Size(176, 144)
    )

    private val _selectedVideoSize = MutableStateFlow(Size(1280, 720))
    val selectedVideoSize = _selectedVideoSize.asStateFlow()

    private val _duration = MutableStateFlow(10)
    val duration = _duration.asStateFlow()

    private val _durationUnit = MutableStateFlow(DurationUnit.SECONDS)
    val durationUnit = _durationUnit.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _wifiConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val wifiConnectionStatus = _wifiConnectionStatus.asStateFlow()

    private val _uploadStatus = MutableStateFlow(UploadStatus.IDLE)
    val uploadStatus = _uploadStatus.asStateFlow()

    private val _currentUploadingFile = MutableStateFlow<String?>(null)
    val currentUploadingFile = _currentUploadingFile.asStateFlow()

    private val _videoNumber = MutableStateFlow(0)
    val videoNumber = _videoNumber.asStateFlow()

    private val _uploadUrl = MutableStateFlow("http://88bill99.top:25000")
    val uploadUrl = _uploadUrl.asStateFlow()

    private val _serverResponseMessage = MutableStateFlow<String?>(null)
    val serverResponseMessage = _serverResponseMessage.asStateFlow()

    // 唇语识别结果 & 视频预览地址
    private val _lipReadingResult = MutableStateFlow<String?>(null)
    val lipReadingResult = _lipReadingResult.asStateFlow()

    private val _lastUploadedVideoUrl = MutableStateFlow<String?>(null)
    val lastUploadedVideoUrl = _lastUploadedVideoUrl.asStateFlow()
    
    // 本地视频文件路径（用于预览播放）
    private val _lastUploadedVideoPath = MutableStateFlow<String?>(null)
    val lastUploadedVideoPath = _lastUploadedVideoPath.asStateFlow()

    private val _localMediaPath = MutableStateFlow(MediaPathProvider.getRootPath(appContext))
    val localMediaPath = _localMediaPath.asStateFlow()

    private val _resultExtractField = MutableStateFlow("src_recognition_results")
    val resultExtractField = _resultExtractField.asStateFlow()

    private val _isAutoUploadEnabled = MutableStateFlow(true)
    val isAutoUploadEnabled = _isAutoUploadEnabled.asStateFlow()
    
    // TTS语音合成相关状态
    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing = _isSynthesizing.asStateFlow()
    
    private val _lastTtsFilePath = MutableStateFlow<String?>(null)
    val lastTtsFilePath = _lastTtsFilePath.asStateFlow()
    
    private val _ttsStatusMessage = MutableStateFlow<String?>(null)
    val ttsStatusMessage = _ttsStatusMessage.asStateFlow()

    private var uploadJob: Job? = null
    private var autoUploadJob: Job? = null
    private var videoUploadApi: VideoUploadApi = NetworkModule.videoUploadApi
    private val uploadQueue = mutableListOf<String>()
    private val uploadedFileKeys = mutableSetOf<String>()
    private var isUploading = false
    private var recordStartAtMs: Long = 0L
    private var lastAutoUploadTriggerAtMs: Long = 0L
    /** 录像结束后的自动上传会话，避免 WiFi 同步回调重复入队导致二次上传失败 */
    private var autoUploadSessionActive = false
    private var currentAutoUploadPath: String? = null
    
    // TTS合成器和播放器
    private var nlsClient: NlsClient? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var mediaPlayer: MediaPlayer? = null
    private val ttsRecordPath = "/sdcard/Download/Rokid/lipReading"
    
    // 从VoiceService配置读取AppKey（复用配置）
    private val sharedPreferences = appContext.getSharedPreferences("voice_service_config", Context.MODE_PRIVATE)
    private val aiPrefs = appContext.getSharedPreferences("ai_interaction_config", Context.MODE_PRIVATE)

    private val sceneStatusUpdateListener = SceneStatusUpdateListener { sceneStatus ->
        sceneStatus?.isVideoRecordRunning?.let { isRunning ->
            if (!isRunning && _isRecording.value) {
                Log.d(TAG, "Video recording stopped")
                handleRecordingStoppedAndAutoUpload("scene_listener")
            }
        }
    }

    private val mediaFilesUpdateListener = MediaFilesUpdateListener {
        Log.d(TAG, "New media files detected (auto-upload disabled)")
        // 不再自动检查并上传
    }

    // 同步全局WiFi状态到本地
    init {
        loadLipReadingConfig()
        viewModelScope.launch {
            GlobalWifiManager.getInstance().wifiStatus.collect { globalStatus ->
                _wifiConnectionStatus.value = when (globalStatus) {
                    GlobalWifiManager.WifiStatus.CONNECTED -> {
                        // WiFi连接成功，不再自动检查并上传
                        ConnectionStatus.CONNECTED
                    }
                    GlobalWifiManager.WifiStatus.CONNECTING -> ConnectionStatus.CONNECTING
                    GlobalWifiManager.WifiStatus.DISCONNECTED -> ConnectionStatus.DISCONNECTED
                }
            }
        }
    }

    private fun loadLipReadingConfig() {
        // 同步唇语识别配置（与“我的/AI助手”一致）
        val savedDuration = aiPrefs.getInt("lip_reading_duration", _duration.value)
        _duration.value = savedDuration

        val savedResolution = aiPrefs.getString("lip_reading_resolution", null)
        parseResolution(savedResolution)?.let { size ->
            _selectedVideoSize.value = size
        }

        val savedUrl = aiPrefs.getString("lip_reading_server_url", "http://88bill99.top:25000")
        savedUrl?.trim()?.let { normalizedUrl ->
            _uploadUrl.value = normalizedUrl
            videoUploadApi = NetworkModule.createVideoUploadApi(normalizedUrl)
        }

        val savedLocalPath = aiPrefs.getString("lip_reading_local_media_path", null)
        savedLocalPath?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
            _localMediaPath.value = path
        }

        val savedExtractField = aiPrefs.getString("lip_reading_result_extract_field", _resultExtractField.value)
        savedExtractField?.trim()?.takeIf { it.isNotEmpty() }?.let { field ->
            _resultExtractField.value = field
        }

        _isAutoUploadEnabled.value = aiPrefs.getBoolean("lip_reading_auto_upload_enabled", true)
    }

    private fun parseResolution(resolution: String?): Size? {
        if (resolution.isNullOrBlank()) return null
        val parts = resolution.lowercase().split("x")
        if (parts.size != 2) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        return videoSize.firstOrNull { it.width == width && it.height == height } ?: Size(width, height)
    }

    private fun formatResolution(size: Size): String {
        return "${size.width}x${size.height}"
    }
    
    private val wifiP2PStatusCallback = object : WifiP2PStatusCallback {
        override fun onConnected() {
            Log.d(TAG, "Wi-Fi P2P connected")
            _wifiConnectionStatus.value = ConnectionStatus.CONNECTED
            _serverResponseMessage.value = "WiFi已连接"
            // 不再自动检查并上传
        }

        override fun onDisconnected() {
            Log.d(TAG, "Wi-Fi P2P disconnected")
            _wifiConnectionStatus.value = ConnectionStatus.DISCONNECTED
            _serverResponseMessage.value = "WiFi已断开"
        }

        override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
            Log.e(TAG, "Wi-Fi P2P connection failed: $errorCode")
            _wifiConnectionStatus.value = ConnectionStatus.DISCONNECTED
            _serverResponseMessage.value = "WiFi连接失败: $errorCode"
        }
    }

    private val unsyncNumResultCallback = UnsyncNumResultCallback { status, audioNum, pictureNum, videoNum ->
        if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED) {
            Log.d(TAG, "Unsync numbers: audio=$audioNum, picture=$pictureNum, video=$videoNum")
            _videoNumber.value = videoNum
        }
    }

    private val syncStatusCallback = object : SyncStatusCallback {
        override fun onSyncStart() {
            Log.d(TAG, "Video sync started")
        }

        override fun onSingleFileSynced(fileName: String?) {
            Log.d(TAG, "Video file synced (原始): $fileName")
            fileName?.let {
                // 处理文件名：如果包含路径，提取文件名部分
                val cleanFileName = if (it.contains("/")) {
                    File(it).name
                } else {
                    it
                }
                Log.d(TAG, "Video file synced (处理后): $cleanFileName")
                
                // 检查文件扩展名，只处理视频文件
                val lowerFileName = cleanFileName.lowercase()
                val isVideoFile = lowerFileName.endsWith(".mp4") || 
                                 lowerFileName.endsWith(".mov") || 
                                 lowerFileName.endsWith(".avi") || 
                                 lowerFileName.endsWith(".mkv") ||
                                 lowerFileName.endsWith(".webm")
                
                if (!isVideoFile) {
                    Log.w(TAG, "跳过非视频文件: $cleanFileName (只处理视频文件)")
                    return@let
                }

                if (autoUploadSessionActive) {
                    Log.d(TAG, "自动上传会话进行中，跳过同步重复入队: $cleanFileName")
                    return@let
                }
                if (isAlreadyUploaded(cleanFileName)) {
                    Log.d(TAG, "文件已上传，跳过同步入队: $cleanFileName")
                    return@let
                }
                
                // 文件同步完成，添加到上传队列
                addToUploadQueue(cleanFileName)
            }
        }

        override fun onSyncFailed() {
            Log.e(TAG, "Video sync failed")
            ErrorReporter.report(
                source = TAG,
                stage = "videoSync",
                message = "视频同步失败"
            )
        }

        override fun onSyncFinished() {
            Log.d(TAG, "Video sync finished")
        }
    }

    fun setSceneStatusListener(toSet: Boolean) {
        if (toSet) {
            GlobalCustomViewManager.getInstance().registerSceneStatusListener(sceneStatusUpdateListener)
        } else {
            GlobalCustomViewManager.getInstance().unregisterSceneStatusListener(sceneStatusUpdateListener)
        }
    }

    fun sizeChoose(resolution: Size) {
        _selectedVideoSize.value = resolution
        aiPrefs.edit().putString("lip_reading_resolution", formatResolution(resolution)).apply()
    }

    fun setDuration(duration: Int) {
        _duration.value = duration
        aiPrefs.edit().putInt("lip_reading_duration", duration).apply()
        VoiceAssistantManager.getInstance(appContext).setLipReadingDuration(duration)
    }

    fun setDurationUnit(unit: DurationUnit) {
        _durationUnit.value = unit
    }

    fun setUploadUrl(context: Context, url: String) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isNotEmpty()) {
            _uploadUrl.value = normalizedUrl
            // 更新API实例
            videoUploadApi = NetworkModule.createVideoUploadApi(normalizedUrl)
            // 保存到全局唇语配置
            aiPrefs.edit().putString("lip_reading_server_url", normalizedUrl).apply()
            VoiceAssistantManager.getInstance(context).setLipReadingServerUrl(normalizedUrl)
            Log.d(TAG, "上传地址已设置: $normalizedUrl")
            _serverResponseMessage.value = "上传地址已设置为: $normalizedUrl"
        }
    }

    fun setLocalMediaPath(path: String) {
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty()) return
        _localMediaPath.value = normalizedPath
        aiPrefs.edit().putString("lip_reading_local_media_path", normalizedPath).apply()
        _serverResponseMessage.value = "本地上传路径已设置为: $normalizedPath"
    }

    fun setResultExtractField(field: String) {
        val normalizedField = field.trim()
        if (normalizedField.isEmpty()) return
        _resultExtractField.value = normalizedField
        aiPrefs.edit().putString("lip_reading_result_extract_field", normalizedField).apply()
        _serverResponseMessage.value = "识别结果提取字段已设置为: $normalizedField"
    }

    fun setAutoUploadEnabled(enabled: Boolean) {
        _isAutoUploadEnabled.value = enabled
        aiPrefs.edit().putBoolean("lip_reading_auto_upload_enabled", enabled).apply()
        _serverResponseMessage.value = if (enabled) {
            "已开启自动上传视频"
        } else {
            "已关闭自动上传视频"
        }
    }

    fun loadUploadUrl(context: Context) {
        val savedUrl = aiPrefs.getString("lip_reading_server_url", "http://88bill99.top:25000")
        savedUrl?.let {
            val normalizedUrl = it.trim()
            _uploadUrl.value = normalizedUrl
            videoUploadApi = NetworkModule.createVideoUploadApi(normalizedUrl)
            Log.d(TAG, "上传地址已加载: $normalizedUrl")
            _serverResponseMessage.value = "当前上传地址: $normalizedUrl"
        }
    }

    private fun checkNetworkConnection(): Boolean {
        try {
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Log.e(TAG, "检查网络连接失败", e)
            return false
        }
    }

    private fun hasAvailableUploadConnection(): Boolean {
        return _wifiConnectionStatus.value == ConnectionStatus.CONNECTED || checkNetworkConnection()
    }

    private fun refreshEffectiveConnectionStatus() {
        _wifiConnectionStatus.value = when {
            _wifiConnectionStatus.value == ConnectionStatus.CONNECTING -> ConnectionStatus.CONNECTING
            hasAvailableUploadConnection() -> ConnectionStatus.CONNECTED
            else -> ConnectionStatus.DISCONNECTED
        }
    }

    private suspend fun checkServerReady(): String? = withContext(Dispatchers.IO) {
        val url = _uploadUrl.value.trim()
        if (url.isEmpty()) return@withContext "服务器地址为空"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext "服务器地址格式错误"
        }
        refreshEffectiveConnectionStatus()
        if (!hasAvailableUploadConnection()) {
            return@withContext "网络未连接，无法上传"
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

    fun testConnection() {
        viewModelScope.launch {
            _serverResponseMessage.value = "正在测试连接（不会上传测试视频）..."
            Log.d(TAG, "开始测试连接: ${_uploadUrl.value}")
            val result = UploadUrlHelper.probeUploadEndpoint(_uploadUrl.value)
            _serverResponseMessage.value = if (result.success) {
                "连接测试成功！\n${result.message}"
            } else {
                "连接测试失败\n${result.message}"
            }
        }
    }

    fun setVideoParams() {
        val size = _selectedVideoSize.value
        CxrApi.getInstance().setVideoParams(
            _duration.value,
            30,
            size.width,
            size.height,
            if (_durationUnit.value == DurationUnit.MINUTES) 0 else 1
        )
        Log.d(TAG, "Video params set: ${size.width}x${size.height}, duration=${_duration.value} ${_durationUnit.value}")
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            // 停止录制
            openOrCloseVideoRecord(false)
            handleRecordingStoppedAndAutoUpload("manual_stop")
        } else {
            viewModelScope.launch {
                val error = checkServerReady()
                if (error != null) {
                    _serverResponseMessage.value = error
                    _uploadStatus.value = UploadStatus.FAILED
                    return@launch
                }
                // 开始录制 - 立即开始，不等待WiFi连接
                openOrCloseVideoRecord(true)
                _isRecording.value = true
                recordStartAtMs = System.currentTimeMillis()
                _uploadStatus.value = UploadStatus.IDLE
                // WiFi连接在后台异步进行，不阻塞录制
                delay(500) // 稍微延迟，确保录制已启动
                autoConnectWifiP2P()
            }
        }
    }

    private fun openOrCloseVideoRecord(toRecord: Boolean) {
        if (toRecord) {
            CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.VIDEO_RECORD, true, null)
        } else {
            CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.VIDEO_RECORD, false, null)
        }
    }

    /**
     * 手动连接 WiFi P2P（委托给全局管理器）
     */
    fun connectWifiP2P() {
        GlobalWifiManager.getInstance().connectWifi()
    }

    /**
     * 断开 WiFi P2P（委托给全局管理器）
     */
    fun disconnectWifiP2P() {
        GlobalWifiManager.getInstance().disconnectWifi()
    }

    private fun autoConnectWifiP2P() {
        if (_wifiConnectionStatus.value == ConnectionStatus.DISCONNECTED) {
            safeInitWifiP2P("Auto")
        }
    }

    private fun handleRecordingStoppedAndAutoUpload(source: String) {
        _isRecording.value = false
        if (!_isAutoUploadEnabled.value) {
            _uploadStatus.value = UploadStatus.IDLE
            _serverResponseMessage.value = "录像已结束，当前未开启自动上传"
            return
        }
        val now = System.currentTimeMillis()
        // 防止手动停止和场景回调重复触发自动上传
        if (now - lastAutoUploadTriggerAtMs < 1500) {
            return
        }
        lastAutoUploadTriggerAtMs = now
        autoUploadSessionActive = true
        currentAutoUploadPath = null
        // 新一次录像结束，清掉上一轮 SUCCESS，避免误判「已上传完成」
        _uploadStatus.value = UploadStatus.IDLE
        _serverResponseMessage.value = "录像已结束，准备自动上传..."
        if (!isProgressPlaceholder(_lipReadingResult.value) && _uploadStatus.value == UploadStatus.SUCCESS) {
            // 保留上一轮识别结果文案
        } else {
            _lipReadingResult.value = "准备上传..."
        }
        autoUploadJob?.cancel()
        autoUploadJob = viewModelScope.launch {
            try {
                triggerAutoUploadAfterRecording(source)
            } finally {
                autoUploadSessionActive = false
                currentAutoUploadPath = null
            }
        }
    }

    private suspend fun triggerAutoUploadAfterRecording(source: String) {
        Log.d(TAG, "录制结束自动上传触发: $source")

        if (_wifiConnectionStatus.value == ConnectionStatus.DISCONNECTED) {
            autoConnectWifiP2P()
        }

        var waitCount = 0
        while (_wifiConnectionStatus.value == ConnectionStatus.CONNECTING && waitCount < 20) {
            delay(500)
            waitCount++
        }

        refreshEffectiveConnectionStatus()
        if (!hasAvailableUploadConnection()) {
            _uploadStatus.value = UploadStatus.FAILED
            _lipReadingResult.value = "上传失败：网络未连接"
            _serverResponseMessage.value = "自动上传失败：网络未连接"
            return
        }

        val latestVideo = findLatestVideoForAutoUploadWithRetry()
        if (latestVideo == null) {
            _uploadStatus.value = UploadStatus.FAILED
            _lipReadingResult.value = "上传失败：未找到录制视频"
            _serverResponseMessage.value = "自动上传失败：未找到可上传视频"
            return
        }

        _lastUploadedVideoPath.value = latestVideo.absolutePath
        currentAutoUploadPath = latestVideo.absolutePath
        autoUploadFileWithRetry(latestVideo)
    }

    private suspend fun findLatestVideoForAutoUploadWithRetry(): File? {
        findLatestVideoForAutoUpload()?.let { return it }
        _serverResponseMessage.value = "等待视频同步到手机..."
        try {
            val path = withTimeout(12_000) {
                GlobalVideoSyncQueue.latestVideo
                    .filter { p ->
                        if (p.isNullOrBlank()) return@filter false
                        val f = File(p)
                        f.exists() && isVideoFileName(f.name) &&
                            (recordStartAtMs <= 0L || f.lastModified() >= recordStartAtMs - 3_000L)
                    }
                    .first()
            }
            if (!path.isNullOrBlank()) return File(path)
        } catch (_: TimeoutCancellationException) {
            Log.d(TAG, "同步队列等待超时，改用目录轮询")
        }
        repeat(autoUploadFindFileRetries) { attempt ->
            _serverResponseMessage.value = "查找视频 (${attempt + 1}/$autoUploadFindFileRetries)..."
            findLatestVideoForAutoUpload()?.let { return it }
            if (attempt < autoUploadFindFileRetries - 1) delay(300)
        }
        return null
    }

    private suspend fun autoUploadFileWithRetry(file: File) {
        repeat(autoUploadMaxAttempts) { attempt ->
            if (isAlreadyUploaded(file.absolutePath)) {
                Log.d(TAG, "本视频已上传过，结束自动上传: ${file.absolutePath}")
                finalizeAutoUploadUiSuccess()
                return
            }
            _uploadStatus.value = UploadStatus.UPLOADING
            _serverResponseMessage.value = "正在自动上传 (${attempt + 1}/$autoUploadMaxAttempts)..."
            val alreadyQueued = synchronized(uploadQueue) { uploadQueue.contains(file.absolutePath) }
            if (!alreadyQueued) {
                addToUploadQueue(file.absolutePath)
            } else {
                Log.d(TAG, "文件已在队列中，等待上传完成 (attempt=${attempt + 1})")
            }
            val success = waitForAutoUploadResult(file.absolutePath)
            if (success) {
                finalizeAutoUploadUiSuccess()
                return
            }
            if (attempt < autoUploadMaxAttempts - 1) {
                _lipReadingResult.value = "上传失败，正在重试..."
                _serverResponseMessage.value = "自动上传失败，正在重试 (${attempt + 2}/$autoUploadMaxAttempts)"
                delay(1500)
            }
        }
        if (_uploadStatus.value != UploadStatus.SUCCESS) {
            _uploadStatus.value = UploadStatus.FAILED
            _lipReadingResult.value = "上传失败：已达到最大重试次数"
            _serverResponseMessage.value = "自动上传失败：已重试 $autoUploadMaxAttempts 次"
        } else {
            finalizeAutoUploadUiSuccess()
        }
    }

    /**
     * 等待本次自动上传完成。必须观察到 UPLOADING/WAITING 之后才算 SUCCESS，避免沿用上一轮成功状态。
     */
    private suspend fun waitForAutoUploadResult(expectedPath: String): Boolean {
        var sawActiveUpload = false
        repeat(600) {
            when (_uploadStatus.value) {
                UploadStatus.SUCCESS -> {
                    if (sawActiveUpload && isAlreadyUploaded(expectedPath)) return true
                }
                UploadStatus.FAILED -> if (sawActiveUpload) return false
                UploadStatus.UPLOADING, UploadStatus.WAITING_RESPONSE -> sawActiveUpload = true
                else -> Unit
            }
            delay(500)
        }
        Log.w(TAG, "等待自动上传结果超时（300s）")
        return _uploadStatus.value == UploadStatus.SUCCESS &&
            isAlreadyUploaded(expectedPath)
    }

    private fun isAutoUploadProgressMessage(msg: String?): Boolean {
        if (msg.isNullOrBlank()) return false
        return msg.contains("准备自动上传") ||
            msg.contains("正在自动上传") ||
            msg.contains("自动上传失败，正在重试") ||
            msg.contains("正在查找录制")
    }

    /** 自动上传流程结束：同步识别结果框与返回消息，去掉「准备自动上传」等中间态 */
    private fun finalizeAutoUploadUiSuccess() {
        syncResultTextAfterUploadSuccess()
        val serverMsg = _serverResponseMessage.value.orEmpty()
        if (isAutoUploadProgressMessage(serverMsg)) {
            _serverResponseMessage.value = when {
                serverMsg.contains("提取结果并显示到眼镜") -> serverMsg
                !isProgressPlaceholder(_lipReadingResult.value) &&
                    (_lipReadingResult.value?.startsWith("{") == true ||
                        _lipReadingResult.value?.startsWith("[") == true) ->
                    "自动上传完成，识别结果见上方"
                else -> "自动上传完成"
            }
        }
    }

    /** 上传已成功但识别结果框仍显示占位文案时补齐 */
    private fun syncResultTextAfterUploadSuccess() {
        if (_uploadStatus.value != UploadStatus.SUCCESS) return
        if (!isProgressPlaceholder(_lipReadingResult.value)) return
        val serverMsg = _serverResponseMessage.value.orEmpty()
        _lipReadingResult.value = when {
            serverMsg.contains("提取结果并显示到眼镜") || serverMsg.contains("服务器返回") ->
                serverMsg
            else -> "上传成功，请查看上方返回消息或识别结果 JSON"
        }
    }

    private fun isAlreadyUploaded(pathOrName: String): Boolean {
        val file = File(pathOrName)
        return uploadedFileKeys.contains(pathOrName) ||
            uploadedFileKeys.contains(file.absolutePath) ||
            uploadedFileKeys.contains(file.name)
    }

    private fun markUploaded(file: File) {
        uploadedFileKeys.add(file.absolutePath)
        uploadedFileKeys.add(file.name)
        Log.d(TAG, "已标记上传完成: ${file.name}")
    }

    private fun isProgressPlaceholder(text: String?): Boolean {
        return text == null ||
            text == "上传中..." ||
            text == "准备上传..." ||
            text.startsWith("上传失败，正在重试")
    }

    /** 已成功或已上传过的重复任务：不覆盖识别结果、不把状态改回上传中 */
    private fun finishAsSkippedDuplicate(fileName: String) {
        Log.d(TAG, "跳过重复上传任务: $fileName")
        _currentUploadingFile.value = null
        if (_uploadStatus.value != UploadStatus.SUCCESS) {
            _uploadStatus.value = UploadStatus.SUCCESS
        }
    }

    private fun findLatestVideoForAutoUpload(): File? {
        val threshold = if (recordStartAtMs > 0L) recordStartAtMs - 3_000L else 0L

        GlobalVideoSyncQueue.getLatestVideoAfter(threshold)?.let { queuedPath ->
            val queuedFile = File(queuedPath)
            if (queuedFile.exists() && queuedFile.isFile && isVideoFileName(queuedFile.name)) {
                Log.d(TAG, "从全局同步队列找到录制视频: ${queuedFile.absolutePath}")
                return queuedFile
            }
        }

        val candidateDirs = linkedSetOf(
            File(_localMediaPath.value),
            MediaPathProvider.getRootDir(appContext)
        )

        candidateDirs.forEach { dir ->
            val latestVideo = findLatestVideoInDirectory(dir, threshold)
            if (latestVideo != null) {
                Log.d(TAG, "从目录找到录制视频: ${latestVideo.absolutePath}")
                return latestVideo
            }
        }

        Log.w(TAG, "未找到录制视频，已检查目录: ${candidateDirs.joinToString { it.absolutePath }}")
        return null
    }

    private fun findLatestVideoInDirectory(rootDir: File, threshold: Long): File? {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            Log.w(TAG, "视频搜索目录不存在: ${rootDir.absolutePath}")
            return null
        }

        return try {
            rootDir.walkTopDown()
                .filter { file -> file.isFile && isVideoFileName(file.name) }
                .sortedByDescending { it.lastModified() }
                .firstOrNull { it.lastModified() >= threshold }
                ?: rootDir.walkTopDown()
                    .filter { file -> file.isFile && isVideoFileName(file.name) }
                    .maxByOrNull { it.lastModified() }
        } catch (e: Exception) {
            Log.e(TAG, "递归查找录制视频失败: ${rootDir.absolutePath}", e)
            null
        }
    }

    private fun isVideoFileName(fileName: String): Boolean {
        val lowerFileName = fileName.lowercase()
        return lowerFileName.endsWith(".mp4") ||
            lowerFileName.endsWith(".mov") ||
            lowerFileName.endsWith(".avi") ||
            lowerFileName.endsWith(".mkv") ||
            lowerFileName.endsWith(".webm")
    }

    /**
     * 安全地初始化WiFi P2P，添加异常处理和状态检查
     */
    private fun safeInitWifiP2P(caller: String) {
        try {
            // 检查蓝牙是否已连接
            if (!CxrApi.getInstance().isBluetoothConnected) {
                Log.w(TAG, "$caller: Bluetooth not connected, cannot init WiFi P2P")
                _serverResponseMessage.value = "蓝牙未连接，无法连接WiFi"
                return
            }

            if (_wifiConnectionStatus.value == ConnectionStatus.DISCONNECTED) {
                Log.d(TAG, "$caller: Connecting Wi-Fi P2P...")
                _wifiConnectionStatus.value = ConnectionStatus.CONNECTING
                CxrApi.getInstance().initWifiP2P(wifiP2PStatusCallback)
            } else {
                Log.d(TAG, "$caller: WiFi already in state: ${_wifiConnectionStatus.value}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "$caller: Error initializing WiFi P2P", e)
            _wifiConnectionStatus.value = ConnectionStatus.DISCONNECTED
            _serverResponseMessage.value = "WiFi连接失败: ${e.message}"
        }
    }


    /**
     * 手动检查并上传视频（已禁用自动检查）
     * 此方法保留用于手动触发上传
     */
    fun checkAndUploadVideos() {
        viewModelScope.launch {
            val error = checkServerReady()
            if (error != null) {
                _uploadStatus.value = UploadStatus.FAILED
                _serverResponseMessage.value = error
                return@launch
            }
            if (_wifiConnectionStatus.value == ConnectionStatus.DISCONNECTED) {
                // Wi-Fi未连接，先连接
                autoConnectWifiP2P()
                // 等待连接完成，但不要无限等待
                var retryCount = 0
                while (_wifiConnectionStatus.value != ConnectionStatus.CONNECTED && 
                       _wifiConnectionStatus.value != ConnectionStatus.DISCONNECTED && 
                       retryCount < 20) {
                    delay(1000)
                    retryCount++
                }
            }

            if (_wifiConnectionStatus.value == ConnectionStatus.CONNECTED) {
                // 查询未同步的视频数量
                try {
                    CxrApi.getInstance().getUnsyncNum(unsyncNumResultCallback)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting unsync num", e)
                }
            }
        }
    }

    /**
     * 添加文件到上传队列
     */
    private fun addToUploadQueue(fileName: String) {
        val normalizedPath = fileName.trim()
        if (normalizedPath.isEmpty()) return

        // 检查文件扩展名，只上传视频文件
        val displayName = File(normalizedPath).name
        val isVideoFile = isVideoFileName(displayName)
        
        if (!isVideoFile) {
            Log.w(TAG, "跳过非视频文件: $normalizedPath")
            return
        }
        
        if (isAlreadyUploaded(normalizedPath)) {
            Log.d(TAG, "文件已上传过，跳过入队: $normalizedPath")
            return
        }

        synchronized(uploadQueue) {
            if (!uploadQueue.contains(normalizedPath)) {
                uploadQueue.add(normalizedPath)
                Log.d(TAG, "文件已添加到上传队列: $normalizedPath, 队列长度: ${uploadQueue.size}")
            } else {
                Log.d(TAG, "文件已在队列中，跳过: $normalizedPath")
            }
        }
        // 如果当前没有在上传，开始处理队列
        if (!isUploading) {
            processUploadQueue()
        }
    }

    /**
     * 处理上传队列
     */
    private fun processUploadQueue() {
        if (isUploading) {
            Log.d(TAG, "正在上传中，跳过处理队列")
            return
        }
        
        uploadJob?.cancel()
        uploadJob = viewModelScope.launch {
            try {
                isUploading = true
                
                while (uploadQueue.isNotEmpty()) {
                    val fileName = synchronized(uploadQueue) {
                        if (uploadQueue.isNotEmpty()) {
                            uploadQueue.removeAt(0)
                        } else {
                            null
                        }
                    }
                    
                    fileName?.let {
                        Log.d(TAG, "开始处理队列中的文件: $it, 剩余: ${uploadQueue.size}")
                        uploadVideoFileInternal(it)
                    }
                }
                
                isUploading = false
                Log.d(TAG, "上传队列处理完成")
            } catch (e: Exception) {
                isUploading = false
                Log.e(TAG, "处理上传队列异常", e)
            }
        }
    }

    /**
     * 内部上传方法（实际执行上传）
     */
    private suspend fun uploadVideoFileInternal(fileName: String) {
        try {
            // 检查网络连接
            if (!checkNetworkConnection()) {
                val errorMsg = "网络未连接，无法上传\n请检查手机网络连接"
                Log.e(TAG, errorMsg)
                ErrorReporter.report(
                    source = TAG,
                    stage = "uploadVideoFileInternal",
                    message = "网络未连接，无法上传",
                    detail = errorMsg
                )
                _uploadStatus.value = UploadStatus.FAILED
                _serverResponseMessage.value = errorMsg
                _currentUploadingFile.value = null
                return
            }

            if (isAlreadyUploaded(fileName)) {
                finishAsSkippedDuplicate(fileName)
                return
            }

            val directFile = File(fileName)
            val candidateFiles = buildList {
                if (directFile.isAbsolute) add(directFile)
                add(File(_localMediaPath.value, File(fileName).name))
                add(File(MediaPathProvider.getRootPath(appContext), File(fileName).name))
            }
            
            var file: File? = candidateFiles.firstOrNull { it.exists() }
            if (file != null) {
                Log.d(TAG, "找到文件: ${file.absolutePath}")
            } else {
                candidateFiles.forEach { testFile ->
                    Log.d(TAG, "文件不存在: ${testFile.absolutePath}")
                }
            }
            
            if (file == null || !file.exists()) {
                if (isAlreadyUploaded(fileName)) {
                    finishAsSkippedDuplicate(fileName)
                    return
                }
                val errorMsg = "视频文件不存在: $fileName\n已尝试路径:\n${candidateFiles.joinToString("\n") { it.absolutePath }}"
                Log.e(TAG, errorMsg)
                ErrorReporter.report(
                    source = TAG,
                    stage = "uploadVideoFileInternal",
                    message = "视频文件不存在",
                    detail = errorMsg
                )
                _uploadStatus.value = UploadStatus.FAILED
                _serverResponseMessage.value = errorMsg
                _currentUploadingFile.value = null
                return
            }

            _uploadStatus.value = UploadStatus.UPLOADING
            _currentUploadingFile.value = file.name
            _lipReadingResult.value = "上传中..."

            // 检查文件扩展名
            val lowerFileName = fileName.lowercase()
            val isVideoFile = lowerFileName.endsWith(".mp4") || 
                             lowerFileName.endsWith(".mov") || 
                             lowerFileName.endsWith(".avi") || 
                             lowerFileName.endsWith(".mkv") ||
                             lowerFileName.endsWith(".webm")
            
            if (!isVideoFile) {
                val errorMsg = "不是视频文件，跳过上传: $fileName"
                Log.w(TAG, errorMsg)
                ErrorReporter.report(
                    source = TAG,
                    stage = "uploadVideoFileInternal",
                    message = "不是视频文件，跳过上传",
                    detail = "fileName=$fileName"
                )
                _uploadStatus.value = UploadStatus.FAILED
                _serverResponseMessage.value = errorMsg
                _currentUploadingFile.value = null
                return
            }

            // 检查文件大小
            val fileSize = file.length()
            if (fileSize == 0L) {
                val errorMsg = "视频文件大小为0: $fileName"
                Log.e(TAG, errorMsg)
                ErrorReporter.report(
                    source = TAG,
                    stage = "uploadVideoFileInternal",
                    message = "视频文件大小为0",
                    detail = "fileName=$fileName"
                )
                _uploadStatus.value = UploadStatus.FAILED
                _serverResponseMessage.value = errorMsg
                _currentUploadingFile.value = null
                return
            }

            // 创建MultipartBody.Part
            // 使用 video/mp4 MIME类型，字段名使用 'file' 与服务器匹配
            // 只使用文件名，不包含路径（与Python的os.path.basename一致）
            val baseFileName = File(fileName).name
            val requestFile = file.asRequestBody("video/mp4".toMediaType())
            val videoPart = MultipartBody.Part.createFormData("file", baseFileName, requestFile)
            
            Log.d(TAG, "上传参数:")
            Log.d(TAG, "  - 原始文件名: $fileName")
            Log.d(TAG, "  - 使用文件名: $baseFileName")
            Log.d(TAG, "  - 文件大小: ${fileSize} bytes")
            Log.d(TAG, "  - MIME类型: video/mp4")
            Log.d(TAG, "  - 字段名: file")
            Log.d(TAG, "  - 上传URL: ${_uploadUrl.value}")

            // 上传文件，最多重试3次
            var success = false
            var retryCount = 0
            val maxRetries = 3
            var lastError: String? = null

            while (!success && retryCount < maxRetries) {
                    try {
                        Log.d(TAG, "尝试上传 (${retryCount + 1}/$maxRetries): $fileName")

                        val upload = withContext(Dispatchers.IO) {
                            VideoUploadCoordinator.uploadFileOnly(
                                videoUploadApi,
                                _uploadUrl.value,
                                videoPart,
                                fast = true
                            ) { phase -> updateUploadPhase(phase) }
                        }

                        if (!upload.success) {
                            lastError = upload.error ?: "上传失败"
                            Log.e(TAG, "上传失败: $lastError")
                            _serverResponseMessage.value = "上传失败 (${retryCount + 1}/$maxRetries): $lastError"
                            retryCount++
                            if (retryCount < maxRetries) delay(2000)
                            continue
                        }

                        val sizeKb = fileSize / 1024
                        updateUploadPhase("上传完成(${sizeKb}KB)，识别处理中...")
                        _uploadStatus.value = UploadStatus.WAITING_RESPONSE

                        val responseMessage = withContext(Dispatchers.IO) {
                            if (!upload.taskId.isNullOrBlank()) {
                                VideoUploadCoordinator.pollRecognitionResult(
                                    videoUploadApi,
                                    _uploadUrl.value,
                                    upload.taskId!!
                                ) { phase -> updateUploadPhase(phase) }
                            } else {
                                upload.immediateBody
                            }
                        }

                        if (!responseMessage.isNullOrBlank()) {
                            Log.d(TAG, "视频上传并识别完成: $fileName")
                            success = true
                            try {
                                _lipReadingResult.value = prettifyJson(responseMessage)
                                val json = JSONObject(responseMessage)
                                val extractedResult = extractResultField(json, _resultExtractField.value)
                                val video = json.optString("video", "")
                                if (video.isNotBlank()) {
                                    val base = _uploadUrl.value
                                    val fullVideoUrl = if (video.startsWith("http")) video else base.trimEnd('/') + "/" + video.trimStart('/')
                                    _lastUploadedVideoUrl.value = fullVideoUrl
                                }
                                
                                // 保存本地文件路径供预览使用（在删除前保存）
                                _lastUploadedVideoPath.value = file.absolutePath
                                _serverResponseMessage.value = if (extractedResult.isNotBlank()) {
                                    "已从字段 ${_resultExtractField.value} 提取结果并显示到眼镜"
                                } else {
                                    "上传成功，但字段 ${_resultExtractField.value} 未返回内容"
                                }
                                if (extractedResult.isBlank()) {
                                    showResultOnGlasses("字段 ${_resultExtractField.value} 未返回识别结果")
                                } else {
                                    showResultOnGlasses(extractedResult)
                                }
                                markUploaded(file)
                                _uploadStatus.value = UploadStatus.SUCCESS
                                if (autoUploadSessionActive) {
                                    finalizeAutoUploadUiSuccess()
                                } else {
                                    syncResultTextAfterUploadSuccess()
                                }
                                
                                // 自动触发语音合成并播放
                                if (extractedResult.isNotBlank()) {
                                    Log.d(TAG, "收到识别结果，自动触发语音合成: $extractedResult")
                                    synthesizeAndPlay(extractedResult, autoPlay = true)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "解析服务器返回失败: $responseMessage", e)
                                ErrorReporter.report(
                                    source = TAG,
                                    stage = "uploadVideoFileInternal",
                                    message = "解析服务器返回失败",
                                    detail = responseMessage
                                )
                                _lipReadingResult.value = responseMessage
                                _serverResponseMessage.value = "上传成功: $fileName\n服务器返回: $responseMessage"
                                markUploaded(file)
                                _uploadStatus.value = UploadStatus.SUCCESS
                                if (autoUploadSessionActive) {
                                    finalizeAutoUploadUiSuccess()
                                } else {
                                    syncResultTextAfterUploadSuccess()
                                }
                            }

                            // 上传成功后延迟删除本地文件（保留用于预览）
                            viewModelScope.launch {
                                delay(5000) // 延迟5秒删除，给预览留时间
                                if (file.exists() && file.delete()) {
                                    Log.d(TAG, "本地文件已删除: $fileName")
                                } else {
                                    Log.w(TAG, "删除本地文件失败或文件不存在: $fileName")
                                }
                            }
                        } else {
                            lastError = "识别超时或失败"
                            Log.e(TAG, "识别失败: $fileName")
                            _serverResponseMessage.value = "识别失败 (${retryCount + 1}/$maxRetries)"
                            retryCount++
                            if (retryCount < maxRetries) delay(2000)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        lastError = "连接超时: ${e.message}"
                        Log.e(TAG, lastError, e)
                        _serverResponseMessage.value = "上传失败 (${retryCount + 1}/$maxRetries): $lastError"
                        retryCount++
                        if (retryCount < maxRetries) {
                            delay(2000)
                        }
                    } catch (e: java.net.UnknownHostException) {
                        lastError = "无法连接到服务器: ${e.message}\n请检查URL是否正确: ${_uploadUrl.value}"
                        Log.e(TAG, lastError, e)
                        _serverResponseMessage.value = lastError
                        retryCount = maxRetries // 网络错误不重试
                    } catch (e: java.net.ConnectException) {
                        lastError = "连接被拒绝: ${e.message}\n请检查服务器是否运行: ${_uploadUrl.value}"
                        Log.e(TAG, lastError, e)
                        _serverResponseMessage.value = lastError
                        retryCount = maxRetries // 连接错误不重试
                    } catch (e: HttpException) {
                        lastError = "HTTP异常 (${e.code()}): ${e.message}"
                        Log.e(TAG, lastError, e)
                        _serverResponseMessage.value = "上传失败 (${retryCount + 1}/$maxRetries): $lastError"
                        retryCount++
                        if (retryCount < maxRetries) {
                            delay(2000)
                        }
                    } catch (e: Exception) {
                        lastError = "上传异常: ${e.javaClass.simpleName} - ${e.message}"
                        Log.e(TAG, lastError, e)
                        _serverResponseMessage.value = "上传失败 (${retryCount + 1}/$maxRetries): ${e.message ?: "未知错误"}"
                        retryCount++
                        if (retryCount < maxRetries) {
                            delay(2000)
                        }
                    }
            }

            if (!success && _uploadStatus.value != UploadStatus.SUCCESS) {
                val errorMessage = "上传最终失败: 已重试 $maxRetries 次\n文件: $fileName\n最后错误: $lastError"
                Log.e(TAG, errorMessage)
                ErrorReporter.report(
                    source = TAG,
                    stage = "uploadVideoFileInternal",
                    message = "上传最终失败",
                    detail = errorMessage
                )
                _uploadStatus.value = UploadStatus.FAILED
                _serverResponseMessage.value = errorMessage
            }

            _currentUploadingFile.value = null

            // 不再自动检查未上传的视频

        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消，这是正常的，不需要记录为错误
            Log.d(TAG, "上传任务被取消: $fileName")
            _currentUploadingFile.value = null
            throw e // 重新抛出，让协程知道被取消了
        } catch (e: Exception) {
            val errorMsg = "上传过程异常: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, errorMsg, e)
            ErrorReporter.report(
                source = TAG,
                stage = "uploadVideoFileInternal",
                message = "上传过程异常",
                detail = errorMsg
            )
            _uploadStatus.value = UploadStatus.FAILED
            _serverResponseMessage.value = errorMsg
            _currentUploadingFile.value = null
        }
    }

    /**
     * 语音合成并播放
     * @param text 要合成的文本
     * @param autoPlay 是否自动播放
     */
    fun synthesizeAndPlay(text: String, autoPlay: Boolean = false) {
        if (text.isBlank()) {
            _ttsStatusMessage.value = "没有文本可以合成"
            return
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 从VoiceService配置读取密钥
                val appKey = sharedPreferences.getString("app_key", "") ?: ""
                val accessKeyId = sharedPreferences.getString("access_key_id", "") ?: ""
                val accessKeySecret = sharedPreferences.getString("access_key_secret", "") ?: ""
                
                if (appKey.isEmpty() || accessKeyId.isEmpty() || accessKeySecret.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _ttsStatusMessage.value = "请先在语音服务中配置AppKey和AccessKey"
                    }
                    return@launch
                }
                
                _isSynthesizing.value = true
                withContext(Dispatchers.Main) {
                    _ttsStatusMessage.value = "正在获取Token..."
                }
                
                // 获取Token
                val token = try {
                    Log.d(TAG, "开始获取Token(TTS)")
                    val tokenHelper = AliyunTokenHelper(accessKeyId, accessKeySecret)
                    tokenHelper.getToken()
                } catch (e: Exception) {
                    Log.e(TAG, "获取Token失败(TTS)", e)
                    val errorMsg = when (e) {
                        is IOException -> "网络错误: ${e.message}"
                        is SecurityException -> "签名错误: 请检查AccessKey"
                        else -> "获取Token失败: ${e.message}"
                    }
                    withContext(Dispatchers.Main) {
                        _ttsStatusMessage.value = errorMsg
                        _isSynthesizing.value = false
                    }
                    return@launch
                }
                
                withContext(Dispatchers.Main) {
                    _ttsStatusMessage.value = "正在合成语音..."
                }
                
                // 创建输出文件
                val outputFile = File(ttsRecordPath, "lip_tts_${System.currentTimeMillis()}.wav")
                val parent = outputFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                
                // 初始化NLS客户端
                nlsClient = NlsClient(token)
                Log.d(TAG, "NLS客户端初始化成功（TTS）")
                
                // 创建合成监听器
                val listener = object : SpeechSynthesizerListener() {
                    private var firstRecvBinary = true
                    
                    override fun onComplete(response: SpeechSynthesizerResponse) {
                        Log.d(TAG, "语音合成完成: ${response.taskId}")
                        CoroutineScope(Dispatchers.Main).launch {
                            _ttsStatusMessage.value = "合成完成"
                            _isSynthesizing.value = false
                            _lastTtsFilePath.value = outputFile.absolutePath
                            
                            // 自动播放
                            if (autoPlay) {
                                playTts()
                            }
                        }
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
                        CoroutineScope(Dispatchers.Main).launch {
                            _ttsStatusMessage.value = "合成失败: ${response.statusText}"
                            _isSynthesizing.value = false
                        }
                    }
                    
                    override fun onMetaInfo(response: SpeechSynthesizerResponse) {
                        Log.d(TAG, "TTS MetaInfo: ${response.taskId}")
                    }
                }
                
                // 创建合成器
                synthesizer = SpeechSynthesizer(nlsClient, listener)
                synthesizer?.setAppKey(appKey)
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
                
            } catch (e: Exception) {
                Log.e(TAG, "语音合成异常", e)
                withContext(Dispatchers.Main) {
                    _ttsStatusMessage.value = "合成异常: ${e.message}"
                    _isSynthesizing.value = false
                }
            }
        }
    }
    
    /**
     * 播放最近一次合成的语音
     */
    fun playTts() {
        val filePath = _lastTtsFilePath.value
        if (filePath.isNullOrEmpty()) {
            _ttsStatusMessage.value = "没有可播放的语音"
            return
        }
        
        val file = File(filePath)
        if (!file.exists()) {
            _ttsStatusMessage.value = "语音文件不存在"
            return
        }
        
        try {
            // 停止之前的播放
            mediaPlayer?.stop()
            mediaPlayer?.release()
            
            // 创建新的播放器
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(filePath)
                prepare()
                
                setOnCompletionListener {
                    Log.d(TAG, "语音播放完成")
                    _ttsStatusMessage.value = "播放完成"
                }
                
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "播放失败: what=$what, extra=$extra")
                    _ttsStatusMessage.value = "播放失败"
                    false
                }
                
                start()
                _ttsStatusMessage.value = "正在播放..."
                Log.d(TAG, "开始播放语音: $filePath")
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放语音失败", e)
            _ttsStatusMessage.value = "播放失败: ${e.message}"
        }
    }

    private fun prettifyJson(raw: String): String {
        val trimmed = raw.trim()
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun extractResultField(json: JSONObject, fieldPath: String): String {
        if (fieldPath.isBlank()) return ""
        val normalizedSegments = fieldPath.split('.')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (normalizedSegments.isEmpty()) return ""

        var current: Any = json
        for (segment in normalizedSegments) {
            current = when (current) {
                is JSONObject -> if (current.has(segment) && !current.isNull(segment)) current.get(segment) else return ""
                is JSONArray -> {
                    val index = segment.toIntOrNull() ?: return ""
                    if (index in 0 until current.length() && !current.isNull(index)) current.get(index) else return ""
                }
                else -> return ""
            }
        }

        return when (current) {
            is JSONObject -> current.toString()
            is JSONArray -> current.toString()
            JSONObject.NULL -> ""
            else -> current.toString()
        }
    }

    private fun showResultOnGlasses(result: String) {
        viewModelScope.launch(Dispatchers.Main) {
            GlobalCustomViewManager.getInstance().showTextView(result)
        }
    }

    private fun updateUploadPhase(phase: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            _lipReadingResult.value = phase
            _serverResponseMessage.value = phase
            when {
                phase.contains("识别") -> _uploadStatus.value = UploadStatus.WAITING_RESPONSE
                phase.contains("上传") -> _uploadStatus.value = UploadStatus.UPLOADING
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        uploadJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        synthesizer?.close()
        nlsClient?.shutdown()
        // 恢复全局场景监听（避免影响AI助手唤醒）
        GlobalCustomViewManager.getInstance().initialize(getApplication())
        // WiFi由全局管理器管理，不在这里断开
    }

    enum class DurationUnit {
        SECONDS, MINUTES
    }
}
