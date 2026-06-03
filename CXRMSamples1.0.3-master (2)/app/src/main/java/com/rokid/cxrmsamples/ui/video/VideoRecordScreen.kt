package com.rokid.cxrmsamples.ui.video

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.activities.video.VideoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoRecordScreen(
    viewModel: VideoViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        viewModel.setSceneStatusListener(true)
        onDispose { viewModel.setSceneStatusListener(false) }
    }

    val selectedResolution by viewModel.selectedVideoSize.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val durationUnit by viewModel.durationUnit.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()

    var resolutionExpanded by remember { mutableStateOf(false) }
    var durationInput by remember { mutableStateOf(duration.toString()) }

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
            Text(text = "视频录制", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        ExposedDropdownMenuBox(
            expanded = resolutionExpanded,
            onExpandedChange = { resolutionExpanded = !resolutionExpanded }
        ) {
            TextField(
                value = "${selectedResolution.width}x${selectedResolution.height}",
                onValueChange = {},
                readOnly = true,
                label = { Text("选择分辨率") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = resolutionExpanded,
                onDismissRequest = { resolutionExpanded = false }
            ) {
                viewModel.videoSize.forEach { size ->
                    DropdownMenuItem(
                        text = { Text("${size.width}x${size.height}") },
                        onClick = {
                            viewModel.sizeChoose(size)
                            resolutionExpanded = false
                        }
                    )
                }
            }
        }

        TextField(
            value = durationInput,
            onValueChange = { input ->
                durationInput = input
                input.toIntOrNull()?.let { viewModel.setDuration(it) }
            },
            label = { Text("设置时长") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                RadioButton(
                    selected = (durationUnit == VideoViewModel.DurationUnit.SECONDS),
                    onClick = { viewModel.setDurationUnit(VideoViewModel.DurationUnit.SECONDS) }
                )
                Text("秒")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                RadioButton(
                    selected = (durationUnit == VideoViewModel.DurationUnit.MINUTES),
                    onClick = { viewModel.setDurationUnit(VideoViewModel.DurationUnit.MINUTES) }
                )
                Text("分")
            }
        }

        Button(
            onClick = { viewModel.setVideoParams() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            Text("设置录像参数")
        }

        Button(
            onClick = { viewModel.toggleRecording() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            Text(if (isRecording) "停止录像" else "开始录像")
        }
    }
}
