package com.rokid.cxrmsamples.ui.mediafile

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.activities.mediaFile.ConnectionStatus
import com.rokid.cxrmsamples.activities.mediaFile.MediaFileViewModel

@Composable
fun MediaFileScreen(
    viewModel: MediaFileViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectStatus by viewModel.connected.collectAsState()
    val audioNumber by viewModel.audioNumber.collectAsState()
    val pictureNumber by viewModel.pictureNumber.collectAsState()
    val videoNumber by viewModel.videoNumber.collectAsState()
    val syncStatus by viewModel.syncing.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        CxrApi.getInstance().setVideoParams(60, 30, 1920, 1080, 0)
        viewModel.setMediaFilesUpdateListener()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text(text = "媒体文件", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { viewModel.getUnsyncNum() }) {
            Text(text = "获取未同步数量")
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(text = "音频：$audioNumber", modifier = Modifier.weight(1f))
            Text(text = "图片：$pictureNumber", modifier = Modifier.weight(1f))
            Text(text = "视频：$videoNumber", modifier = Modifier.weight(1f))
        }

        Text(
            text = "连接状态：$connectStatus",
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Button(
                onClick = { viewModel.connect() },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                enabled = connectStatus == ConnectionStatus.DISCONNECTED
            ) {
                Text(text = "连接WiFi")
            }

            Button(
                onClick = { viewModel.disconnect() },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                enabled = connectStatus == ConnectionStatus.CONNECTED
            ) {
                Text(text = "断开WiFi")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Button(
                onClick = { viewModel.startSync(arrayOf(ValueUtil.CxrMediaType.VIDEO)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                enabled = !syncStatus
            ) {
                Text(text = "同步视频")
            }
            Button(
                onClick = { viewModel.startSync(arrayOf(ValueUtil.CxrMediaType.PICTURE)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                enabled = !syncStatus
            ) {
                Text(text = "同步图片")
            }
            Button(
                onClick = { viewModel.startSync(arrayOf(ValueUtil.CxrMediaType.AUDIO)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                enabled = !syncStatus
            ) {
                Text(text = "同步音频")
            }
        }

        Button(
            onClick = { viewModel.stopSync() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            enabled = syncStatus
        ) {
            Text(text = "停止同步")
        }
    }
}
