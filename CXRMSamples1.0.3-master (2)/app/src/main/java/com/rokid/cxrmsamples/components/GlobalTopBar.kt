package com.rokid.cxrmsamples.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.cxrmsamples.managers.GlobalWifiManager

/**
 * 全局顶部栏（在系统状态栏下方）
 * 参考: Glasses_0919 样式，显示设备名称和右侧操作图标
 */
@Composable
fun GlobalTopBar() {
    val wifiManager = GlobalWifiManager.getInstance()
    val deviceName by wifiManager.deviceName.collectAsState()
    val bluetoothStatus by wifiManager.bluetoothStatus.collectAsState()
    val wifiStatus by wifiManager.wifiStatus.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFE0E0E0))
    ) {
        // 第一行：设备名称和操作图标（类似 Glasses_0919）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：设备名称
            Text(
                text = deviceName,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            
            // 右侧：操作图标（?, +, 设置）
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 蓝牙状态指示（小圆点）
                Text(
                    text = when (bluetoothStatus) {
                        GlobalWifiManager.BluetoothStatus.CONNECTED -> "🔵"
                        GlobalWifiManager.BluetoothStatus.DISCONNECTED -> "⚫"
                        GlobalWifiManager.BluetoothStatus.CONNECTING -> "🔵"
                    },
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
                
                IconButton(onClick = { /* 帮助 */ }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "帮助", tint = Color.DarkGray)
                }
                IconButton(onClick = { /* 添加 */ }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "添加", tint = Color.DarkGray)
                }
                IconButton(onClick = { /* 设置 */ }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.DarkGray)
                }
            }
        }
        
        // 第二行：WiFi连接状态（类似 CXR连接中...）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (wifiStatus) {
                    GlobalWifiManager.WifiStatus.CONNECTED -> "CXR已连接"
                    GlobalWifiManager.WifiStatus.CONNECTING -> "CXR连接中..."
                    GlobalWifiManager.WifiStatus.DISCONNECTED -> "CXR未连接"
                },
                fontSize = 12.sp,
                color = when (wifiStatus) {
                    GlobalWifiManager.WifiStatus.CONNECTED -> Color(0xFF4CAF50)
                    GlobalWifiManager.WifiStatus.CONNECTING -> Color(0xFFFFA726)
                    GlobalWifiManager.WifiStatus.DISCONNECTED -> Color(0xFF757575)
                }
            )
        }
    }
}
