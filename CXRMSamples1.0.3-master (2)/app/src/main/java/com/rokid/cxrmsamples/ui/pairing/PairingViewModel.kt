package com.rokid.cxrmsamples.ui.pairing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.GlassInfoResultCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.dataBeans.CONSTANT
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager
import com.rokid.cxrmsamples.managers.GlobalVoiceRecognitionManager
import com.rokid.cxrmsamples.managers.GlobalWifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DeviceItem(
    val device: BluetoothDevice?,
    val name: String,
    val macAddress: String,
    val rssi: Int
)

class PairingViewModel : ViewModel() {
    private val TAG = "PairingViewModel"

    private val _recordState: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val recordState: StateFlow<Boolean> = _recordState.asStateFlow()

    private val _isScanning: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isScanningState: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _devicesList: MutableStateFlow<List<DeviceItem>> = MutableStateFlow(emptyList())
    val devicesList: StateFlow<List<DeviceItem>> = _devicesList.asStateFlow()

    private val _recordName: MutableStateFlow<String?> = MutableStateFlow(null)
    val recordName: StateFlow<String?> = _recordName.asStateFlow()

    private val _recordUUID: MutableStateFlow<String?> = MutableStateFlow(null)
    val recordUUID: StateFlow<String?> = _recordUUID.asStateFlow()

    private val _recordMacAddress: MutableStateFlow<String?> = MutableStateFlow(null)
    val recordMacAddress: StateFlow<String?> = _recordMacAddress.asStateFlow()

    private val _batteryLevel: MutableStateFlow<Int> = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    val isCharging: StateFlow<Boolean?> = _isCharging.asStateFlow()

    private val _brightness: MutableStateFlow<Int> = MutableStateFlow(-1)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val _volume: MutableStateFlow<Int> = MutableStateFlow(-1)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _wearingStatus: MutableStateFlow<String?> = MutableStateFlow(null)
    val wearingStatus: StateFlow<String?> = _wearingStatus.asStateFlow()

    private val _connecting: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _connected: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val toConnect = MutableStateFlow(false)

    private val connectionState = object : BluetoothStatusCallback {
        override fun onConnectionInfo(uuid: String?, macAddress: String?, p2: String?, p3: Int) {
            Log.d(TAG, "onConnectionInfo: uuid=$uuid, macAddress=$macAddress")
            if (_recordUUID.value == (uuid ?: "error") && _recordMacAddress.value == (macAddress ?: "error")) {
                Log.d(TAG, "Device matches recorded info")
                if (!CxrApi.getInstance().isBluetoothConnected) {
                    Log.d(TAG, "Device not connected, posting toConnect")
                    toConnect.value = true
                } else {
                    Log.d(TAG, "Device already connected")
                    GlobalWifiManager.getInstance().updateDeviceInfo()
                }
            } else {
                Log.d(TAG, "New device info received")
                uuid?.let { u ->
                    macAddress?.let { m ->
                        Log.d(TAG, "Updating records and posting toConnect")
                        _recordUUID.value = u
                        _recordMacAddress.value = m
                        toConnect.value = true
                    }
                }
            }
        }

        override fun onConnected() {
            Log.d(TAG, "Bluetooth device connected successfully")
            _devicesList.value = emptyList()
            _connected.value = true
            _connecting.value = false
            GlobalWifiManager.getInstance().updateDeviceInfo()
        }

        override fun onDisconnected() {
            Log.d(TAG, "Bluetooth device disconnected")
            _connecting.value = false
            _connected.value = false
            GlobalWifiManager.getInstance().updateDeviceInfo()
        }

        override fun onFailed(p0: ValueUtil.CxrBluetoothErrorCode?) {
            Log.e(TAG, "Bluetooth connection failed with error: $p0")
            _connecting.value = false
            _connected.value = false
            GlobalWifiManager.getInstance().updateDeviceInfo()
        }
    }

