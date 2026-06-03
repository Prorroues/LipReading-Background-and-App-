package com.rokid.cxrmsamples.ui.customprotocol

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.activities.customProtocol.CustomProtocolViewModel

@Composable
fun CustomProtocolScreen(
    viewModel: CustomProtocolViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message by viewModel.messageReceived.collectAsState()

    DisposableEffect(Unit) {
        viewModel.setCustomCmdListener(true)
        onDispose { viewModel.setCustomCmdListener(false) }
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
            Text(text = "自定义协议", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "收到的消息：",
            modifier = Modifier.padding(top = 12.dp, start = 12.dp)
        )
        Text(
            text = message ?: "",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 12.dp),
            textAlign = TextAlign.Start
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { viewModel.sendCustomMessage() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("发送自定义协议")
        }
    }
}
