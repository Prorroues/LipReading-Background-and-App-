package com.rokid.cxrmsamples.ui.lipreading

import android.Manifest
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.activities.videoUpload.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LipReadingScreen(viewModel: VideoUploadViewModel = viewModel(), onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val selectedResolution by viewModel.selectedVideoSize.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val durationUnit by viewModel.durationUnit.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val wifiConnectionStatus by viewModel.wifiConnectionStatus.collectAsState()
    val uploadStatus by viewModel.uploadStatus.collectAsState()
    val currentUploadingFile by viewModel.currentUploadingFile.collectAsState()
    val videoNumber by viewModel.videoNumber.collectAsState()
    val uploadUrl by viewModel.uploadUrl.collectAsState()
    val localMediaPath by viewModel.localMediaPath.collectAsState()
    val resultExtractField by viewModel.resultExtractField.collectAsState()
    val isAutoUploadEnabled by viewModel.isAutoUploadEnabled.collectAsState()
    val serverResponseMessage by viewModel.serverResponseMessage.collectAsState()
    val lipReadingResult by viewModel.lipReadingResult.collectAsState()
    val lastUploadedVideoUrl by viewModel.lastUploadedVideoUrl.collectAsState()
    val lastUploadedVideoPath by viewModel.lastUploadedVideoPath.collectAsState()
    val isSynthesizing by viewModel.isSynthesizing.collectAsState()
    val lastTtsFilePath by viewModel.lastTtsFilePath.collectAsState()
    val ttsStatusMessage by viewModel.ttsStatusMessage.collectAsState()

    var resolutionExpanded by remember { mutableStateOf(false) }
    var durationInput by remember { mutableStateOf(duration.toString()) }
    var uploadUrlInput by remember { mutableStateOf(uploadUrl) }
    var localPathInput by remember { mutableStateOf(localMediaPath) }
    var extractFieldInput by remember { mutableStateOf(resultExtractField) }
    var dialog by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.connectWifiP2P(force = false)
    }
    DisposableEffect(Unit) { viewModel.setSceneStatusListener(true); onDispose { viewModel.setSceneStatusListener(false) } }
    LaunchedEffect(Unit) {
        viewModel.loadUploadUrl(context)
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text("唇语识别", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            if (dialog == 1) ConfigEditor(uploadUrlInput, { uploadUrlInput = it }, "完整上传地址 (例如: http://IP:24060/upload/videos)", { viewModel.setUploadUrl(context, uploadUrlInput); dialog = 0 }, { uploadUrlInput = uploadUrl; dialog = 0 })
            else if (dialog == 2) ConfigEditor(localPathInput, { localPathInput = it }, "本地视频上传路径", { viewModel.setLocalMediaPath(localPathInput); dialog = 0 }, { localPathInput = localMediaPath; dialog = 0 })
            else if (dialog == 3) ConfigEditor(extractFieldInput, { extractFieldInput = it }, "结果提取字段 (支持点路径，如 data.src_recognition_results)", { viewModel.setResultExtractField(extractFieldInput); dialog = 0 }, { extractFieldInput = resultExtractField; dialog = 0 })
            else if (dialog == 4) BooleanChoiceEditor("是否自动上传视频", isAutoUploadEnabled, { viewModel.setAutoUploadEnabled(it); dialog = 0 }, { dialog = 0 })
            else {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Button(onClick = { uploadUrlInput = uploadUrl; dialog = 1 }, modifier = Modifier.weight(1f).padding(end = 4.dp)) { Text("设置上传地址") }
                    Button(onClick = { viewModel.testConnection() }, modifier = Modifier.weight(1f).padding(start = 4.dp)) { Text("测试上传地址连接") }
                }
                Button(onClick = { localPathInput = localMediaPath; dialog = 2 }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text("设置本地视频上传路径") }
                Button(onClick = { dialog = 4 }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) { Text("是否自动上传视频：${if (isAutoUploadEnabled) "是" else "否"}") }
                Text("当前完整上传地址: $uploadUrl", modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                Text("上传路径: $localMediaPath", modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            }

            ExposedDropdownMenuBox(expanded = resolutionExpanded, onExpandedChange = { resolutionExpanded = !resolutionExpanded }) {
                TextField(value = "${selectedResolution.width}x${selectedResolution.height}", onValueChange = {}, readOnly = true, label = { Text("选择分辨率") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth())
                ExposedDropdownMenu(expanded = resolutionExpanded, onDismissRequest = { resolutionExpanded = false }) {
                    viewModel.videoSize.forEach { size -> DropdownMenuItem(text = { Text("${size.width}x${size.height}") }, onClick = { viewModel.sizeChoose(size); resolutionExpanded = false }) }
                }
            }

            TextField(value = durationInput, onValueChange = { durationInput = it; it.toIntOrNull()?.let(viewModel::setDuration) }, label = { Text("设置时长") }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) { RadioButton(durationUnit == VideoUploadViewModel.DurationUnit.SECONDS, onClick = { viewModel.setDurationUnit(VideoUploadViewModel.DurationUnit.SECONDS) }); Text("秒") }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) { RadioButton(durationUnit == VideoUploadViewModel.DurationUnit.MINUTES, onClick = { viewModel.setDurationUnit(VideoUploadViewModel.DurationUnit.MINUTES) }); Text("分") }
            }
            Button(onClick = { viewModel.setVideoParams() }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { Text("设置录像参数") }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.toggleRecording() }, modifier = Modifier.weight(1f)) { Text(if (isRecording) "停止录像" else "开始录像") }
                Button(onClick = { extractFieldInput = resultExtractField; dialog = 3 }, modifier = Modifier.weight(1f)) { Text("提取json文件关键字段") }
            }

            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text("录制状态: ${if (isRecording) "录制中" else "未录制"}", modifier = Modifier.padding(vertical = 4.dp))
                Text("Wi-Fi状态: ${when (wifiConnectionStatus) { ConnectionStatus.CONNECTED -> "眼镜直连已连接（录像可以拷到手机）"; ConnectionStatus.CONNECTING -> "眼镜直连连接中"; ConnectionStatus.DISCONNECTED -> "眼镜直连未连接。手机有网只能上传，录像还在眼镜上" }}", modifier = Modifier.padding(vertical = 4.dp))
                Button(onClick = { viewModel.connectWifiP2P(force = true) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("立即连接眼镜直连") }
                Text("上传状态: ${when (uploadStatus) { UploadStatus.IDLE -> "空闲"; UploadStatus.UPLOADING -> "上传中"; UploadStatus.WAITING_RESPONSE -> "上传成功，等待响应"; UploadStatus.SUCCESS -> "成功"; UploadStatus.FAILED -> "失败" }}", modifier = Modifier.padding(vertical = 4.dp))
                Text("自动上传视频: ${if (isAutoUploadEnabled) "是" else "否"}", modifier = Modifier.padding(vertical = 4.dp))
                currentUploadingFile?.let { Text("当前文件: $it", modifier = Modifier.padding(vertical = 4.dp)) }
                Text("未同步视频: $videoNumber", modifier = Modifier.padding(vertical = 4.dp))
                Text("当前唇语识别结果提取自$resultExtractField 字段", modifier = Modifier.padding(vertical = 4.dp), fontSize = 12.sp)
            }

            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("唇语识别结果")
                TextField(value = lipReadingResult ?: "", onValueChange = {}, readOnly = true, label = { Text("识别结果") }, modifier = Modifier.fillMaxWidth().height(100.dp), maxLines = 4)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { lipReadingResult?.takeIf { it.isNotBlank() }?.let { viewModel.synthesizeAndPlay(it, autoPlay = true) } }, modifier = Modifier.weight(1f), enabled = !lipReadingResult.isNullOrBlank() && !isSynthesizing) { Text(if (isSynthesizing) "合成中..." else "合成并播放") }
                    Button(onClick = { viewModel.playTts() }, modifier = Modifier.weight(1f), enabled = !lastTtsFilePath.isNullOrEmpty() && !isSynthesizing) { Text("播放语音") }
                }
                if (!ttsStatusMessage.isNullOrEmpty()) Text("语音状态: $ttsStatusMessage", modifier = Modifier.fillMaxWidth().padding(top = 4.dp), fontSize = 12.sp)
            }

            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("视频预览")
                if (lastUploadedVideoPath.isNullOrEmpty() && lastUploadedVideoUrl.isNullOrEmpty()) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text("暂无已上传视频") }
                else {
                    AndroidView(modifier = Modifier.fillMaxWidth().height(200.dp), factory = { ctx ->
                        VideoView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            val c = MediaController(ctx)
                            c.setAnchorView(this)
                            setMediaController(c)
                            setOnErrorListener { _, _, _ -> true }
                        }
                    }, update = { view ->
                        val src = when {
                            lastUploadedVideoPath?.let { File(it).exists() } == true -> lastUploadedVideoPath
                            !lastUploadedVideoUrl.isNullOrEmpty() -> lastUploadedVideoUrl
                            else -> null
                        }
                        if (src != null && view.tag != src) {
                            view.tag = src
                            try {
                                if (src.startsWith("http")) view.setVideoURI(Uri.parse(src))
                                else view.setVideoPath(src)
                            } catch (_: Exception) {
                            }
                        }
                    })
                    Text(when { lastUploadedVideoPath?.let { File(it).exists() } == true -> "本地视频（点播放器控制条播放，不要反复点）"; !lastUploadedVideoUrl.isNullOrEmpty() -> "服务器视频（点击播放器控制条播放）"; else -> "加载中..." }, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }

            if (!serverResponseMessage.isNullOrEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text("服务器返回（调试）")
                    TextField(value = serverResponseMessage ?: "", onValueChange = {}, readOnly = true, label = { Text("返回消息") }, modifier = Modifier.fillMaxWidth().height(120.dp), maxLines = 5)
                }
            }
        }
    }
}

@Composable
private fun ConfigEditor(value: String, onValueChange: (String) -> Unit, label: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        TextField(value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Button(onClick = onConfirm, modifier = Modifier.weight(1f).padding(end = 4.dp)) { Text("确定") }
            Button(onClick = onCancel, modifier = Modifier.weight(1f).padding(start = 4.dp)) { Text("取消") }
        }
    }
}

@Composable
private fun BooleanChoiceEditor(label: String, currentValue: Boolean, onConfirm: (Boolean) -> Unit, onCancel: () -> Unit) {
    var selected by remember(currentValue) { mutableStateOf(currentValue) }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                RadioButton(selected = selected, onClick = { selected = true })
                Text("是")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                RadioButton(selected = !selected, onClick = { selected = false })
                Text("否")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Button(onClick = { onConfirm(selected) }, modifier = Modifier.weight(1f).padding(end = 4.dp)) { Text("确定") }
            Button(onClick = onCancel, modifier = Modifier.weight(1f).padding(start = 4.dp)) { Text("取消") }
        }
    }
}
