package com.rokid.cxrmsamples.managers

import android.content.Context
import android.util.Log
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.UnsyncNumResultCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.listeners.MediaFilesUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.utils.MediaPathProvider
import com.rokid.cxrmsamples.managers.ErrorReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全局WiFi连接管理器（单例）
 * 蓝牙连接后自动建立并维持 WiFi P2P，断线自动重连，周期性心跳保活
 */
class GlobalWifiManager private constructor(private val context: Context) {
    
    private val TAG = "GlobalWifiManager"
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // 使用SupervisorJob确保协程作用域在应用生命周期内有效
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    enum class BluetoothStatus {
        CONNECTED, DISCONNECTED, CONNECTING
    }
    
    enum class WifiStatus {
        CONNECTED, CONNECTING, DISCONNECTED
    }
    
    // 设备信息
    private val _deviceName = MutableStateFlow("Glasses_未连接")
    val deviceName = _deviceName.asStateFlow()
    
    // 蓝牙状态
    private val _bluetoothStatus = MutableStateFlow(BluetoothStatus.DISCONNECTED)
    val bluetoothStatus = _bluetoothStatus.asStateFlow()
    
    // WiFi状态
    private val _wifiStatus = MutableStateFlow(WifiStatus.DISCONNECTED)
    val wifiStatus = _wifiStatus.asStateFlow()
    
    // WiFi保活心跳
    private var keepAliveJob: Job? = null
    private val keepAliveIntervalMs = 15000L  // 每15秒心跳一次（不考虑续航）
    
    // 自动视频同步
    private var isVideoSyncEnabled = false
    private var isVideoSyncing = false
    
    // 媒体文件更新监听器（WiFi 已连接时自动同步视频）
    private val mediaFilesUpdateListener = MediaFilesUpdateListener {
        Log.d(TAG, "检测到新媒体文件，尝试自动同步视频")
        if (shouldKeepWifiAlive() && _wifiStatus.value == WifiStatus.CONNECTED && !isVideoSyncing) {
            scope.launch {
                delay(MEDIA_FILE_SETTLE_MS)
                autoSyncVideos()
            }
        }
    }
    
    // 视频同步回调
    private val videoSyncCallback = object : SyncStatusCallback {
        override fun onSyncStart() {
            Log.d(TAG, "自动视频同步开始")
            isVideoSyncing = true
        }
        
        override fun onSingleFileSynced(fileName: String?) {
            Log.d(TAG, "视频文件已同步: $fileName")
            fileName?.let {
                // 处理文件名：提取文件名部分（去除路径）
                val cleanFileName = if (it.contains("/")) {
                    java.io.File(it).name
                } else {
                    it
                }
                
                // 检查是否是视频文件
                val lowerFileName = cleanFileName.lowercase()
                val isVideoFile = lowerFileName.endsWith(".mp4") || 
                                 lowerFileName.endsWith(".mov") || 
                                 lowerFileName.endsWith(".avi") || 
                                 lowerFileName.endsWith(".mkv") ||
                                 lowerFileName.endsWith(".webm") ||
                                 lowerFileName.endsWith(".m4v")
                
                if (isVideoFile) {
                    // 优先使用系统返回的完整路径，否则在同步目录中查找
                    val fullPath = resolveSyncedVideoPath(it, cleanFileName)
                    if (fullPath != null) {
                        GlobalVideoSyncQueue.addVideo(fullPath)
                        Log.d(TAG, "视频已添加到同步队列: $fullPath")
                    } else {
                        ErrorReporter.report(
                            source = TAG,
                            stage = "onSingleFileSynced",
                            message = "同步后未找到实际文件路径",
                            detail = "fileName=$it"
                        )
                    }
                } else {
                    Log.d(TAG, "跳过非视频文件: $cleanFileName")
                }
            }
        }
        
        override fun onSyncFailed() {
            Log.e(TAG, "自动视频同步失败")
            ErrorReporter.report(
                source = TAG,
                stage = "autoSyncVideos",
                message = "自动视频同步失败"
            )
            isVideoSyncing = false
        }
        
        override fun onSyncFinished() {
            Log.d(TAG, "自动视频同步完成")
            isVideoSyncing = false
        }
    }

