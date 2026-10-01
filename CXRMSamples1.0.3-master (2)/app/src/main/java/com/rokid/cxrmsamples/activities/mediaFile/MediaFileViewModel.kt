package com.rokid.cxrmsamples.activities.mediaFile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.UnsyncNumResultCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.managers.GlobalWifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ConnectionStatus{
    CONNECTED,
    CONNECTING,
    DISCONNECTED
}

class MediaFileViewModel: ViewModel() {

    private val TAG = "MediaFileViewModel"
    private val _connected: MutableStateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connected = _connected.asStateFlow()

    private val _audioNumber: MutableStateFlow<Int> = MutableStateFlow(0)
    val audioNumber = _audioNumber.asStateFlow()
    private val _pictureNumber: MutableStateFlow<Int> = MutableStateFlow(0)
    val pictureNumber = _pictureNumber.asStateFlow()
    private val _videoNumber: MutableStateFlow<Int> = MutableStateFlow(0)
    val videoNumber = _videoNumber.asStateFlow()

    private val _syncing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    init {
        viewModelScope.launch {
            GlobalWifiManager.getInstance().wifiStatus.collect { status ->
                _connected.value = when (status) {
                    GlobalWifiManager.WifiStatus.CONNECTED -> ConnectionStatus.CONNECTED
                    GlobalWifiManager.WifiStatus.CONNECTING -> ConnectionStatus.CONNECTING
                    GlobalWifiManager.WifiStatus.DISCONNECTED -> ConnectionStatus.DISCONNECTED
                }
            }
        }
    }

    fun connect(){
        GlobalWifiManager.getInstance().connectWifi()
    }

    fun disconnect(){
        Log.w(TAG, "忽略断开请求，避免拆掉全局眼镜直连")
    }

    fun setMediaFilesUpdateListener(){
        Log.d(TAG, "媒体更新监听由 GlobalWifiManager 统一持有")
    }

    fun getUnsyncNum(){
        CxrApi.getInstance().getUnsyncNum(
            UnsyncNumResultCallback { status, audioNum, pictureNum, videoNum ->
                if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED){
                    _audioNumber.value = audioNum
                    _pictureNumber.value = pictureNum
                    _videoNumber.value = videoNum
                }
            }
        )
    }

    fun startSync(mediaType: Array<ValueUtil.CxrMediaType>){
        GlobalWifiManager.getInstance().requestImmediateVideoSync("media_file_page")
        _syncing.value = true
    }

    fun stopSync(){
        CxrApi.getInstance().stopSync()
        _syncing.value = false
    }
}
