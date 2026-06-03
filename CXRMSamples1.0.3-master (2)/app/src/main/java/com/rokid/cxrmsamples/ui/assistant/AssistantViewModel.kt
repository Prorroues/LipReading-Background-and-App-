package com.rokid.cxrmsamples.ui.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxrmsamples.managers.GlobalWifiManager
import com.rokid.cxrmsamples.services.assistant.VoiceAssistantManager
import com.rokid.cxrmsamples.activities.deviceInformation.DeviceInformationViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val voiceAssistant = VoiceAssistantManager.getInstance(application)
    private val wifiManager = GlobalWifiManager.getInstance()
    private val deviceInfoViewModel = DeviceInformationViewModel()
    
    private val _deviceName = MutableStateFlow("Glasses_未连接")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()
    
    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()
    
    private val _isCharging = MutableStateFlow<Boolean?>(null)
    val isCharging: StateFlow<Boolean?> = _isCharging.asStateFlow()
    
    private val _wifiStatus = MutableStateFlow(false)
    val wifiStatus: StateFlow<Boolean> = _wifiStatus.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    val conversationHistory: StateFlow<List<com.rokid.cxrmsamples.models.Message>> = 
        voiceAssistant.conversationHistory
    
    private val _currentStatus = MutableStateFlow("")
    val currentStatus: StateFlow<String> = _currentStatus.asStateFlow()
    
    init {
        // Observe device info
        viewModelScope.launch {
            deviceInfoViewModel.batteryLevel.collect { level ->
                _batteryLevel.value = level
            }
        }
        
        viewModelScope.launch {
            deviceInfoViewModel.isCharging.collect { charging ->
                _isCharging.value = charging
            }
        }
        
        viewModelScope.launch {
            deviceInfoViewModel.deviceName.collect { name ->
                _deviceName.value = name ?: "Glasses_未连接"
            }
        }
        
        // Observe WiFi status
        viewModelScope.launch {
            wifiManager.wifiStatus.collect { status ->
                _wifiStatus.value = status == GlobalWifiManager.WifiStatus.CONNECTED
            }
        }
        
        // ⭐ 同时监听 GlobalWifiManager 和 CxrApi 的连接状态
        viewModelScope.launch {
            wifiManager.bluetoothStatus.collect { status ->
                val wifiConnected = status == GlobalWifiManager.BluetoothStatus.CONNECTED
                val apiConnected = try {
                    CxrApi.getInstance().isBluetoothConnected
                } catch (e: Exception) {
                    false
                }
                _isConnected.value = wifiConnected || apiConnected
            }
        }
        
        // ⭐ 定期检查 CxrApi 连接状态（作为补充）
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000) // 每2秒检查一次
                try {
                    val apiConnected = CxrApi.getInstance().isBluetoothConnected
                    if (apiConnected != _isConnected.value) {
                        _isConnected.value = apiConnected
                    }
                } catch (e: Exception) {
                    if (_isConnected.value) {
                        _isConnected.value = false
                    }
                }
            }
        }
        
        viewModelScope.launch {
            wifiManager.deviceName.collect { name ->
                _deviceName.value = name
            }
        }
        
        // Observe assistant status
        viewModelScope.launch {
            combine(
                voiceAssistant.state,
                voiceAssistant.statusText
            ) { state, statusText ->
                when (state) {
                    VoiceAssistantManager.AssistantState.LISTENING -> "正在聆听..."
                    VoiceAssistantManager.AssistantState.THINKING -> "正在思考..."
                    VoiceAssistantManager.AssistantState.SPEAKING -> "正在播报..."
                    VoiceAssistantManager.AssistantState.CAMERA -> "正在处理图片..."
                    else -> statusText.ifEmpty { "" }
                }
            }.collect { status ->
                _currentStatus.value = status
            }
        }
        
        // Initialize device info
        deviceInfoViewModel.getDeviceInformation()
    }
}
