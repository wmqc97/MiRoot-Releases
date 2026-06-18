package com.wmqc.miroot.car

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wmqc.miroot.car.ble.BleDeviceEntry
import com.wmqc.miroot.car.ble.DigitalCarKeyConstants
import com.wmqc.miroot.car.ble.DigitalCarKeyPermissionHelper
import com.wmqc.miroot.car.ble.DigitalCarKeyProbe
import com.wmqc.miroot.car.ble.DigitalCarKeyService
import com.wmqc.miroot.car.ble.DigitalCarKeyState
import com.wmqc.miroot.car.ble.GattProbeEntry
import com.wmqc.miroot.ui.applyMiRootSecondarySystemBars
import kotlinx.coroutines.launch

/**
 * 数字车钥匙调试扫描页。
 * 支持扫描全部 BLE、按 UUID 过滤、点击连接、GATT 探测日志。
 */
class DigitalCarKeyDebugActivity : ComponentActivity() {

    private lateinit var dkService: DigitalCarKeyService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyMiRootSecondarySystemBars()
        enableEdgeToEdge()
        dkService = DigitalCarKeyService(this)

        setContent {
            DigitalCarKeyScanScreen(dkService = dkService)
        }
    }

    override fun onDestroy() {
        dkService.shutdown()
        super.onDestroy()
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, DigitalCarKeyDebugActivity::class.java)
    }
}

