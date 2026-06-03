package com.rokid.cxrmsamples.activities.main

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.dataBeans.CONSTANT
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager
import com.rokid.cxrmsamples.managers.GlobalVoiceRecognitionManager
import com.rokid.cxrmsamples.ui.pairing.PairingActivity
import com.rokid.cxrmsamples.ui.main.MainScreenActivity
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private lateinit var bluetoothManager: BluetoothManager

    private val openBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {result ->
        if (result.resultCode == RESULT_OK){// 打开了蓝牙
            viewModel.checkBluetoothEnabled(bluetoothManager.adapter)
        }
    }

    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.checkPermission(this, bluetoothManager.adapter)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        viewModel.checkPermission(this, bluetoothManager.adapter)
        
        // 检查配对状态
        checkPairingStatus()
        
        setContent {
            CXRMSamplesTheme {
                MainScreen(viewModel, onButtonClick = {
                    when (viewModel.bluetoothState.value){
                        BluetoothState.PERMISSION_REQUIRED -> {
                            Log.e("MainActivity", "permission required")
                            viewModel.requestBluetoothPermission(requestBluetoothPermission)
                        }
                        BluetoothState.BLUETOOTH_DISABLED -> {
                            viewModel.requestBluetoothEnable(openBluetoothLauncher)
                        }
                        BluetoothState.BLUETOOTH_READY -> {
                            viewModel.toInit(this)
                        }
                    }
                })
            }
        }
    }
    
    private fun checkPairingStatus() {
        // 检查 SharedPreferences 中是否有配对记录
        val sharedPreferences = getSharedPreferences("record", Context.MODE_PRIVATE)
        val recordName = sharedPreferences.getString("record_name", null)
        val recordUUID = sharedPreferences.getString("record_uuid", null)
        val recordMacAddress = sharedPreferences.getString("record_mac_address", null)
        
        // 检查实时蓝牙连接状态
        val isBluetoothConnected = try {
            CxrApi.getInstance().isBluetoothConnected
        } catch (e: Exception) {
            false
        }
        
        val hasPairedRecord = recordName != null && recordUUID != null && recordMacAddress != null
        
        // ⭐ 检查是否是第一次启动（通过检查是否有已初始化标记）
        val isFirstLaunch = sharedPreferences.getBoolean("app_initialized", false).not()
        
        if (isFirstLaunch && !hasPairedRecord && !isBluetoothConnected) {
            // 第一次启动且没有配对记录，跳转到配对界面
            Log.d("MainActivity", "第一次启动，未配对，跳转到配对界面")
            sharedPreferences.edit().putBoolean("app_initialized", true).apply()
            startActivity(Intent(this, PairingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            } else {
            // ⭐ 非第一次启动，直接进入主界面，在后台自动连接
            Log.d("MainActivity", "直接进入主界面，后台自动连接")
            
            // 标记已初始化
            if (isFirstLaunch) {
                sharedPreferences.edit().putBoolean("app_initialized", true).apply()
            }
            
            // 跳转到主界面
            startActivity(Intent(this, MainScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            
            // 在后台自动连接（如果有配对记录且未连接）
            if (hasPairedRecord && !isBluetoothConnected) {
                lifecycleScope.launch {
                    delay(1000) // 延迟1秒让主界面先加载
                    autoConnect(recordUUID!!, recordMacAddress!!)
                }
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun autoConnect(uuid: String, macAddress: String) {
        Log.d("MainActivity", "开始自动连接: uuid=$uuid, macAddress=$macAddress")
        
        val connectionCallback = object : BluetoothStatusCallback {
            override fun onConnectionInfo(
                infoUuid: String?,
                infoMacAddress: String?,
                p2: String?,
                p3: Int
            ) {
                Log.d("MainActivity", "onConnectionInfo: uuid=$infoUuid, macAddress=$infoMacAddress")
                if (!CxrApi.getInstance().isBluetoothConnected) {
                    Log.d("MainActivity", "设备未连接，尝试连接")
                    connectBTSocket(infoUuid ?: uuid, infoMacAddress ?: macAddress, this)
                } else {
                    Log.d("MainActivity", "设备已连接")
                }
            }

            override fun onConnected() {
                Log.d("MainActivity", "蓝牙设备连接成功（后台自动连接）")
                // 初始化全局管理器
                GlobalCustomViewManager.getInstance().initialize(this@MainActivity)
                
                // 初始化语音识别配置
                val prefs = getSharedPreferences("voice_service_config", Context.MODE_PRIVATE)
                val appKey = prefs.getString("app_key", "") ?: ""
                val accessKeyId = prefs.getString("access_key_id", "") ?: ""
                val accessKeySecret = prefs.getString("access_key_secret", "") ?: ""

                if (appKey.isNotEmpty() && accessKeyId.isNotEmpty() && accessKeySecret.isNotEmpty()) {
                    GlobalVoiceRecognitionManager.getInstance().configure(appKey, accessKeyId, accessKeySecret)
                    Log.d("MainActivity", "全局语音识别配置已初始化")
                }
                // ⭐ 不再跳转，主界面已经在显示
            }

            override fun onDisconnected() {
                Log.d("MainActivity", "蓝牙设备断开连接，后台自动重连")
                // ⭐ 断开后在后台自动重连
                val callback = this
                lifecycleScope.launch {
                    delay(3000) // 等待3秒后重试
                    if (!CxrApi.getInstance().isBluetoothConnected) {
                        Log.d("MainActivity", "后台尝试重新连接")
                        connectBTSocket(uuid, macAddress, callback)
                    }
                }
            }

            override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                Log.e("MainActivity", "蓝牙连接失败: $errorCode，后台自动重试")
                // ⭐ 连接失败，在后台继续重试，不跳转到配对界面
                val callback = this
                lifecycleScope.launch {
                    delay(5000) // 等待5秒后重试
                    if (!CxrApi.getInstance().isBluetoothConnected) {
                        Log.d("MainActivity", "后台重试连接")
                        connectBTSocket(uuid, macAddress, callback)
                    }
                }
            }
        }
        
        // 初始化蓝牙并连接
        try {
            // 先尝试直接连接
            connectBTSocket(uuid, macAddress, connectionCallback)
        } catch (e: Exception) {
            Log.e("MainActivity", "自动连接异常: ${e.message}", e)
            // ⭐ 连接失败不跳转，继续在后台重试（由 onFailed 处理）
        }
    }
    
    private fun connectBTSocket(uuid: String, macAddress: String, callback: BluetoothStatusCallback) {
        Log.d("MainActivity", "连接蓝牙: uuid=$uuid, macAddress=$macAddress")
        val rawFileBytes = readRawFile()
        CxrApi.getInstance().connectBluetooth(
            this,
            uuid,
            macAddress,
            callback,
            rawFileBytes,
            CONSTANT.CLIENT_SECRET.replace("-", "")
        )
    }
    
    private fun readRawFile(): ByteArray {
        val inputStream = resources.openRawResource(R.raw.sn)
        return inputStream.readBytes()
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel(), onButtonClick:()->Unit) {
    val state = viewModel.bluetoothState.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        Image(// background
            painter = painterResource(id = R.drawable.glasses_bg),
            contentDescription = null,
            alpha = 0.3f,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(// app name
                text = when (state.value) {
                    BluetoothState.PERMISSION_REQUIRED -> stringResource(R.string.bluetooth_permission_request)
                    BluetoothState.BLUETOOTH_DISABLED -> stringResource(R.string.bluetooth_closed)
                    BluetoothState.BLUETOOTH_READY -> stringResource(R.string.ready)
                },
                textAlign = TextAlign.Start,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(// welcome
                text = stringResource(R.string.hello_rokid),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Button(onClick = onButtonClick) {// button
                Text(text = when(state.value){
                    BluetoothState.PERMISSION_REQUIRED -> stringResource(R.string.button_request_permission)
                    BluetoothState.BLUETOOTH_DISABLED -> stringResource(R.string.button_open_bluetooth)
                    BluetoothState.BLUETOOTH_READY -> stringResource(R.string.button_to_init)
                })
            }
        }

    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CXRMSamplesTheme {
        MainScreen(viewModel { MainViewModel() }, onButtonClick = {})
    }
}