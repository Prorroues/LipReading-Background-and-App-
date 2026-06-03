package com.rokid.cxrmsamples.ui.deviceinfo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxrmsamples.activities.deviceInformation.DeviceInformationViewModel
import com.rokid.cxrmsamples.utils.MediaPathProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class DeviceInfoItem(
    val label: String,
    val value: String
)

class DeviceInfoViewModel(application: Application) : AndroidViewModel(application) {
    private val deviceInfoViewModel = DeviceInformationViewModel()
    
    private val _deviceInfoItems = MutableStateFlow<List<DeviceInfoItem>>(emptyList())
    val deviceInfoItems: StateFlow<List<DeviceInfoItem>> = _deviceInfoItems.asStateFlow()
    
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    
    init {
        // Observe all device info - collect each flow separately and combine manually
        viewModelScope.launch {
            var name: String? = null
            var id: String? = null
            var version: String? = null
            var battery: Int = -1
            var charging: Boolean? = null
            var brightness: Int = -1
            var volume: Int = -1
            var wearing: String? = null
            var screenOn: Boolean? = null
            
            fun updateItems() {
                val mediaPath = MediaPathProvider.getRootPath(getApplication())
                _deviceInfoItems.value = buildList {
                    add(DeviceInfoItem("设备名称", name ?: "未知"))
                    add(DeviceInfoItem("设备ID", id ?: "未知"))
                    add(DeviceInfoItem("系统版本", version ?: "未知"))
                    add(DeviceInfoItem("电量", if (battery >= 0) "$battery%" else "未知"))
                    add(DeviceInfoItem("充电状态", when (charging) {
                        true -> "充电中"
                        false -> "未充电"
                        null -> "未知"
                    }))
                    add(DeviceInfoItem("亮度", if (brightness >= 0) "$brightness" else "未知"))
                    add(DeviceInfoItem("音量", if (volume >= 0) "$volume" else "未知"))
                    add(DeviceInfoItem("佩戴状态", wearing ?: "未知"))
                    add(DeviceInfoItem("屏幕状态", when {
                        screenOn == true -> "开启"
                        screenOn == false -> "关闭"
                        else -> "未知"
                    }))
                    add(DeviceInfoItem("媒体存储路径", mediaPath))
                }
            }
            
            launch {
                deviceInfoViewModel.deviceName.collect {
                    name = it
                    updateItems()
                }
            }
            launch {
                deviceInfoViewModel.deviceId.collect {
                    id = it
                    updateItems()
                }
            }
            launch {
                deviceInfoViewModel.systemVersion.collect {
                    version = it
                    updateItems()
                }
            }
            launch {
                deviceInfoViewModel.batteryLevel.collect {
                    battery = it
                    updateItems()
                }
            }
            launch {
                deviceInfoViewModel.isCharging.collect {
                    charging = it
                    updateItems()
                }
            }
            launch {
                deviceInfoViewModel.brightness.collect {
                    brightness = it
                    updateItems()
                }
            }
            launch {
                deviceInfoViewModel.soundVolume.collect {
                    volume = it
                    updateItems()
                }
            }
            launch {
                deviceInfoViewModel.wearingState.collect {
                    wearing = it
                    updateItems()
                }
            }
            launch {
                deviceInfoViewModel.isScreenOn.collect {
                    screenOn = it
                    updateItems()
                }
            }
        }
        
        // Initialize device info
        deviceInfoViewModel.getDeviceInformation()
        
        // Setup listeners for real-time updates
        setupListeners()
        
        // Auto refresh every 5 seconds
        startAutoRefresh()
    }
    
    /**
     * 设置监听器以实时更新设备信息
     */
    private fun setupListeners() {
        viewModelScope.launch {
            // 设置电池监听
            deviceInfoViewModel.toSetBatteryListener()
            // 设置亮度监听
            deviceInfoViewModel.toSetBrightnessListener()
            // 设置音量监听
            deviceInfoViewModel.toSetSoundVolumeListener()
            // 设置屏幕状态监听
            deviceInfoViewModel.toSetScreenListener()
        }
    }
    
    /**
     * 手动刷新设备信息
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            deviceInfoViewModel.getDeviceInformation()
            // 等待一小段时间后取消刷新状态
            kotlinx.coroutines.delay(500)
            _isRefreshing.value = false
        }
    }
    
    /**
     * 自动刷新设备信息（每5秒）
     */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000) // 5秒刷新一次
                deviceInfoViewModel.getDeviceInformation()
            }
        }
    }
}
