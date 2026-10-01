package com.rokid.cxrmsamples.managers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.UnsyncNumResultCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.listeners.MediaFilesUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.utils.GalleryPublisher
import com.rokid.cxrmsamples.utils.MediaPathProvider
import com.rokid.cxrmsamples.utils.SyncedVideoFinder
import com.rokid.cxrmsamples.managers.ErrorReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    
    // 连接回调走主线程；文件同步和目录扫描不要占 UI
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
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

    private val _statusMessage = MutableStateFlow("未连接")
    val statusMessage = _statusMessage.asStateFlow()

    private var connectTimeoutJob: Job? = null
    private var connectAttemptJob: Job? = null
    
    // WiFi保活心跳
    private var keepAliveJob: Job? = null
    private val keepAliveIntervalMs = 45_000L
    private var autoReconnectAttempts = 0
    private var lastP2pInitAtMs = 0L
    private var p2pInited = false
    @Volatile
    private var ignoreP2pCallbacks = false
    private var bluetoothMisses = 0
    
    // 自动视频同步
    private var isVideoSyncEnabled = false
    private var isVideoSyncing = false
    private var pendingResync = false
    private var syncCheckJob: Job? = null
    private var syncWatchdogJob: Job? = null
    private var lastSyncStartedAtMs = 0L
    
    // 媒体文件更新监听器（WiFi 已连接时自动同步视频）
    private val mediaFilesUpdateListener = MediaFilesUpdateListener {
        // 连上后眼镜会连续上报文件，这里只记日志。真正拉视频只在停录后 requestImmediateVideoSync。
        Log.d(TAG, "检测到新媒体文件（不自动全量同步，避免把界面卡死）")
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
                val cleanFileName = if (it.contains("/")) {
                    java.io.File(it).name
                } else {
                    it
                }
                if (!SyncedVideoFinder.isVideoFileName(cleanFileName)) {
                    Log.d(TAG, "跳过非视频文件: $cleanFileName")
                    return@let
                }
                scope.launch(Dispatchers.IO) {
                    val fullPath = waitForSyncedFileOnDisk(it, cleanFileName)
                    if (fullPath != null) {
                        GlobalVideoSyncQueue.addVideo(fullPath)
                        Log.d(TAG, "视频已添加到同步队列: $fullPath")
                        GalleryPublisher.publishVideo(context, java.io.File(fullPath))
                    } else {
                        ErrorReporter.report(
                            source = TAG,
                            stage = "onSingleFileSynced",
                            message = "同步后未找到实际文件路径",
                            detail = "fileName=$it"
                        )
                    }
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
            finishVideoSync(retryPending = true)
        }
        
        override fun onSyncFinished() {
            Log.d(TAG, "自动视频同步完成")
            finishVideoSync(retryPending = true)
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
            if (ignoreP2pCallbacks) {
                Log.d(TAG, "忽略 onConnected（正在 deinit）")
                return
            }
            Log.d(TAG, "全局WiFi连接成功")
            connectTimeoutJob?.cancel()
            reconnectJob?.cancel()
            autoReconnectAttempts = 0
            _wifiStatus.value = WifiStatus.CONNECTED
            _statusMessage.value = "眼镜直连已接通"
            startKeepAlive()
            enableAutoVideoSync()
        }
        
        override fun onDisconnected() {
            if (ignoreP2pCallbacks) {
                Log.d(TAG, "忽略 onDisconnected（正在 deinit）")
                return
            }
            // 握手过程中的断开多半是上次 deinit 迟到回调，不能拆掉正在进行的 init
            if (_wifiStatus.value == WifiStatus.CONNECTING) {
                Log.d(TAG, "握手中收到 onDisconnected，忽略")
                return
            }
            Log.d(TAG, "全局WiFi断开连接")
            p2pInited = false
            _wifiStatus.value = WifiStatus.DISCONNECTED
            _statusMessage.value = "眼镜直连已断开"
            stopKeepAlive()
            disableAutoVideoSync()
            scheduleReconnectIfNeeded("onDisconnected")
        }
        
        override fun onP2pDeviceAvailable(name: String?, address: String?, info: String?) {
            if (ignoreP2pCallbacks) return
            if (_wifiStatus.value == WifiStatus.CONNECTED) return
            Log.d(TAG, "发现眼镜 P2P: name=$name address=$address info=$info")
            _statusMessage.value = "已发现眼镜${name?.let { " $it" } ?: ""}，正在握手，请稍等…"
        }

        override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
            if (ignoreP2pCallbacks) {
                Log.d(TAG, "忽略 onFailed（正在 deinit）: $errorCode")
                return
            }
            Log.e(TAG, "全局WiFi连接失败: $errorCode")
            connectTimeoutJob?.cancel()
            p2pInited = false
            _wifiStatus.value = WifiStatus.DISCONNECTED
            _statusMessage.value = describeWifiError(errorCode)
            stopKeepAlive()
            disableAutoVideoSync()
            scheduleReconnectIfNeeded("onFailed")
        }
    }
    
    companion object {
        private const val PREFS_NAME = "global_wifi_manager"
        private const val KEY_KEEP_ALIVE_ENABLED = "wifi_keep_alive_enabled"
        private const val RECONNECT_DELAY_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 90_000L
        private const val MONITOR_INTERVAL_MS = 12_000L
        private const val MIN_INIT_GAP_MS = 8_000L
        private const val MAX_AUTO_RECONNECT = 8
        private const val DEINIT_SETTLE_MS = 2_500L
        private const val CONFLICT_SSID = "HaoYu"
        private const val MEDIA_FILE_SETTLE_MS = 120L
        private const val WIFI_STABLE_BEFORE_SYNC_MS = 80L
        private const val UNSYNC_RETRY_COUNT = 20
        private const val UNSYNC_RETRY_DELAY_MS = 400L
        private const val UNSYNC_QUERY_TIMEOUT_MS = 800L
        private const val SYNC_STUCK_MS = 18_000L
        private const val PENDING_RESYNC_DELAY_MS = 400L
        
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
                bluetoothMisses = 0
                _bluetoothStatus.value = BluetoothStatus.CONNECTED
                _deviceName.value = "Glasses_已连接"
                Log.d(TAG, "设备信息已更新：蓝牙已连接")
            } else {
                bluetoothMisses++
                _deviceName.value = "Glasses_未连接"
                // 蓝牙状态会抖一下；连上的直连很稳，不要一次误判就拆 P2P
                if (bluetoothMisses >= 3) {
                    _bluetoothStatus.value = BluetoothStatus.DISCONNECTED
                    if (_wifiStatus.value != WifiStatus.DISCONNECTED) {
                        Log.d(TAG, "蓝牙连续未连接，关闭 WiFi P2P")
                        disconnectWifi()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新设备信息失败", e)
            _deviceName.value = "Glasses_错误"
        }
    }
    
    /**
     * 连接WiFi P2P。连上之后不要再 deinit；force 只用于未连接时重新发起握手。
     */
    fun connectWifi(force: Boolean = false) {
        if (_wifiStatus.value == WifiStatus.CONNECTED) {
            _statusMessage.value = "眼镜直连已接通"
            return
        }
        if (_wifiStatus.value == WifiStatus.CONNECTING) {
            _statusMessage.value = "正在连接眼镜直连，请稍等…"
            Log.d(TAG, "直连握手中，不打断 force=$force")
            return
        }
        if (force) {
            autoReconnectAttempts = 0
        }
        connectAttemptJob?.cancel()
        connectAttemptJob = scope.launch {
            startWifiP2PInternal()
        }
    }

    private suspend fun startWifiP2PInternal() {
        try {
            if (!CxrApi.getInstance().isBluetoothConnected) {
                _bluetoothStatus.value = BluetoothStatus.DISCONNECTED
                _statusMessage.value = "请先配对眼镜，蓝牙通了才能直连"
                Log.w(TAG, "蓝牙未连接，无法连接WiFi")
                return
            }
            bluetoothMisses = 0
            _bluetoothStatus.value = BluetoothStatus.CONNECTED

            val blockReason = p2pBlockReason()
            if (blockReason != null) {
                _statusMessage.value = blockReason
                Log.w(TAG, blockReason)
                return
            }

            val warn = p2pWarnings()
            if (warn.isNotEmpty()) {
                Log.w(TAG, warn)
            }

            if (_wifiStatus.value == WifiStatus.CONNECTED) {
                _statusMessage.value = "眼镜直连已接通"
                return
            }
            if (_wifiStatus.value == WifiStatus.CONNECTING) {
                return
            }

            prepareWifiRadio()
            dropConflictingStaIfNeeded()

            val elapsed = System.currentTimeMillis() - lastP2pInitAtMs
            if (lastP2pInitAtMs > 0L && elapsed < MIN_INIT_GAP_MS) {
                delay(MIN_INIT_GAP_MS - elapsed)
                if (_wifiStatus.value == WifiStatus.CONNECTED) {
                    _statusMessage.value = "眼镜直连已接通"
                    return
                }
            }

            _wifiStatus.value = WifiStatus.CONNECTING
            _statusMessage.value = buildString {
                append("正在连接眼镜直连，请靠近眼镜并稍等…")
                val warn = p2pWarnings()
                if (warn.isNotEmpty()) {
                    append('\n')
                    append(warn)
                }
            }

            // 第一次只 init。已经 init 失败后再 deinit，并且吞掉 deinit 回调。
            if (p2pInited) {
                ignoreP2pCallbacks = true
                connectTimeoutJob?.cancel()
                safeDeinitP2p()
                delay(DEINIT_SETTLE_MS)
                ignoreP2pCallbacks = false
                p2pInited = false
            }

            Log.d(TAG, "开始连接全局WiFi firstInit=${!p2pInited}")
            lastP2pInitAtMs = System.currentTimeMillis()
            p2pInited = true
            // 1.0.3 的 initWifiP2P 握手约 4 秒就 WIFI_CONNECT_FAILED；1.0.8+ 的 P2P2 超时约 30 秒。
            try {
                CxrApi.getInstance().initWifiP2P2(true, wifiP2PCallback)
            } catch (e: Throwable) {
                Log.w(TAG, "initWifiP2P2 不可用，回退 initWifiP2P: ${e.message}")
                CxrApi.getInstance().initWifiP2P(wifiP2PCallback)
            }
            watchConnectTimeout()
        } catch (e: Exception) {
            Log.e(TAG, "连接WiFi失败", e)
            ignoreP2pCallbacks = false
            _wifiStatus.value = WifiStatus.DISCONNECTED
            _statusMessage.value = "直连失败: ${e.message}"
        }
    }

    private fun safeDeinitP2p() {
        try {
            CxrApi.getInstance().deinitWifiP2P()
        } catch (e: Exception) {
            Log.w(TAG, "deinitWifiP2P: ${e.message}")
        }
    }

    /**
     * 断开WiFi P2P
     */
    fun disconnectWifi() {
        try {
            Log.d(TAG, "断开全局WiFi...")
            connectTimeoutJob?.cancel()
            connectAttemptJob?.cancel()
            reconnectJob?.cancel()
            stopKeepAlive()
            ignoreP2pCallbacks = true
            safeDeinitP2p()
            ignoreP2pCallbacks = false
            p2pInited = false
            _wifiStatus.value = WifiStatus.DISCONNECTED
            _statusMessage.value = "已关闭眼镜直连"
        } catch (e: Exception) {
            ignoreP2pCallbacks = false
            Log.e(TAG, "断开WiFi失败", e)
        }
    }
    
    private fun watchConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_wifiStatus.value == WifiStatus.CONNECTING) {
                Log.w(TAG, "直连等待超时")
                ignoreP2pCallbacks = true
                safeDeinitP2p()
                ignoreP2pCallbacks = false
                p2pInited = false
                _wifiStatus.value = WifiStatus.DISCONNECTED
                _statusMessage.value = "直连超时，正在重试…"
                scheduleReconnectIfNeeded("timeout")
            }
        }
    }

    private fun p2pBlockReason(): String? {
        val missing = missingP2pPermissions()
        if (missing.isNotEmpty()) {
            return "缺少权限：${missing.joinToString("、")}。请点「立即连接」并允许"
        }
        return null
    }

    private fun p2pWarnings(): String {
        val parts = mutableListOf<String>()
        if (isPhoneHotspotOn()) {
            parts.add("检测到手机热点，直连可能变慢，可先关掉热点")
        }
        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiMgr.isWifiEnabled) {
            parts.add("手机 WiFi 开关是关的，正在尝试打开")
        }
        return parts.joinToString("；")
    }

    @Suppress("DEPRECATION")
    private fun prepareWifiRadio() {
        try {
            val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiMgr.isWifiEnabled) {
                wifiMgr.isWifiEnabled = true
                Log.d(TAG, "已请求打开手机 WiFi 开关")
            }
        } catch (e: Exception) {
            Log.w(TAG, "打开 WiFi 开关失败: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? {
        return try {
            val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val raw = wifiMgr.connectionInfo?.ssid ?: return null
            val ssid = raw.trim().removeSurrounding("\"")
            if (ssid.isBlank() ||
                ssid.equals("<unknown ssid>", ignoreCase = true) ||
                ssid == "0x"
            ) {
                null
            } else {
                ssid
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun dropConflictingStaIfNeeded() {
        val ssid = currentSsid() ?: return
        if (!ssid.contains(CONFLICT_SSID, ignoreCase = true)) return
        try {
            val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiMgr.disconnect()
            _statusMessage.value = "已断开 $ssid。手机不要连电脑热点，WiFi 开关保持打开"
            Log.w(TAG, "断开冲突 WiFi $ssid，避免挡住眼镜直连")
            delay(1500)
        } catch (e: Exception) {
            Log.w(TAG, "断开冲突 WiFi 失败: ${e.message}")
        }
    }

    private fun isPhoneHotspotOn(): Boolean {
        return try {
            val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiMgr) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    fun missingP2pPermissions(): List<String> {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.map {
            when (it) {
                Manifest.permission.ACCESS_FINE_LOCATION -> "定位"
                Manifest.permission.NEARBY_WIFI_DEVICES -> "附近的设备"
                else -> it.substringAfterLast('.')
            }
        }
    }

    fun p2pPermissionArray(): Array<String> {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return needed.toTypedArray()
    }

    private fun describeWifiError(errorCode: ValueUtil.CxrWifiErrorCode?): String {
        val name = errorCode?.name.orEmpty()
        return when {
            name.contains("PERMISSION", ignoreCase = true) -> "直连失败：权限被拒绝"
            name.contains("TIMEOUT", ignoreCase = true) -> "直连超时，请靠近眼镜后点立即连接"
            name.contains("BUSY", ignoreCase = true) -> "直连忙，正在重试"
            name.isBlank() -> "直连失败，请靠近眼镜后点立即连接"
            else -> "直连失败：$name"
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
                if (_wifiStatus.value != WifiStatus.CONNECTED || isVideoSyncing) {
                    continue
                }
                try {
                    // 只做轻量探活，不要在心跳里 startSync 拉积压视频
                    CxrApi.getInstance().getUnsyncNum { _, _, _, _ -> }
                } catch (e: Exception) {
                    Log.w(TAG, "WiFi心跳失败: ${e.message}")
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
        if (_wifiStatus.value == WifiStatus.CONNECTING) return
        if (connectAttemptJob?.isActive == true) return
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "[$source] 已有重连在等待，不重置定时器")
            return
        }
        if (autoReconnectAttempts >= MAX_AUTO_RECONNECT) {
            _statusMessage.value = "直连多次未成功。请关掉手机热点、断开 HaoYu，靠近眼镜后点「立即连接」"
            Log.w(TAG, "[$source] 已达自动重连上限 $MAX_AUTO_RECONNECT")
            return
        }

        autoReconnectAttempts++
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!shouldKeepWifiAlive() ||
                _bluetoothStatus.value != BluetoothStatus.CONNECTED ||
                _wifiStatus.value != WifiStatus.DISCONNECTED
            ) {
                return@launch
            }
            Log.d(TAG, "[$source] 自动重连 ${autoReconnectAttempts}/$MAX_AUTO_RECONNECT")
            _statusMessage.value = "正在重试眼镜直连（${autoReconnectAttempts}/$MAX_AUTO_RECONNECT）…"
            connectWifi(force = false)
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
            _wifiStatus.value == WifiStatus.CONNECTED ||
                _wifiStatus.value == WifiStatus.CONNECTING -> {
                // 连上就保持，握手中也不要后台再踢一脚
            }
            _bluetoothStatus.value == BluetoothStatus.CONNECTED &&
                _wifiStatus.value == WifiStatus.DISCONNECTED -> {
                Log.d(TAG, "[$reason] 蓝牙已连接但直连断开，走限次重连")
                scheduleReconnectIfNeeded(reason)
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
            connectWifi(force = true)
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
            Log.d(TAG, "自动视频同步已启用（仅停录后拉取，不在连上时全量同步）")
        }
    }

    /**
     * 录像开始时预热 WiFi 直连，避免停录后再等连接。
     */
    fun prepareForRecording() {
        if (!CxrApi.getInstance().isBluetoothConnected) return
        _bluetoothStatus.value = BluetoothStatus.CONNECTED
        if (syncLooksStuck()) {
            Log.w(TAG, "开始录像前发现同步标志卡住，先复位")
            isVideoSyncing = false
            syncWatchdogJob?.cancel()
        }
        if (_wifiStatus.value == WifiStatus.DISCONNECTED) {
            connectWifi()
        }
        if (_wifiStatus.value == WifiStatus.CONNECTED && !isVideoSyncEnabled) {
            enableAutoVideoSync()
        }
    }

    /**
     * 录像结束等场景：尽快触发一次视频同步。
     */
    fun requestImmediateVideoSync(reason: String) {
        ioScope.launch {
            Log.d(TAG, "[$reason] 主动请求视频同步")
            if (_wifiStatus.value == WifiStatus.CONNECTING) {
                var wait = 0
                while (_wifiStatus.value == WifiStatus.CONNECTING && wait < 200) {
                    delay(100)
                    wait++
                }
            } else if (_wifiStatus.value == WifiStatus.DISCONNECTED &&
                CxrApi.getInstance().isBluetoothConnected
            ) {
                _bluetoothStatus.value = BluetoothStatus.CONNECTED
                withContext(Dispatchers.Main) {
                    connectWifi(force = false)
                }
                var wait = 0
                while (_wifiStatus.value != WifiStatus.CONNECTED && wait < 200) {
                    delay(100)
                    wait++
                }
            }
            if (_wifiStatus.value != WifiStatus.CONNECTED) {
                Log.w(TAG, "[$reason] 眼镜直连未接通，无法从眼镜拷视频")
                return@launch
            }
            if (!isVideoSyncEnabled) {
                withContext(Dispatchers.Main) {
                    enableAutoVideoSync()
                }
            }
            val fromRecording = reason.contains("recording") ||
                reason.contains("record_end") ||
                reason == "lip_reading"
            val forceStart = fromRecording ||
                reason == "wait-start" ||
                reason == "wait-poll"
            if (fromRecording) {
                delay(800)
            }
            autoSyncVideos(forceStart = forceStart)
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
    private fun autoSyncVideos(forceStart: Boolean = false) {
        if (!isVideoSyncEnabled) {
            return
        }
        if (syncLooksStuck()) {
            Log.w(TAG, "同步状态卡住（${System.currentTimeMillis() - lastSyncStartedAtMs}ms），强制复位")
            isVideoSyncing = false
            syncCheckJob?.cancel()
            syncWatchdogJob?.cancel()
        }
        if (forceStart && syncCheckJob?.isActive == true && !isVideoSyncing) {
            syncCheckJob?.cancel()
        }
        if (isVideoSyncing || syncCheckJob?.isActive == true) {
            pendingResync = true
            Log.d(TAG, "同步进行中，结束后再补一次")
            return
        }

        syncCheckJob = ioScope.launch {
            try {
                if (!forceStart) {
                    var videoNum = queryUnsyncVideoNum()
                    var retry = 0
                    while (videoNum <= 0 && retry < 3) {
                        retry++
                        delay(UNSYNC_RETRY_DELAY_MS)
                        videoNum = queryUnsyncVideoNum()
                    }
                    if (videoNum <= 0) {
                        Log.d(TAG, "没有未同步的视频")
                        return@launch
                    }
                    Log.d(TAG, "检测到 $videoNum 个未同步视频，开始自动同步...")
                } else {
                    Log.d(TAG, "录像结束，直接开始同步")
                }
                startVideoSyncTransfer()
            } catch (e: Exception) {
                Log.e(TAG, "自动同步视频失败", e)
                ErrorReporter.report(
                    source = TAG,
                    stage = "autoSyncVideos",
                    message = "自动同步视频异常",
                    detail = e.message
                )
                finishVideoSync(retryPending = false)
            }
        }
    }

    private fun startVideoSyncTransfer() {
        val syncPath = MediaPathProvider.getRootPath(context)
        val syncDir = java.io.File(syncPath)
        if (!syncDir.exists()) {
            syncDir.mkdirs()
        }
        isVideoSyncing = true
        lastSyncStartedAtMs = System.currentTimeMillis()
        armSyncWatchdog()
        try {
            CxrApi.getInstance().startSync(
                syncPath,
                arrayOf(ValueUtil.CxrMediaType.VIDEO),
                videoSyncCallback
            )
        } catch (e: Exception) {
            Log.e(TAG, "startSync 调用失败", e)
            finishVideoSync(retryPending = true)
        }
    }

    private fun syncLooksStuck(): Boolean {
        if (!isVideoSyncing) return false
        if (lastSyncStartedAtMs <= 0L) return true
        return System.currentTimeMillis() - lastSyncStartedAtMs > SYNC_STUCK_MS
    }

    private fun armSyncWatchdog() {
        syncWatchdogJob?.cancel()
        syncWatchdogJob = ioScope.launch {
            delay(SYNC_STUCK_MS)
            if (!isVideoSyncing) return@launch
            Log.w(TAG, "startSync 超时未结束，复位以便下一轮录像能继续拉文件")
            pendingResync = true
            finishVideoSync(retryPending = true)
        }
    }

    private fun finishVideoSync(retryPending: Boolean) {
        syncWatchdogJob?.cancel()
        isVideoSyncing = false
        val needAgain = retryPending && pendingResync
        pendingResync = false
        if (needAgain) {
            ioScope.launch {
                delay(PENDING_RESYNC_DELAY_MS)
                autoSyncVideos(forceStart = true)
            }
        }
    }

    private suspend fun queryUnsyncVideoNum(): Int {
        return try {
            val deferred = CompletableDeferred<Int>()
            val callback = UnsyncNumResultCallback { status, _, _, videoNum ->
                if (!deferred.isCompleted) {
                    if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED) {
                        Log.d(TAG, "未同步视频数量: $videoNum")
                        deferred.complete(videoNum)
                    } else {
                        deferred.complete(0)
                    }
                }
            }
            CxrApi.getInstance().getUnsyncNum(callback)
            withTimeout(UNSYNC_QUERY_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.w(TAG, "查询未同步数量失败: ${e.message}")
            0
        }
    }

    private suspend fun waitForSyncedFileOnDisk(rawName: String, cleanFileName: String): String? {
        repeat(12) {
            val path = resolveSyncedVideoPath(rawName, cleanFileName)
            if (path != null) {
                val file = java.io.File(path)
                if (file.exists() && file.length() >= 2 * 1024L) {
                    delay(80)
                    if (file.exists() && file.length() >= 2 * 1024L) {
                        return file.absolutePath
                    }
                }
            }
            delay(80)
        }
        return resolveSyncedVideoPath(rawName, cleanFileName)
    }
}
