package com.rokid.cxrmsamples.ui.mine

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MineScreen(
    viewModel: MineViewModel = viewModel(),
    onSettingsClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "我的",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Settings List
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(getSettingsItems()) { item ->
                SettingsItem(
                    item = item,
                    onClick = { onSettingsClick(item.id) }
                )
            }
        }
    }
}

data class SettingsItem(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

fun getSettingsItems(): List<SettingsItem> = listOf(
    SettingsItem("pairing", "配对眼镜", Icons.Default.Bluetooth),  // ⭐ 移到第一位
    SettingsItem("llm_config", "大模型 API 设置", Icons.Default.SmartToy),
    SettingsItem("speech_config", "语音识别 API 设置", Icons.Default.Mic),
    SettingsItem("voice_service", "语音服务调试", Icons.Default.RecordVoiceOver),
    SettingsItem("device_info", "设备信息", Icons.Default.Info),
    SettingsItem("lip_reading_config", "唇语识别配置", Icons.Default.Person),
    SettingsItem("sign_language", "手语识别配置", Icons.Default.Gesture),
    SettingsItem("audio_usage", "使用音频", Icons.Default.Mic),
    SettingsItem("video_record", "录像", Icons.Default.Videocam),
    SettingsItem("photo_capture", "拍照", Icons.Default.PhotoCamera),
    SettingsItem("media_file", "媒体文件处理", Icons.Default.Folder),
    SettingsItem("custom_view", "自定义View", Icons.Default.ViewModule),
    SettingsItem("custom_protocol", "自定义协议", Icons.Default.Code)
)

@Composable
fun SettingsItem(
    item: SettingsItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
