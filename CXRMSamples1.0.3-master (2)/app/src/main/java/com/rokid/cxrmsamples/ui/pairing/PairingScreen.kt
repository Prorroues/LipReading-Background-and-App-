package com.rokid.cxrmsamples.ui.pairing

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.R

@SuppressLint("MissingPermission")
@Composable
fun PairingScreen(
    viewModel: PairingViewModel = viewModel(),
    onScan: () -> Unit,
    onDeviceClick: (DeviceItem?) -> Unit,
    onClear: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onConnected: () -> Unit
) {
    val recordState by viewModel.recordState.collectAsState()
    val scanning by viewModel.isScanningState.collectAsState()
    val devices by viewModel.devicesList.collectAsState()
    val recordName by viewModel.recordName.collectAsState()
    val recordMacAddress by viewModel.recordMacAddress.collectAsState()
    val recordUuid by viewModel.recordUUID.collectAsState()
    val connecting by viewModel.connecting.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val isCharging by viewModel.isCharging.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val wearingStatus by viewModel.wearingStatus.collectAsState()

    if (connected) {
        onConnected()
    }

    LaunchedEffect(devices, recordMacAddress, recordState, scanning, connected, connecting) {
        if (recordState && scanning && !connected && !connecting) {
            val matched = recordMacAddress?.let { mac ->
                devices.firstOrNull { it.macAddress == mac }
            }
            if (matched != null) {
                onDeviceClick(matched)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                recordState -> {
                    RecordStatusSection(
                        recordName = recordName ?: "-",
                        recordMacAddress = recordMacAddress ?: "-",
                        recordUuid = recordUuid ?: "-",
                        connected = connected,
                        batteryLevel = batteryLevel,
                        isCharging = isCharging,
                        brightness = brightness,
                        volume = volume,
                        wearingStatus = wearingStatus,
                        onReconnect = onReconnect,
                        onScan = onScan
                    )
                }
                scanning -> {
                    ScanningSection(onCancel = onScan)
                }
                else -> {
                    ScanPromptSection(onScan = onScan)
                }
            }

            if (!scanning && devices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                DeviceListSection(
                    devices = devices,
                    connecting = connecting,
                    onDeviceClick = onDeviceClick,
                    onClear = onClear
                )
            }

            if (connected) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.bt_disconnect))
                }
            }
        }
    }
}

@Composable
private fun ScanPromptSection(onScan: () -> Unit) {
    Spacer(modifier = Modifier.height(32.dp))
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(160.dp),
        shadowElevation = 8.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            IconCircle()
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.searching_for_rokid),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.searching_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.scan))
    }
}

@Composable
private fun ScanningSection(onCancel: () -> Unit) {
    Spacer(modifier = Modifier.height(32.dp))
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(160.dp),
        shadowElevation = 8.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            IconCircle()
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.searching_for_rokid),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.searching_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    TextButton(onClick = onCancel) {
        Text(text = stringResource(R.string.cancel_scanning))
    }
}

@Composable
private fun IconCircle() {
    Surface(
        shape = CircleShape,
        color = Color.White,
        shadowElevation = 6.dp,
        modifier = Modifier.size(96.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun RecordStatusSection(
    recordName: String,
    recordMacAddress: String,
    recordUuid: String,
    connected: Boolean,
    batteryLevel: Int,
    isCharging: Boolean?,
    brightness: Int,
    volume: Int,
    wearingStatus: String?,
    onReconnect: () -> Unit,
    onScan: () -> Unit
) {
    Text(
        text = stringResource(R.string.device_status),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.is_record),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.device_status_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = if (connected) Color(0xFFE7F6EE) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (connected) stringResource(R.string.connected) else stringResource(R.string.not_connected),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = recordName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.device_signal_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            InfoRow(label = stringResource(R.string.mac_address_record), value = recordMacAddress)
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow(label = stringResource(R.string.uuid_record), value = recordUuid)
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow(
                label = stringResource(R.string.battery_level),
                value = if (batteryLevel >= 0) {
                    if (isCharging == true) {
                        "${batteryLevel}% (${stringResource(R.string.charging)})"
                    } else {
                        "${batteryLevel}%"
                    }
                } else {
                    "-"
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow(
                label = stringResource(R.string.brightness_level),
                value = if (brightness >= 0) brightness.toString() else "-"
            )
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow(
                label = stringResource(R.string.volume_level),
                value = if (volume >= 0) volume.toString() else "-"
            )
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow(
                label = stringResource(R.string.wearing_status),
                value = when (wearingStatus) {
                    "1" -> stringResource(R.string.wearing)
                    "0" -> stringResource(R.string.not_wearing)
                    else -> "-"
                }
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onReconnect, modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.reconnect))
        }
        OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.scan))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DeviceListSection(
    devices: List<DeviceItem>,
    connecting: Boolean,
    onDeviceClick: (DeviceItem?) -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.scan_results),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        TextButton(onClick = onClear) {
            Text(text = stringResource(R.string.clear_items))
        }
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(devices) { deviceItem ->
            BluetoothDeviceItem(
                item = deviceItem,
                onClick = {
                    if (!connecting) {
                        onDeviceClick(deviceItem)
                    }
                }
            )
        }
    }
}

@Composable
fun BluetoothDeviceItem(item: DeviceItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onClick() },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text = item.macAddress, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text = "${item.rssi} dBm", fontSize = 14.sp)
    }
}