    private fun resolveSyncedVideoPath(rawName: String, cleanFileName: String): String? {
        if (rawName.contains("/")) {
            val rawFile = java.io.File(rawName)
            if (rawFile.exists()) return rawFile.absolutePath
        }
        val syncPath = MediaPathProvider.getRootPath(context)
        val direct = java.io.File(syncPath, cleanFileName)
        if (direct.exists()) return direct.absolutePath
        return try {
            java.io.File(syncPath)
                .walkTopDown()
                .firstOrNull { it.isFile && it.name == cleanFileName }
                ?.absolutePath
        } catch (e: Exception) {
            ErrorReporter.report(
                source = TAG,
                stage = "resolveSyncedVideoPath",
                message = "查找同步文件失败",
                detail = e.message
            )
            null
        }
    }
    
    // WiFi P2P回调
    private val wifiP2PCallback = object : WifiP2PStatusCallback {
        override fun onConnected() {
            Log.d(TAG, "全局WiFi连接成功")
            _wifiStatus.value = WifiStatus.CONNECTED
            startKeepAlive()
            // WiFi连接成功后，启用自动视频同步并检查未同步视频
            enableAutoVideoSync()
        }
        
        override fun onDisconnected() {
            Log.d(TAG, "全局WiFi断开连接")
            _wifiStatus.value = WifiStatus.DISCONNECTED
            stopKeepAlive()
            disableAutoVideoSync()
            scheduleReconnectIfNeeded("onDisconnected")
        }
        
        override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
            Log.e(TAG, "全局WiFi连接失败: $errorCode")
            ErrorReporter.report(
                source = TAG,
                stage = "wifiP2P",
                message = "WiFi连接失败",
                detail = errorCode?.name
            )
            _wifiStatus.value = WifiStatus.DISCONNECTED
            stopKeepAlive()
            disableAutoVideoSync()
            scheduleReconnectIfNeeded("onFailed")
        }
    }
    
    companion object {
        private const val PREFS_NAME = "global_wifi_manager"
        private const val KEY_KEEP_ALIVE_ENABLED = "wifi_keep_alive_enabled"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MONITOR_INTERVAL_MS = 5000L
        private const val MEDIA_FILE_SETTLE_MS = 400L
        private const val WIFI_STABLE_BEFORE_SYNC_MS = 500L
        
        @Volatile
        private var instance: GlobalWifiManager? = null
        
        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = GlobalWifiManager(context.applicationContext)
                    }
                }
            }
        }
        
        fun getInstance(): GlobalWifiManager {
            return instance ?: throw IllegalStateException("GlobalWifiManager must be initialized first")
        }
    }
    
    /**
     * 初始化设备信息（从CXR API获取）
     */
    fun updateDeviceInfo() {
        try {
            val cxrApi = CxrApi.getInstance()
            
            // 检查蓝牙状态
            if (cxrApi.isBluetoothConnected) {
                _bluetoothStatus.value = BluetoothStatus.CONNECTED
                
                // 设置设备名称（可后续通过API获取实际设备名）
                _deviceName.value = "Glasses_已连接"
                
                Log.d(TAG, "设备信息已更新：蓝牙已连接")
            } else {
                _bluetoothStatus.value = BluetoothStatus.DISCONNECTED
                _deviceName.value = "Glasses_未连接"
                if (_wifiStatus.value != WifiStatus.DISCONNECTED) {
                    Log.d(TAG, "蓝牙已断开，关闭 WiFi P2P")
                    disconnectWifi()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新设备信息失败", e)
            _deviceName.value = "Glasses_错误"
        }
    }
    
    /**
     * 连接WiFi P2P
     */
    fun connectWifi() {
        try {
            // 检查蓝牙是否已连接
            if (!CxrApi.getInstance().isBluetoothConnected) {
                Log.w(TAG, "蓝牙未连接，无法连接WiFi")
                return
            }
            
            if (_wifiStatus.value == WifiStatus.DISCONNECTED) {
                Log.d(TAG, "开始连接全局WiFi...")
                _wifiStatus.value = WifiStatus.CONNECTING
                CxrApi.getInstance().initWifiP2P(wifiP2PCallback)
            } else {
                Log.d(TAG, "WiFi已在连接中或已连接")
            }
        } catch (e: Exception) {
            Log.e(TAG, "连接WiFi失败", e)
            _wifiStatus.value = WifiStatus.DISCONNECTED
        }
    }
    
    /**
     * 断开WiFi P2P
     */
    fun disconnectWifi() {
        try {
            Log.d(TAG, "断开全局WiFi...")
            stopKeepAlive()
            CxrApi.getInstance().deinitWifiP2P()
            _wifiStatus.value = WifiStatus.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "断开WiFi失败", e)
        }
    }
    
    /**
     * 启动WiFi保活心跳：定期发送查询保持连接
     */
    private fun startKeepAlive() {
        stopKeepAlive()
        keepAliveJob = scope.launch {
            Log.d(TAG, "WiFi保活心跳已启动（全局），间隔: ${keepAliveIntervalMs}ms")
            while (true) {
                delay(keepAliveIntervalMs)
                
                if (_wifiStatus.value == WifiStatus.CONNECTED) {
                    try {
                        // 发送轻量级查询保持连接活跃
                        CxrApi.getInstance().getUnsyncNum { _, _, _, _ ->
                            // 心跳成功
                        }
                        Log.v(TAG, "WiFi心跳发送成功")
                    } catch (e: Exception) {
                        Log.w(TAG, "WiFi心跳失败: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * 停止WiFi保活心跳
     */
    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        Log.d(TAG, "WiFi保活心跳已停止（全局）")
    }
    
    private var reconnectJob: Job? = null
    
    /**
     * 是否应维持 WiFi P2P（全局保活开启且用户未手动关闭）
     */
    fun shouldKeepWifiAlive(): Boolean = _isWifiEnabled.value
    
    private fun scheduleReconnectIfNeeded(source: String) {
        if (!shouldKeepWifiAlive()) return
        if (_bluetoothStatus.value != BluetoothStatus.CONNECTED) return
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (shouldKeepWifiAlive() &&
                _bluetoothStatus.value == BluetoothStatus.CONNECTED &&
                _wifiStatus.value == WifiStatus.DISCONNECTED
            ) {
                Log.d(TAG, "[$source] 全局保活：${RECONNECT_DELAY_MS}ms 后自动重连 WiFi P2P")
                connectWifi()
            }
        }
    }
    
    /**
     * 根据蓝牙状态维持 WiFi P2P 连接（全局保活核心逻辑）
     */
    private fun ensureWifiKeepAlive(reason: String) {
        if (!shouldKeepWifiAlive()) {
            if (_wifiStatus.value != WifiStatus.DISCONNECTED) {
                Log.d(TAG, "[$reason] 保活已关闭，断开 WiFi P2P")
                disconnectWifi()
            }
            return
        }
        
        when {
            _bluetoothStatus.value == BluetoothStatus.CONNECTED &&
                _wifiStatus.value == WifiStatus.DISCONNECTED -> {
                Log.d(TAG, "[$reason] 全局保活：蓝牙已连接，建立 WiFi P2P")
                connectWifi()
            }
            _bluetoothStatus.value == BluetoothStatus.DISCONNECTED &&
                _wifiStatus.value != WifiStatus.DISCONNECTED -> {
                Log.d(TAG, "[$reason] 蓝牙未连接，释放 WiFi P2P")
                disconnectWifi()
            }
        }
    }
    
    // WiFi 保活开关（默认开启；关闭后不再自动连接/重连）
    private val _isWifiEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_KEEP_ALIVE_ENABLED, true)
    )
    val isWifiEnabled = _isWifiEnabled.asStateFlow()
    
    /**
     * 监控蓝牙和 WiFi 状态，周期性执行全局保活
     */
    fun startMonitoring() {
        scope.launch {
            updateDeviceInfo()
            ensureWifiKeepAlive("startMonitoring")
            while (true) {
                delay(MONITOR_INTERVAL_MS)
                updateDeviceInfo()
                ensureWifiKeepAlive("monitor")
            }
        }
    }
    
    /**
     * 设置 WiFi 全局保活开关（持久化）
     */
    fun setWifiEnabled(enabled: Boolean) {
        if (_isWifiEnabled.value == enabled) return
        _isWifiEnabled.value = enabled
        prefs.edit().putBoolean(KEY_KEEP_ALIVE_ENABLED, enabled).apply()
        reconnectJob?.cancel()
        reconnectJob = null
        if (enabled) {
            Log.d(TAG, "已开启 WiFi 全局保活")
            ensureWifiKeepAlive("setWifiEnabled")
        } else {
            Log.d(TAG, "已关闭 WiFi 全局保活")
            disconnectWifi()
        }
    }
    
    /**
     * 启用自动视频同步（WiFi连接成功时调用）
     */
    private fun enableAutoVideoSync() {
        if (!isVideoSyncEnabled) {
            isVideoSyncEnabled = true
            CxrApi.getInstance().setMediaFilesUpdateListener(mediaFilesUpdateListener)
            Log.d(TAG, "自动视频同步已启用")
            // 连接成功后立即检查一次未同步视频
            scope.launch {
                delay(WIFI_STABLE_BEFORE_SYNC_MS)
                autoSyncVideos()
            }
        }
    }

    /**
     * 录像结束等场景：尽快触发一次视频同步（需 WiFi 已连接或正在连接）
     */
    fun requestImmediateVideoSync(reason: String) {
        if (!shouldKeepWifiAlive()) return
        scope.launch {
            Log.d(TAG, "[$reason] 主动请求视频同步")
            if (_wifiStatus.value == WifiStatus.DISCONNECTED &&
                _bluetoothStatus.value == BluetoothStatus.CONNECTED
            ) {
                connectWifi()
                var wait = 0
                while (_wifiStatus.value != WifiStatus.CONNECTED && wait < 20) {
                    delay(250)
                    wait++
                }
            }
            if (_wifiStatus.value == WifiStatus.CONNECTED) {
                delay(MEDIA_FILE_SETTLE_MS)
                autoSyncVideos()
            } else {
                Log.w(TAG, "[$reason] WiFi 未连接，跳过立即同步")
            }
        }
    }
    
    /**
     * 禁用自动视频同步（WiFi断开时调用）
     */
    private fun disableAutoVideoSync() {
        if (isVideoSyncEnabled) {
            isVideoSyncEnabled = false
            CxrApi.getInstance().setMediaFilesUpdateListener(null)
            Log.d(TAG, "自动视频同步已禁用")
        }
    }
    
    /**
     * 自动同步视频（检查未同步数量并同步）
     */
    private fun autoSyncVideos() {
        if (isVideoSyncing || !isVideoSyncEnabled) {
            return
        }
        
        scope.launch {
            try {
                Log.d(TAG, "开始检查未同步视频...")
                val deferred = CompletableDeferred<Int>()
                
                val callback = UnsyncNumResultCallback { status, audioNum, pictureNum, videoNum ->
                    if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED) {
                        Log.d(TAG, "未同步视频数量: $videoNum")
                        deferred.complete(videoNum)
                    } else {
                        deferred.complete(0)
                    }
                }
                
                CxrApi.getInstance().getUnsyncNum(callback)
                val videoNum = withTimeout(5000) { deferred.await() }
                
                if (videoNum > 0) {
                    Log.d(TAG, "检测到 $videoNum 个未同步视频，开始自动同步...")
                    val syncPath = MediaPathProvider.getRootPath(context)
                    val syncDir = java.io.File(syncPath)
                    if (!syncDir.exists()) {
                        syncDir.mkdirs()
                    }
                    
                    CxrApi.getInstance().startSync(
                        syncPath,
                        arrayOf(ValueUtil.CxrMediaType.VIDEO),
                        videoSyncCallback
                    )
                } else {
                    Log.d(TAG, "没有未同步的视频")
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动同步视频失败", e)
                ErrorReporter.report(
                    source = TAG,
                    stage = "autoSyncVideos",
                    message = "自动同步视频异常",
                    detail = e.message
                )
                isVideoSyncing = false
            }
        }
    }
}
