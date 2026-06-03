package com.rokid.cxrmsamples.ui.audio

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.activities.audio.AudioSceneId
import com.rokid.cxrmsamples.activities.audio.AudioUsageViewModel
import com.rokid.cxrmsamples.activities.audio.PlayState

@Composable
fun AudioUsageScreen(
    viewModel: AudioUsageViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playState by viewModel.playStatus.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val listRecordName by viewModel.listRecordName.collectAsState()
    val changing by viewModel.changing.collectAsState()
    val pickupType by viewModel.pickUpType.collectAsState()

    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f

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
            Text(text = "使用音频", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "拾音声场：${pickupType.sceneName}",
            modifier = Modifier.padding(top = 8.dp)
        )

        Row(
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            Button(
                onClick = { viewModel.startAudioStream() },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                enabled = !recording
            ) {
                Text("开始录音")
            }
            Button(
                onClick = { viewModel.stopAudioStream() },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                enabled = recording
            ) {
                Text("停止录音")
            }
        }

        Row(modifier = Modifier.padding(vertical = 16.dp)) {
            Button(
                onClick = { viewModel.changeAudioSceneId(AudioSceneId.NEAR) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                enabled = (!changing && pickupType != AudioSceneId.NEAR)
            ) {
                Text("近场")
            }
            Button(
                onClick = { viewModel.changeAudioSceneId(AudioSceneId.FAR) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                enabled = (!changing && pickupType != AudioSceneId.FAR)
            ) {
                Text("远场")
            }
            Button(
                onClick = { viewModel.changeAudioSceneId(AudioSceneId.BOTH) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                enabled = (!changing && pickupType != AudioSceneId.BOTH)
            ) {
                Text("全景")
            }
        }

        if (listRecordName.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Text("音频播放器", modifier = Modifier.padding(bottom = 16.dp))
                Row {
                    Button(
                        onClick = {
                            if (playState == PlayState.PLAYING) {
                                viewModel.pausePlayAudio()
                            } else {
                                viewModel.startPlayAudio()
                            }
                        },
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Text(if (playState == PlayState.PLAYING) "暂停" else "播放")
                    }
                    Button(
                        onClick = { viewModel.stopPlayAudio() },
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                        enabled = playState != PlayState.STOPPED
                    ) {
                        Text("停止")
                    }
                }
                Slider(
                    value = progress,
                    onValueChange = { },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                )
                Text("进度: ${formatTime(currentPosition)} / ${formatTime(duration)}")
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@SuppressLint("DefaultLocale")
fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).toInt()
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%02d:%02d", minutes, remainingSeconds)
}
