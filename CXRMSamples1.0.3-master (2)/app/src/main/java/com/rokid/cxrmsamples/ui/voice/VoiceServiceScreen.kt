package com.rokid.cxrmsamples.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.activities.voiceService.VoiceServiceViewModel
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager

@Composable
fun VoiceServiceScreen(
    viewModel: VoiceServiceViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val isRecognizing by viewModel.isRecognizing.collectAsState()
    val recognitionText by viewModel.recognitionText.collectAsState()
    val isSynthesizing by viewModel.isSynthesizing.collectAsState()
    val synthesisText by viewModel.synthesisText.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val appKey by viewModel.appKey.collectAsState()
    val accessKeyId by viewModel.accessKeyId.collectAsState()
    val accessKeySecret by viewModel.accessKeySecret.collectAsState()
    val qwenApiKey by viewModel.qwenApiKey.collectAsState()
    val lastTtsFilePath by viewModel.lastTtsFilePath.collectAsState()

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        Toast.makeText(
            context,
            if (allGranted) "权限已授予" else "需要录音和存储权限才能使用语音服务",
            Toast.LENGTH_SHORT
        ).show()
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        GlobalCustomViewManager.getInstance().initialize(context)
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
            Text(text = "语音服务", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "阿里云NLS配置（语音识别/合成）", modifier = Modifier.padding(vertical = 8.dp))
            TextField(
                value = appKey,
                onValueChange = { viewModel.setAppKey(it) },
                label = { Text("AppKey（必填）") },
                placeholder = { Text("示例：1234567890abcdef") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                singleLine = true
            )
            TextField(
                value = accessKeyId,
                onValueChange = { viewModel.setAccessKeyId(it) },
                label = { Text("访问密钥 ID") },
                placeholder = { Text("示例：LTAI5t...") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                singleLine = true
            )
            TextField(
                value = accessKeySecret,
                onValueChange = { viewModel.setAccessKeySecret(it) },
                label = { Text("访问密钥密文") },
                placeholder = { Text("示例：******") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                singleLine = true
            )

            Text(text = "阿里云千问API配置（智能助手）", modifier = Modifier.padding(vertical = 8.dp))
            TextField(
                value = qwenApiKey,
                onValueChange = { viewModel.setQwenApiKey(it) },
                label = { Text("Qwen API Key（必填）") },
                placeholder = { Text("示例：sk-...") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                singleLine = true
            )
            Text(
                text = "提示：千问API Key可在阿里云DashScope控制台获取\nhttps://dashscope.console.aliyun.com/",
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall
            )

            Text(text = "语音识别", modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Button(
                    onClick = { viewModel.startRecognition() },
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                    enabled = !isRecognizing && !isSynthesizing
                ) {
                    Text("开始识别")
                }
                Button(
                    onClick = { viewModel.stopRecognition() },
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    enabled = isRecognizing
                ) {
                    Text("停止识别")
                }
            }
            TextField(
                value = recognitionText,
                onValueChange = {},
                label = { Text("识别结果") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                readOnly = true
            )

            Text(text = "语音合成", modifier = Modifier.padding(vertical = 8.dp))
            TextField(
                value = synthesisText,
                onValueChange = { viewModel.setSynthesisText(it) },
                label = { Text("要合成的文本") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            Button(
                onClick = { viewModel.startSynthesis() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                enabled = !isRecognizing && !isSynthesizing && synthesisText.isNotEmpty()
            ) {
                Text("开始合成")
            }
            Button(
                onClick = { viewModel.playLastTts() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                enabled = !isSynthesizing && !isRecognizing && !lastTtsFilePath.isNullOrEmpty()
            ) {
                Text("播放合成音频")
            }
            if (!lastTtsFilePath.isNullOrEmpty()) {
                Text(
                    text = "最新合成文件: $lastTtsFilePath",
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }

            Text(
                text = "状态: $statusMessage",
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
        }
    }
}
