package com.rokid.cxrmsamples.ui.lipreading

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.rokid.cxrmsamples.services.assistant.VoiceAssistantManager
import com.rokid.cxrmsamples.utils.MediaPathProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LipReadingConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPreferences = application.getSharedPreferences("ai_interaction_config", Context.MODE_PRIVATE)
    
    private val _resolution = MutableStateFlow(
        sharedPreferences.getString("lip_reading_resolution", "1920x1080") ?: "1920x1080"
    )
    val resolution: StateFlow<String> = _resolution.asStateFlow()
    
    private val _duration = MutableStateFlow(
        sharedPreferences.getInt("lip_reading_duration", 6)
    )
    val duration: StateFlow<Int> = _duration.asStateFlow()
    
    private val _uploadUrl = MutableStateFlow(
        sharedPreferences.getString("lip_reading_server_url", "http://88bill99.top:25000") ?: "http://88bill99.top:25000"
    )
    val uploadUrl: StateFlow<String> = _uploadUrl.asStateFlow()

    private val _localPath = MutableStateFlow(MediaPathProvider.getRootPath(application))
    val localPath: StateFlow<String> = _localPath.asStateFlow()
    
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
            putString("lip_reading_resolution", _resolution.value)
            putInt("lip_reading_duration", _duration.value)
            putString("lip_reading_server_url", _uploadUrl.value)
            apply()
        }
        
        // Update VoiceAssistantManager
        VoiceAssistantManager.getInstance(getApplication()).apply {
            setLipReadingDuration(_duration.value)
            setLipReadingServerUrl(_uploadUrl.value)
        }
    }
}
