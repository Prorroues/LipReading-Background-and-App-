package com.rokid.cxrmsamples.ui.mine

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MineViewModel(application: Application) : AndroidViewModel(application) {
    private val _navigateTo = MutableStateFlow<String?>(null)
    val navigateTo: StateFlow<String?> = _navigateTo.asStateFlow()
    
    fun onSettingsClick(settingId: String) {
        _navigateTo.value = settingId
    }
    
    fun clearNavigation() {
        _navigateTo.value = null
    }
}