    private val glassInfoCallback = GlassInfoResultCallback { status, glassInfo ->
        if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED) {
            glassInfo?.let { info ->
                _batteryLevel.value = info.batteryLevel
                _isCharging.value = info.isCharging
                _brightness.value = info.brightness
                _volume.value = info.volume
                _wearingStatus.value = info.wearingStatus
            }
        }
    }

    init {
        _recordState.value = false
    }

    private val bleScannerCallback: ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val name = device.name ?: "Unknown"
            val macAddress = device.address
            val rssi = result.rssi
            Log.d(TAG, "Found BLE device: name=$name, address=$macAddress, rssi=$rssi")
            addDevice(device, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun handleScan(bleScanner: BluetoothLeScanner?) {
        if (_isScanning.value) {
            Log.d(TAG, "Stopping BLE scan")
            bleScanner?.stopScan(bleScannerCallback)
            _isScanning.value = false
        } else {
            Log.d(TAG, "Starting BLE scan with service UUID: ${CONSTANT.SERVICE_UUID}")
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(CONSTANT.SERVICE_UUID)).build()
            val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            bleScanner?.startScan(mutableListOf(filter), scanSettings, bleScannerCallback)
            _isScanning.value = true
        }
    }

    fun checkRecordState(context: Context) {
        Log.d(TAG, "Checking record state")
        val sharedPreferences = context.getSharedPreferences("record", Context.MODE_PRIVATE)
        val recordName = sharedPreferences.getString("record_name", null)
        val recordUUID = sharedPreferences.getString("record_uuid", null)
        val recordMacAddress = sharedPreferences.getString("record_mac_address", null)
        recordName?.let { name ->
            recordUUID?.let { uuid ->
                recordMacAddress?.let { mac ->
                    Log.d(TAG, "Record found: name=$name, uuid=$uuid, mac=$mac")
                    this._recordName.value = name
                    this._recordUUID.value = uuid
                    this._recordMacAddress.value = mac
                    _recordState.value = true
                    _connecting.value = false
                    return
                }
            }
        }
        Log.d(TAG, "No record found")
        _recordState.value = false
    }

    fun record(context: Context) {
        Log.d(TAG, "Recording device info: name=${_recordName.value}, uuid=${_recordUUID.value}, mac=${_recordMacAddress.value}")
        val sharedPreferences = context.getSharedPreferences("record", Context.MODE_PRIVATE)
        sharedPreferences.edit {
            putString("record_name", _recordName.value)
            putString("record_uuid", _recordUUID.value)
            putString("record_mac_address", _recordMacAddress.value)
        }
        _recordState.value = true
    }

    @SuppressLint("MissingPermission")
    fun addDevice(device: BluetoothDevice, rssi: Int) {
        Log.d(TAG, "Adding device: name=${device.name ?: "Unknown"}, address=${device.address}, rssi=$rssi")
        val existingDevice = _devicesList.value.find { it.device == device }
        if (existingDevice != null) {
            Log.d(TAG, "Device already exists, updating RSSI")
            updateRssi(device, rssi)
        } else {
            val newDevice = DeviceItem(device, device.name ?: "Unknown", device.address, rssi)
            _devicesList.value = _devicesList.value + newDevice
            Log.d(TAG, "New device added to list, total devices: ${_devicesList.value.size}")
        }
    }

    fun updateRssi(device: BluetoothDevice, rssi: Int) {
        Log.d(TAG, "Updating RSSI for device ${device.address}: $rssi")
        _devicesList.value = _devicesList.value.map {
            if (it.device == device) it.copy(rssi = rssi) else it
        }
    }

    fun clearDevices() {
        Log.d(TAG, "Clearing device list")
        _devicesList.value = emptyList()
    }

    fun connectBTSocket(context: Context) {
        Log.d(TAG, "Reconnecting to device: uuid=${_recordUUID.value}, mac=${_recordMacAddress.value}")
        CxrApi.getInstance().connectBluetooth(
            context,
            _recordUUID.value ?: "error",
            _recordMacAddress.value ?: "error",
            connectionState,
            readRawFile(context),
            CONSTANT.CLIENT_SECRET.replace("-", "")
        )
    }

    fun deviceClicked(activity: Context, deviceItem: DeviceItem?) {
        deviceItem?.let {
            Log.d(TAG, "Device clicked: name=${it.name}, address=${it.macAddress}")
            _recordName.value = it.name
            CxrApi.getInstance().initBluetooth(activity, it.device, connectionState)
            _connecting.value = true
        }
    }

    fun readRawFile(context: Context): ByteArray {
        Log.d(TAG, "Reading raw file: sn.lc")
        val inputStream = context.resources.openRawResource(R.raw.sn)
        val bytes = inputStream.readBytes()
        Log.d(TAG, "Read ${bytes.size} bytes from raw file")
        return bytes
    }

    fun disconnect() {
        CxrApi.getInstance().deinitBluetooth()
        GlobalWifiManager.getInstance().updateDeviceInfo()
    }

    fun checkConnection() {
        _connected.value = CxrApi.getInstance().isBluetoothConnected
        GlobalWifiManager.getInstance().updateDeviceInfo()
    }

    fun refreshGlassInfo() {
        viewModelScope.launch {
            try {
                CxrApi.getInstance().getGlassInfo(glassInfoCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh glass info", e)
            }
        }
    }
}
