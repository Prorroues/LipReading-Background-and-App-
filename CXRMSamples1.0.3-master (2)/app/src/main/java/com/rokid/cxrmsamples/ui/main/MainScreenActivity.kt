package com.rokid.cxrmsamples.ui.main

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.dataBeans.CONSTANT
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager
import com.rokid.cxrmsamples.managers.GlobalVoiceRecognitionManager
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainScreenActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val TAG = "MainScreenActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // ⭐ 启动时检查蓝牙连接状态，如果未连接则自动连接
        checkAndAutoConnect()
        
        setContent {
            CXRMSamplesTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
    
    private fun checkAndAutoConnect() {
        lifecycleScope.launch {
            delay(500) // 稍微延迟确保初始化完成
            
            try {
                val isConnected = CxrApi.getInstance().isBluetoothConnected
                if (!isConnected) {
                    Log.d(TAG, "主界面启动，检测到蓝牙未连接，开始自动连接")
                    val sharedPreferences = getSharedPreferences("record", Context.MODE_PRIVATE)
                    val recordUUID = sharedPreferences.getString("record_uuid", null)
                    val recordMacAddress = sharedPreferences.getString("record_mac_address", null)
                    
                    if (recordUUID != null && recordMacAddress != null) {
                        autoConnect(recordUUID, recordMacAddress)
                    } else {
                        Log.d(TAG, "没有配对记录，无法自动连接")
                    }
                } else {
                    Log.d(TAG, "蓝牙已连接，初始化全局管理器")
                    ensureManagersInitialized()
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查蓝牙连接状态失败", e)
            }
        }
    }

    private fun ensureManagersInitialized() {
        // 初始化全局管理器（用于唤醒后触发聆听）
        GlobalCustomViewManager.getInstance().initialize(this@MainScreenActivity)
        
        // 初始化语音识别配置
        val prefs = getSharedPreferences("voice_service_config", Context.MODE_PRIVATE)
        val appKey = prefs.getString("app_key", "") ?: ""
        val accessKeyId = prefs.getString("access_key_id", "") ?: ""
        val accessKeySecret = prefs.getString("access_key_secret", "") ?: ""
        
        if (appKey.isNotEmpty() && accessKeyId.isNotEmpty() && accessKeySecret.isNotEmpty()) {
            GlobalVoiceRecognitionManager.getInstance().configure(appKey, accessKeyId, accessKeySecret)
            Log.d(TAG, "全局语音识别配置已初始化")
        } else {
            Log.d(TAG, "语音识别配置为空，等待用户配置")
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun autoConnect(uuid: String, macAddress: String) {
        Log.d(TAG, "开始自动连接: uuid=$uuid, macAddress=$macAddress")
        
        val connectionCallback = object : BluetoothStatusCallback {
            override fun onConnectionInfo(
                infoUuid: String?,
                infoMacAddress: String?,
                p2: String?,
                p3: Int
            ) {
                Log.d(TAG, "onConnectionInfo: uuid=$infoUuid, macAddress=$infoMacAddress")
                if (!CxrApi.getInstance().isBluetoothConnected) {
                    Log.d(TAG, "设备未连接，尝试连接")
                    connectBTSocket(infoUuid ?: uuid, infoMacAddress ?: macAddress, this)
                }
            }

            override fun onConnected() {
                Log.d(TAG, "蓝牙设备连接成功（主界面后台自动连接）")
                ensureManagersInitialized()
            }

            override fun onDisconnected() {
                Log.d(TAG, "蓝牙设备断开连接，后台自动重连")
                val callback = this
                lifecycleScope.launch {
                    delay(3000)
                    if (!CxrApi.getInstance().isBluetoothConnected) {
                        Log.d(TAG, "后台尝试重新连接")
                        connectBTSocket(uuid, macAddress, callback)
                    }
                }
            }

            override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                Log.e(TAG, "蓝牙连接失败: $errorCode，后台自动重试")
                val callback = this
                lifecycleScope.launch {
                    delay(5000)
                    if (!CxrApi.getInstance().isBluetoothConnected) {
                        Log.d(TAG, "后台重试连接")
                        connectBTSocket(uuid, macAddress, callback)
                    }
                }
            }
        }
        
        try {
            connectBTSocket(uuid, macAddress, connectionCallback)
        } catch (e: Exception) {
            Log.e(TAG, "自动连接异常: ${e.message}", e)
        }
    }
    
    private fun connectBTSocket(uuid: String, macAddress: String, callback: BluetoothStatusCallback) {
        Log.d(TAG, "连接蓝牙: uuid=$uuid, macAddress=$macAddress")
        val rawFileBytes = resources.openRawResource(com.rokid.cxrmsamples.R.raw.sn).readBytes()
        CxrApi.getInstance().connectBluetooth(
            this,
            uuid,
            macAddress,
            callback,
            rawFileBytes,
            CONSTANT.CLIENT_SECRET.replace("-", "")
        )
    }
}
