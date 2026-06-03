package com.rokid.cxrmsamples.ui.main

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.ui.audio.AudioUsageScreen
import com.rokid.cxrmsamples.ui.assistant.AssistantScreen
import com.rokid.cxrmsamples.ui.common.PlaceholderScreen
import com.rokid.cxrmsamples.ui.components.BottomNavigationBar
import com.rokid.cxrmsamples.ui.components.Tab
import com.rokid.cxrmsamples.ui.customprotocol.CustomProtocolScreen
import com.rokid.cxrmsamples.ui.customview.CustomViewScreen
import com.rokid.cxrmsamples.ui.deviceinfo.DeviceInfoScreen
import com.rokid.cxrmsamples.ui.home.HomeScreen
import com.rokid.cxrmsamples.ui.gallery.GalleryScreen
import com.rokid.cxrmsamples.ui.lipreading.LipReadingScreen
import com.rokid.cxrmsamples.ui.llmconfig.LLMConfigScreen
import com.rokid.cxrmsamples.ui.lipreading.LipReadingConfigScreen
import com.rokid.cxrmsamples.ui.mediafile.MediaFileScreen
import com.rokid.cxrmsamples.ui.mine.MineScreen
import com.rokid.cxrmsamples.ui.pairing.PairingActivity
import com.rokid.cxrmsamples.ui.picture.PictureScreen
import com.rokid.cxrmsamples.ui.signlanguage.SignLanguageConfigScreen
import com.rokid.cxrmsamples.ui.speechconfig.SpeechConfigScreen
import com.rokid.cxrmsamples.ui.video.VideoRecordScreen
import com.rokid.cxrmsamples.ui.voice.VoiceServiceScreen
import com.rokid.cxrmsamples.ui.videoqueue.VideoQueueScreen

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val currentTab by viewModel.currentTab.collectAsState()
    var navigateTo: String? by remember { mutableStateOf(null) }
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            BottomNavigationBar(
                currentTab = currentTab,
                onTabSelected = { viewModel.setCurrentTab(it) }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                navigateTo == "llm_config" -> {
                    LLMConfigScreen(onBack = { navigateTo = null })
                }
                navigateTo == "speech_config" -> {
                    SpeechConfigScreen(onBack = { navigateTo = null })
                }
                navigateTo == "device_info" -> {
                    DeviceInfoScreen(onBack = { navigateTo = null })
                }
                navigateTo == "audio_usage" -> {
                    AudioUsageScreen(onBack = { navigateTo = null })
                }
                navigateTo == "video_record" -> {
                    VideoRecordScreen(onBack = { navigateTo = null })
                }
                navigateTo == "photo_capture" -> {
                    PictureScreen(onBack = { navigateTo = null })
                }
                navigateTo == "media_file" -> {
                    MediaFileScreen(onBack = { navigateTo = null })
                }
                navigateTo == "custom_protocol" -> {
                    CustomProtocolScreen(onBack = { navigateTo = null })
                }
                navigateTo == "voice_service" -> {
                    VoiceServiceScreen(onBack = { navigateTo = null })
                }
                navigateTo == "lip_reading" -> {
                    LipReadingScreen(onBack = { navigateTo = null })
                }
                navigateTo == "sign_language" -> {
                    SignLanguageConfigScreen(onBack = { navigateTo = null })
                }
                navigateTo == "translation" -> {
                    PlaceholderScreen(title = "翻译", onBack = { navigateTo = null })
                }
                navigateTo == "custom_view" -> {
                    CustomViewScreen(onBack = { navigateTo = null })
                }
                navigateTo == "video_queue" -> {
                    VideoQueueScreen(onBack = { navigateTo = null })
                }
                navigateTo == "lip_reading_config" -> {
                    LipReadingConfigScreen(onBack = { navigateTo = null })
                }
                currentTab == Tab.ASSISTANT -> AssistantScreen()
                currentTab == Tab.HOME -> HomeScreen(
                    onFeatureClick = { featureId ->
                        navigateTo = featureId
                    },
                    onSettingsClick = {
                        viewModel.setCurrentTab(Tab.MINE)
                    }
                )
                currentTab == Tab.GALLERY -> {
                    GalleryScreen()
                }
                currentTab == Tab.MINE -> MineScreen(
                    onSettingsClick = { settingId ->
                        if (settingId == "pairing") {
                            context.startActivity(Intent(context, PairingActivity::class.java))
                        } else {
                            navigateTo = settingId
                        }
                    }
                )
            }
        }
    }
}
