package com.rokid.cxrmsamples.ui.llmconfig

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxrmsamples.models.LLMConfig
import com.rokid.cxrmsamples.models.LLMProvider
import com.rokid.cxrmsamples.services.assistant.VoiceAssistantManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LLMProviderItem(
    val provider: LLMProvider,
    val displayName: String
)

data class ApiKeyItem(
    val id: String,
    val name: String,
    val key: String,
    val placeholder: String
)

class LLMConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPreferences = application.getSharedPreferences("llm_config", Context.MODE_PRIVATE)
    
    private val _providers = MutableStateFlow(
        LLMProvider.values().map { LLMProviderItem(it, it.displayName) }
    )
    val providers: StateFlow<List<LLMProviderItem>> = _providers.asStateFlow()
    
    private val _selectedProvider = MutableStateFlow(
        LLMProvider.fromName(sharedPreferences.getString("selected_provider", LLMProvider.QWEN.name) ?: LLMProvider.QWEN.name)
    )
    val selectedProvider: StateFlow<LLMProvider> = _selectedProvider.asStateFlow()
    
    private val _apiKeys = MutableStateFlow(
        LLMProvider.values().map { provider ->
            ApiKeyItem(
                id = provider.name,
                name = provider.displayName,
                key = sharedPreferences.getString("api_key_${provider.name}", "") ?: "",
                placeholder = when (provider) {
                    LLMProvider.OPENAI -> "sk-..."
                    LLMProvider.QWEN -> "sk-..."
                    LLMProvider.DEEPSEEK -> "sk-..."
                    LLMProvider.ZHIPU -> "sk-..."
                    LLMProvider.MOONSHOT -> "sk-..."
                    LLMProvider.BAIDU -> "Access Key..."
                    else -> "API Key..."
                }
            )
        }
    )
    val apiKeys: StateFlow<List<ApiKeyItem>> = _apiKeys.asStateFlow()
    
    private val _visibleKeys = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val visibleKeys: StateFlow<Map<String, Boolean>> = _visibleKeys.asStateFlow()
    
    fun selectProvider(provider: LLMProvider) {
        _selectedProvider.value = provider
    }
    
    fun setApiKey(providerId: String, key: String) {
        _apiKeys.value = _apiKeys.value.map {
            if (it.id == providerId) {
                it.copy(key = key)
            } else {
                it
            }
        }
    }
    
    fun toggleVisibility(providerId: String) {
        val current = _visibleKeys.value.toMutableMap()
        current[providerId] = !(current[providerId] ?: false)
        _visibleKeys.value = current
    }
    
    fun save() {
        val selected = _selectedProvider.value
        val apiKey = _apiKeys.value.find { it.id == selected.name }?.key ?: ""
        
        sharedPreferences.edit().apply {
            putString("selected_provider", selected.name)
            _apiKeys.value.forEach { item ->
                putString("api_key_${item.id}", item.key)
            }
            apply()
        }
        
        // Update VoiceAssistantManager
        viewModelScope.launch {
            val nlsPrefs = getApplication<Application>().getSharedPreferences("voice_service_config", Context.MODE_PRIVATE)
            val nlsAppKey = nlsPrefs.getString("app_key", "") ?: ""
            val nlsAccessKeyId = nlsPrefs.getString("access_key_id", "") ?: ""
            val nlsAccessKeySecret = nlsPrefs.getString("access_key_secret", "") ?: ""
            
            if (nlsAppKey.isNotEmpty() && nlsAccessKeyId.isNotEmpty() && nlsAccessKeySecret.isNotEmpty()) {
                val config = LLMConfig(
                    provider = selected,
                    apiKey = apiKey
                )
                VoiceAssistantManager.getInstance(getApplication()).configureLLM(
                    config = config,
                    nlsAppKey = nlsAppKey,
                    nlsAccessKeyId = nlsAccessKeyId,
                    nlsAccessKeySecret = nlsAccessKeySecret
                )
            }
        }
    }
}