@Composable
private fun DigitalCarKeyScanScreen(dkService: DigitalCarKeyService) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val discoveredDevices = remember { mutableStateListOf<BleDeviceEntry>() }
    val probeLogs = remember { mutableStateListOf<GattProbeEntry>() }
    var isScanning by remember { mutableStateOf(false) }
    var stateText by remember { mutableStateOf("就绪") }
    var profileText by remember { mutableStateOf("") }
    val appCtx = ctx.applicationContext

    LaunchedEffect(dkService) {
        dkService.onDeviceFoundDetailed = { device, rssi, uuids ->
            synchronized(discoveredDevices) {
                val idx = discoveredDevices.indexOfFirst { it.device.address == device.address }
                val entry = BleDeviceEntry(device, rssi, uuids)
                if (idx >= 0) discoveredDevices[idx] = entry
                else discoveredDevices.add(entry)
            }
        }
        dkService.onProfileMatched = { profile ->
            profileText = "协议: ${profile.displayName}"
        }
        dkService.onProbeResult = { entry ->
            synchronized(probeLogs) {
                probeLogs.add(0, entry)
                if (probeLogs.size > 80) probeLogs.removeAt(probeLogs.lastIndex)
            }
        }
    }

    LaunchedEffect(dkService) {
        dkService.state.collect { event ->
            stateText = when (event.state) {
                DigitalCarKeyState.IDLE -> "就绪"
                DigitalCarKeyState.SCANNING -> "正在扫描（按 UUID 过滤）..."
                DigitalCarKeyState.SCANNING_ALL -> "正在扫描（全部 BLE 设备）..."
                DigitalCarKeyState.CONNECTING -> "连接中: ${event.message}"
                DigitalCarKeyState.DISCOVERING_SERVICES -> "发现服务..."
                DigitalCarKeyState.AUTHENTICATING -> "认证中: ${event.message}"
                DigitalCarKeyState.READY -> "已就绪 ✓ ${event.message}"
                DigitalCarKeyState.RECONNECTING -> "重连中: ${event.message}"
                DigitalCarKeyState.FAILED -> "失败: ${event.message}"
                DigitalCarKeyState.DISCONNECTED -> "已断开"
            }
            isScanning = event.state == DigitalCarKeyState.SCANNING
                || event.state == DigitalCarKeyState.SCANNING_ALL
        }
    }

    DisposableEffect(Unit) {
        onDispose { dkService.shutdown() }
    }

    val dark = isSystemInDarkTheme()
    val pageBg = Color(
        if (dark) android.graphics.Color.parseColor("#1A1A1A")
        else android.graphics.Color.parseColor("#F5F5F5")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp),
    ) {
        Text(
            text = "蓝牙数字钥匙扫描",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (dark) Color.White else Color.Black,
        )

        Spacer(Modifier.height(4.dp))
        Text(text = stateText, fontSize = 14.sp, color = if (isScanning) Color(0xFF4CAF50) else Color.Gray)
        if (profileText.isNotEmpty()) {
            Text(text = profileText, fontSize = 12.sp, color = Color(0xFF2196F3))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Service UUID: ${DigitalCarKeyConstants.digitalKeyServiceUuid.uuid}",
            fontSize = 11.sp,
            color = Color.Gray,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScanButton("按 UUID 扫描", Color(0xFF2E7D32)) {
                if (!checkBleReady(ctx, appCtx)) return@ScanButton
                discoveredDevices.clear()
                probeLogs.clear()
                profileText = ""
                dkService.startScan()
            }
            ScanButton("扫描全部 BLE", Color(0xFFFF9800)) {
                if (!checkBleReady(ctx, appCtx)) return@ScanButton
                discoveredDevices.clear()
                probeLogs.clear()
                profileText = ""
                dkService.startScanAllDevices()
            }
            ScanButton("停止", Color(0xFFF44336)) {
                dkService.stopScan()
                isScanning = false
                stateText = "已停止"
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { dkService.probeGattProfile() },
                enabled = dkService.getConnectedDeviceAddress() != null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B)),
            ) {
                Text("探测 GATT", fontSize = 12.sp)
            }
            Button(
                onClick = { dkService.disconnect() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9E9E9E)),
            ) {
                Text("断开", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "发现 ${discoveredDevices.size} 个设备",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.Gray,
        )

        Spacer(Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(discoveredDevices.toList(), key = { it.device.address }) { entry ->
                DeviceItem(
                    entry = entry,
                    isConnected = dkService.getConnectedDeviceAddress() == entry.device.address,
                    onConnect = {
                        scope.launch {
                            dkService.stopScan()
                            probeLogs.clear()
                            dkService.connectToDevice(entry.device)
                        }
                    },
                )
            }
            if (discoveredDevices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("点击上方按钮开始扫描", fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
        }

        if (probeLogs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("探测日志", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .background(
                        if (dark) Color(0xFF111111) else Color(0xFFECEFF1),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(6.dp),
            ) {
                items(probeLogs.take(20), key = { "${it.characteristicUuid}-${it.timestampMs}" }) { entry ->
                    Text(
                        text = "${entry.source} ${entry.characteristicUuid.toString().take(13)}… "
                            + DigitalCarKeyProbe.bytesToHex(entry.value),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (dark) Color(0xFFB0BEC5) else Color(0xFF455A64),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ScanButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = Modifier.weight(1f),
    ) {
        Text(label, fontSize = 12.sp)
    }
}

private fun checkBleReady(ctx: android.content.Context, appCtx: android.content.Context): Boolean {
    if (!DigitalCarKeyPermissionHelper.hasAll(appCtx)) {
        Toast.makeText(ctx, "缺少 BLE 权限", Toast.LENGTH_SHORT).show()
        return false
    }
    if (!DigitalCarKeyPermissionHelper.isBluetoothEnabled(appCtx)) {
        Toast.makeText(ctx, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
        return false
    }
    return true
}

@Composable
private fun DeviceItem(
    entry: BleDeviceEntry,
    isConnected: Boolean,
    onConnect: () -> Unit,
) {
    val device = entry.device
    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF2A2A2A) else Color.White
    val name = device.name ?: "(未命名)"
    val isKnown = entry.serviceUuids?.any { pu ->
        DigitalCarKeyConstants.knownServiceUuids.any {
            it.equals(pu.uuid.toString(), ignoreCase = true)
        }
    } ?: false

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onConnect)
            .padding(12.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = when {
                        isConnected -> Color(0xFF4CAF50)
                        isKnown -> Color(0xFF2196F3)
                        else -> if (dark) Color.White else Color.Black
                    },
                )
                Row {
                    if (isConnected) {
                        Text(
                            "已连接",
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier
                                .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    } else if (isKnown) {
                        Text(
                            "DK",
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier
                                .background(Color(0xFF2196F3), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text("${entry.rssi} dBm", fontSize = 12.sp, color = Color.Gray)
                }
            }
            Text(device.address, fontSize = 11.sp, color = Color.Gray)
            entry.serviceUuids?.takeIf { it.isNotEmpty() }?.let { uuids ->
                Text(
                    text = "UUID: " + uuids.joinToString { it.uuid.toString().take(18) + "…" },
                    fontSize = 10.sp,
                    color = Color.Gray,
                )
            }
            Text("点击连接并探测 GATT", fontSize = 10.sp, color = Color(0xFF9E9E9E))
        }
    }
}
