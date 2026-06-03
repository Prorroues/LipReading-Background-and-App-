package com.rokid.cxrmsamples.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxrmsamples.activities.deviceInformation.DeviceInformationViewModel
import com.rokid.cxrmsamples.managers.GlobalWifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
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

    private val _isWifiEnabled = MutableStateFlow(false)
    val isWifiEnabled: StateFlow<Boolean> = _isWifiEnabled.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    init {
        refreshBluetoothConnectionState()

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

        viewModelScope.launch {
            wifiManager.wifiStatus.collect { status ->
                _wifiStatus.value = status == GlobalWifiManager.WifiStatus.CONNECTED
            }
        }

        viewModelScope.launch {
            wifiManager.isWifiEnabled.collect { enabled ->
                _isWifiEnabled.value = enabled
            }
        }

        viewModelScope.launch {
            wifiManager.bluetoothStatus.collect { status ->
                _isConnected.value = status == GlobalWifiManager.BluetoothStatus.CONNECTED
            }
        }

        viewModelScope.launch {
            wifiManager.deviceName.collect { name ->
                _deviceName.value = name
            }
        }

        deviceInfoViewModel.getDeviceInformation()
    }

    fun toggleWifi(enabled: Boolean) {
        wifiManager.setWifiEnabled(enabled)
        _isWifiEnabled.value = enabled
    }

    fun toggleRecording() {
        _isRecording.value = !_isRecording.value
        if (_isRecording.value) {
            try {
                CxrApi.getInstance().openAudioRecord(1, "home_recording")
            } catch (e: Exception) {
                _isRecording.value = false
            }
        } else {
            try {
                CxrApi.getInstance().closeAudioRecord("home_recording")
            } catch (_: Exception) {
            }
        }
    }

    fun refreshStatus() {
        refreshBluetoothConnectionState()
        deviceInfoViewModel.getDeviceInformation()
        wifiManager.updateDeviceInfo()
        com.rokid.cxrmsamples.managers.GlobalCustomViewManager.getInstance().refreshScreen()
    }

    private fun refreshBluetoothConnectionState() {
        val connected = CxrApi.getInstance().isBluetoothConnected
        _isConnected.value = connected
        if (!connected) {
            _deviceName.value = "Glasses_未连接"
        }
    }
}
