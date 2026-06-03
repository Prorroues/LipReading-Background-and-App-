package com.rokid.cxrmsamples.ui.customview

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.activities.customView.CustomViewViewModel
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager

@Composable
fun CustomViewScreen(
    viewModel: CustomViewViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isCustomViewOpen by viewModel.isCustomViewRunning.collectAsState()
    val iconSent by viewModel.iconSent.collectAsState()

    DisposableEffect(Unit) {
        viewModel.setCustomSceneListener(true)
        onDispose { viewModel.setCustomSceneListener(false) }
    }

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
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "自定义View",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!iconSent) {
            Button(
                onClick = { viewModel.uploadIcon(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("上传图像")
            }
        } else {
            Button(
                onClick = { viewModel.toggleCustomView() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isCustomViewOpen) "关闭自定义页面" else "打开自定义页面")
            }

            if (isCustomViewOpen) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.updateCustomView() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("更新自定义界面")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "对话界面测试", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { GlobalCustomViewManager.getInstance().showDialogView("你好，我是乐奇") },
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) {
                    Text("显示对话界面")
                }
                Button(
                    onClick = { GlobalCustomViewManager.getInstance().closeView() },
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                ) {
                    Text("关闭显示")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Button(
                    onClick = { GlobalCustomViewManager.getInstance().updateDialogText("正在识别中...") },
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) {
                    Text("更新文字")
                }
                Button(
                    onClick = { GlobalCustomViewManager.getInstance().updateDialogText("识别完成！") },
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                ) {
                    Text("完成识别")
                }
            }
        }
    }
}
