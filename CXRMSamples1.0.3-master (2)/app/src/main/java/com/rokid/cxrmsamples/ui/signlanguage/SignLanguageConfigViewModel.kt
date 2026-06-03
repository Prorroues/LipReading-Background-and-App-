package com.rokid.cxrmsamples.ui.signlanguage

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.rokid.cxrmsamples.utils.UploadUrlHelper

class SignLanguageConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPreferences = application.getSharedPreferences("sign_language_config", Context.MODE_PRIVATE)
    
    private val _resolution = MutableStateFlow(
        sharedPreferences.getString("sign_language_resolution", "1920x1080") ?: "1920x1080"
    )
    val resolution: StateFlow<String> = _resolution.asStateFlow()
    
    private val _duration = MutableStateFlow(
        sharedPreferences.getInt("sign_language_duration", 6)
    )
    val duration: StateFlow<Int> = _duration.asStateFlow()
    
    private val _uploadUrl = MutableStateFlow(
        sharedPreferences.getString("sign_language_server_url", "http://88bill99.top:25000") ?: "http://88bill99.top:25000"
    )
    val uploadUrl: StateFlow<String> = _uploadUrl.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()
    
    fun setResolution(res: String) {
        _resolution.value = res
    }
    
    fun setDuration(dur: Int) {
        if (dur in 3..30) {
            _duration.value = dur
        }
    }
    
    fun setUploadUrl(url: String) {
        _uploadUrl.value = url
    }
    
    fun save() {
        sharedPreferences.edit().apply {
            putString("sign_language_resolution", _resolution.value)
            putInt("sign_language_duration", _duration.value)
            putString("sign_language_server_url", _uploadUrl.value)
            apply()
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _statusMessage.value = "正在测试连接（不会上传测试视频）..."
            val result = UploadUrlHelper.probeUploadEndpoint(_uploadUrl.value)
            _statusMessage.value = if (result.success) {
                "连接测试成功\n${result.message}"
            } else {
                "连接测试失败\n${result.message}"
            }
        }
    }
}
