package com.rokid.cxrmsamples.ui.main

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.rokid.cxrmsamples.ui.components.Tab

class MainViewModel : ViewModel() {
    private val _currentTab = MutableStateFlow(Tab.HOME)
    val currentTab: StateFlow<Tab> = _currentTab.asStateFlow()

    fun setCurrentTab(tab: Tab) {
        _currentTab.value = tab
    }
}
