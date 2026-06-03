package com.rokid.cxrmsamples.ui.pairing

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rokid.cxrmsamples.dataBeans.CONSTANT
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager
import com.rokid.cxrmsamples.managers.GlobalVoiceRecognitionManager
import com.rokid.cxrmsamples.ui.main.MainScreenActivity
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class PairingActivity : ComponentActivity() {
    private val viewModel: PairingViewModel by viewModels()
    private lateinit var btManager: BluetoothManager
    private var lastAutoScanAt = 0L
    
    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startAutoScan()
        } else {
            Toast.makeText(this, "需要蓝牙权限才能扫描设备", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        btManager = getSystemService(BluetoothManager::class.java)
        
        // 检查并请求权限
        checkAndRequestPermissions()
        
        setContent {
            CXRMSamplesTheme {
                val toConnect by viewModel.toConnect.collectAsState()
                
                LaunchedEffect(toConnect) {
                    if (toConnect) {
                        viewModel.connectBTSocket(this@PairingActivity)
                        viewModel.toConnect.value = false
                    }
                }
                
                PairingScreen(
                    viewModel = viewModel,
                    onScan = {
                        if (hasBluetoothPermissions()) {
                            viewModel.handleScan(btManager.adapter.bluetoothLeScanner)
                        } else {
                            requestBluetoothPermission.launch(getBluetoothPermissions())
                            Toast.makeText(this@PairingActivity, "需要蓝牙权限才能扫描", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDeviceClick = { deviceItem ->
                        viewModel.handleScan(btManager.adapter.bluetoothLeScanner)
                        viewModel.deviceClicked(this@PairingActivity, deviceItem)
                    },
                    onClear = {
                        viewModel.clearDevices()
                    },
                    onReconnect = {
                        viewModel.connectBTSocket(this@PairingActivity)
                    },
                    onDisconnect = {
                        viewModel.disconnect()
                    },
                    onConnected = {
                        viewModel.record(this@PairingActivity)
                        GlobalCustomViewManager.getInstance().initialize(this@PairingActivity)
                        
                        val prefs = getSharedPreferences("voice_service_config", Context.MODE_PRIVATE)
                        val appKey = prefs.getString("app_key", "") ?: ""
                        val accessKeyId = prefs.getString("access_key_id", "") ?: ""
                        val accessKeySecret = prefs.getString("access_key_secret", "") ?: ""
                        
                        if (appKey.isNotEmpty() && accessKeyId.isNotEmpty() && accessKeySecret.isNotEmpty()) {
                            GlobalVoiceRecognitionManager.getInstance().configure(appKey, accessKeyId, accessKeySecret)
                            android.util.Log.d("PairingActivity", "全局语音识别配置已初始化")
                        }
                        
                        // 跳转到主界面
                        startActivity(Intent(this@PairingActivity, MainScreenActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                )
            }
        }
        
        viewModel.checkRecordState(this)
        viewModel.checkConnection()
        startAutoScan()
        startStatusPolling()
        startAutoReconnect()
    }
    
    private fun checkAndRequestPermissions() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermission.launch(getBluetoothPermissions())
        }
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        val permissions = getBluetoothPermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun getBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要新的权限
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            // Android 11 及以下使用旧权限
            CONSTANT.BLUETOOTH_PERMISSIONS
        }
    }

    private fun startAutoScan() {
        if (!hasBluetoothPermissions()) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAutoScanAt < 3000L) {
            return
        }
        if (!viewModel.isScanningState.value) {
            lastAutoScanAt = now
            viewModel.handleScan(btManager.adapter.bluetoothLeScanner)
        }
    }

    private fun startStatusPolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.refreshGlassInfo()
                    delay(5000)
                }
            }
        }
    }

    private fun startAutoReconnect() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (!viewModel.connected.value && !viewModel.isScanningState.value) {
                        startAutoScan()
                    }
                    delay(5000)
                }
            }
        }
    }
}
