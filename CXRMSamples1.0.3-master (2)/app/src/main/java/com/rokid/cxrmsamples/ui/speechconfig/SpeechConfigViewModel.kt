package com.rokid.cxrmsamples.ui.speechconfig

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxrmsamples.managers.GlobalVoiceRecognitionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpeechConfigItem(
    val id: String,
    val name: String,
    val value: String,
    val placeholder: String
)

class SpeechConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPreferences = application.getSharedPreferences("voice_service_config", Context.MODE_PRIVATE)
    
    private val _configItems = MutableStateFlow(
        listOf(
            SpeechConfigItem(
                id = "app_key",
                name = "AppKey",
                value = sharedPreferences.getString("app_key", "") ?: "",
                placeholder = "项目 AppKey"
            ),
            SpeechConfigItem(
                id = "access_key_id",
                name = "AccessKey ID",
                value = sharedPreferences.getString("access_key_id", "") ?: "",
                placeholder = "LTAI..."
            ),
            SpeechConfigItem(
                id = "access_key_secret",
                name = "AccessKey Secret",
                value = sharedPreferences.getString("access_key_secret", "") ?: "",
                placeholder = "Secret..."
            )
        )
    )
    val configItems: StateFlow<List<SpeechConfigItem>> = _configItems.asStateFlow()
    
    private val _visibleKeys = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val visibleKeys: StateFlow<Map<String, Boolean>> = _visibleKeys.asStateFlow()
    
    fun setConfigValue(id: String, value: String) {
        _configItems.value = _configItems.value.map {
            if (it.id == id) {
                it.copy(value = value)
            } else {
                it
            }
        }
    }
    
    fun toggleVisibility(id: String) {
        val current = _visibleKeys.value.toMutableMap()
        current[id] = !(current[id] ?: false)
        _visibleKeys.value = current
    }
    
    fun save() {
        val appKey = _configItems.value.find { it.id == "app_key" }?.value ?: ""
        val accessKeyId = _configItems.value.find { it.id == "access_key_id" }?.value ?: ""
        val accessKeySecret = _configItems.value.find { it.id == "access_key_secret" }?.value ?: ""
        
        sharedPreferences.edit().apply {
            putString("app_key", appKey)
            putString("access_key_id", accessKeyId)
            putString("access_key_secret", accessKeySecret)
            apply()
        }
        
        // Update GlobalVoiceRecognitionManager
        if (appKey.isNotEmpty() && accessKeyId.isNotEmpty() && accessKeySecret.isNotEmpty()) {
            GlobalVoiceRecognitionManager.getInstance().configure(appKey, accessKeyId, accessKeySecret)
        }
    }
}
