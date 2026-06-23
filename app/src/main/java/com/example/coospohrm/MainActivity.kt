package com.example.coospohrm

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "training_history"
        private const val HISTORY_KEY = "history"
        private const val ZONES_KEY = "heart_rate_zones"
        val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFO_SERVICE_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val MANUFACTURER_NAME_UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER_UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    data class TrainingSession(
        val id: String = UUID.randomUUID().toString(),
        val date: String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()),
        val durationSeconds: Int = 0,
        val maxBPM: Int = 0,
        val avgBPM: Int = 0,
        val hrData: List<Int> = emptyList(),
        val zonePercentages: Map<Int, Float> = emptyMap()
    )

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    private var heartRate by mutableIntStateOf(0)
    private var batteryLevel by mutableIntStateOf(-1)
    private var isConnected by mutableStateOf(false)
    private var manufacturerName by mutableStateOf("")
    private var modelNumber by mutableStateOf("")
    private val deviceDisplayName: String
        get() = if (manufacturerName.isNotEmpty() && modelNumber.isNotEmpty())
            "$manufacturerName $modelNumber"
        else "Coospo H9Z"
    private var statusText by mutableStateOf("Запрос разрешений...")
    private val hrHistory = mutableStateListOf<Int>()
    private var isTraining by mutableStateOf(false)
    private var trainingStartTime by mutableStateOf(0L)
    private var trainingMaxHR by mutableIntStateOf(0)
    private var currentScreen by mutableStateOf("main")
    private var selectedSession by mutableStateOf<TrainingSession?>(null)
    private val trainingHistory = mutableStateListOf<TrainingSession>()
    private var showSaveDialog by mutableStateOf(false)
    private var showDeleteDialog by mutableStateOf(false)
    private var sessionToDelete by mutableStateOf<TrainingSession?>(null)

    private var zone1Max by mutableIntStateOf(100)
    private var zone2Max by mutableIntStateOf(120)
    private var zone3Max by mutableIntStateOf(140)
    private var zone4Max by mutableIntStateOf(160)

    private val gson = Gson()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            statusText = "Поиск устройства..."
            connectToPairedDevice()
        } else {
            statusText = "Нужны разрешения Bluetooth"
            Toast.makeText(this, "Предоставьте разрешения в настройках", Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            connectToPairedDevice()
        } else {
            statusText = "Bluetooth выключен"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        loadHistory()
        loadZones()
        checkPermissionsAndConnect()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (currentScreen) {
                        "main" -> MainScreen(
                            deviceName = deviceDisplayName,
                            heartRate = heartRate,
                            batteryLevel = batteryLevel,
                            isConnected = isConnected,
                            statusText = statusText,
                            trainingHistory = trainingHistory.toList(),
                            zone1Max = zone1Max, zone2Max = zone2Max,
                            zone3Max = zone3Max, zone4Max = zone4Max,
                            onStartTraining = { startTraining() },
                            onHistoryClick = { session -> selectedSession = session; currentScreen = "sessionDetail" },
                            onDeleteClick = { session -> sessionToDelete = session; showDeleteDialog = true },
                            onSettingsClick = { currentScreen = "settings" }
                        )
                        "training" -> TrainingScreen(
                            heartRate = heartRate,
                            hrHistory = hrHistory.toList(),
                            zone1Max = zone1Max, zone2Max = zone2Max,
                            zone3Max = zone3Max, zone4Max = zone4Max,
                            onStopTraining = { showSaveDialog = true }
                        )
                        "sessionDetail" -> SessionDetailScreen(
                            session = selectedSession,
                            zone1Max = zone1Max, zone2Max = zone2Max,
                            zone3Max = zone3Max, zone4Max = zone4Max,
                            onBack = { currentScreen = "main" }
                        )
                        "settings" -> SettingsScreen(
                            z1 = zone1Max, z2 = zone2Max, z3 = zone3Max, z4 = zone4Max,
                            onSave = { z1, z2, z3, z4 ->
                                zone1Max = z1; zone2Max = z2; zone3Max = z3; zone4Max = z4
                                saveZones(); currentScreen = "main"
                            },
                            onBack = { currentScreen = "main" }
                        )
                    }
                }
            }

            if (showSaveDialog) {
                val duration = ((System.currentTimeMillis() - trainingStartTime) / 1000).toInt()
                AlertDialog(
                    onDismissRequest = { showSaveDialog = false },
                    title = { Text("Сохранить тренировку?") },
                    text = { Text("Длительность: ${formatDuration(duration)}\nМакс BPM: $trainingMaxHR") },
                    confirmButton = { TextButton(onClick = { saveTraining(); showSaveDialog = false; stopTraining() }) { Text("Сохранить") } },
                    dismissButton = { TextButton(onClick = { showSaveDialog = false; stopTraining() }) { Text("Отмена") } }
                )
            }

            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text("Удалить тренировку?") },
                    text = { Text("Действие нельзя отменить") },
                    confirmButton = { TextButton(onClick = { sessionToDelete?.let { trainingHistory.remove(it); saveHistory() }; showDeleteDialog = false; sessionToDelete = null }) { Text("Удалить", color = Color(0xFFF44336)) } },
                    dismissButton = { TextButton(onClick = { showDeleteDialog = false; sessionToDelete = null }) { Text("Отмена") } }
                )
            }
        }
    }

    fun getHeartRateZone(hr: Int): Int = when {
        hr <= zone1Max -> 1; hr <= zone2Max -> 2; hr <= zone3Max -> 3; hr <= zone4Max -> 4; else -> 5
    }

    private fun loadZones() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(ZONES_KEY, null) ?: return
        val parts = saved.split(",").map { it.toIntOrNull() ?: 0 }
        if (parts.size == 4) { zone1Max = parts[0]; zone2Max = parts[1]; zone3Max = parts[2]; zone4Max = parts[3] }
    }

    private fun saveZones() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(ZONES_KEY, "$zone1Max,$zone2Max,$zone3Max,$zone4Max").apply()
    }

    private fun checkPermissionsAndConnect() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            if (bluetoothAdapter?.isEnabled == true) {
                statusText = "Поиск устройства..."
                connectToPairedDevice()
            } else {
                statusText = "Включите Bluetooth"
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPairedDevice() {
        if (bluetoothAdapter?.isEnabled != true) {
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        statusText = "Поиск устройства..."
        val pairedDevices = bluetoothAdapter?.bondedDevices

        if (pairedDevices != null) {
            for (device in pairedDevices) {
                val name = device.name ?: ""
                if (name.contains("H9Z", true) || name.contains("Coospo", true) || name.contains("Heart", true)) {
                    statusText = "Подключение к $name..."
                    try {
                        bluetoothGatt?.close()
                        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } catch (e: SecurityException) {
                        statusText = "Ошибка подключения"
                    }
                    return
                }
            }
        }
        statusText = "H9Z не найден в сопряженных"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        isConnected = true; statusText = "Подключено!"; gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        isConnected = false; statusText = "Отключено. Переподключение..."; heartRate = 0; batteryLevel = -1
                        handler.postDelayed({
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                    connectToPairedDevice()
                                }
                            } else {
                                connectToPairedDevice()
                            }
                        }, 3000)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.getService(HEART_RATE_SERVICE_UUID)?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)?.let {
                    gatt.setCharacteristicNotification(it, true)
                    it.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID).value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID))
                }
                gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)?.let { battChar ->
                    gatt.readCharacteristic(battChar)
                    gatt.setCharacteristicNotification(battChar, true)
                    battChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)?.let { desc ->
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(desc)
                    }
                }
                gatt.getService(DEVICE_INFO_SERVICE_UUID)?.let { dis ->
                    dis.getCharacteristic(MANUFACTURER_NAME_UUID)?.let { gatt.readCharacteristic(it) }
                    dis.getCharacteristic(MODEL_NUMBER_UUID)?.let { gatt.readCharacteristic(it) }
                }
                runOnUiThread { statusText = "Мониторинг..." }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                HEART_RATE_MEASUREMENT_UUID -> {
                    val hr = parseHeartRate(characteristic.value)
                    runOnUiThread {
                        heartRate = hr
                        if (hr > 0) {
                            statusText = "Пульс: $hr BPM"
                            hrHistory.add(hr)
                            if (hrHistory.size > 300) hrHistory.removeAt(0)
                            if (isTraining && hr > trainingMaxHR) trainingMaxHR = hr
                        }
                    }
                }
                BATTERY_LEVEL_UUID -> {
                    val level = characteristic.value[0].toInt() and 0xFF
                    runOnUiThread { batteryLevel = level }
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    BATTERY_LEVEL_UUID -> {
                        val level = characteristic.value[0].toInt() and 0xFF
                        runOnUiThread { batteryLevel = level }
                    }
                    MANUFACTURER_NAME_UUID -> {
                        val name = String(characteristic.value).trim()
                        runOnUiThread { manufacturerName = name }
                    }
                    MODEL_NUMBER_UUID -> {
                        val model = String(characteristic.value).trim()
                        runOnUiThread { modelNumber = model }
                    }
                }
            }
        }
    }

    private fun parseHeartRate(data: ByteArray): Int {
        if (data.size < 2) return 0
        return try {
            if ((data[0].toInt() and 0x01) != 0 && data.size >= 3) ((data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8))
            else data[1].toInt() and 0xFF
        } catch (_: Exception) { 0 }
    }

    private fun startTraining() { isTraining = true; trainingStartTime = System.currentTimeMillis(); trainingMaxHR = 0; hrHistory.clear(); currentScreen = "training" }
    private fun stopTraining() { isTraining = false; currentScreen = "main" }

    private fun saveTraining() {
        val duration = ((System.currentTimeMillis() - trainingStartTime) / 1000).toInt()
        val avgHR = if (hrHistory.isNotEmpty()) hrHistory.average().roundToInt() else 0
        val zoneCounts = mutableMapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0)
        hrHistory.forEach { hr -> val zone = getHeartRateZone(hr); zoneCounts[zone] = (zoneCounts[zone] ?: 0) + 1 }
        val total = hrHistory.size
        val zonePercentages = zoneCounts.mapValues { if (total > 0) (it.value.toFloat() / total * 100) else 0f }
        trainingHistory.add(0, TrainingSession(durationSeconds = duration, maxBPM = trainingMaxHR, avgBPM = avgHR, hrData = hrHistory.toList(), zonePercentages = zonePercentages))
        saveHistory()
    }

    private fun loadHistory() {
        val json = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(HISTORY_KEY, null) ?: return
        try { trainingHistory.clear(); trainingHistory.addAll(gson.fromJson(json, object : TypeToken<List<TrainingSession>>() {}.type)) } catch (_: Exception) {}
    }

    private fun saveHistory() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(HISTORY_KEY, gson.toJson(trainingHistory.toList())).apply()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        bluetoothGatt?.disconnect(); bluetoothGatt?.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}

// Вспомогательные функции
fun formatDuration(seconds: Int): String = "${seconds / 60}:${String.format("%02d", seconds % 60)}"

fun getZoneColor(zone: Int): Color = when (zone) {
    1 -> Color(0xFF4CAF50); 2 -> Color(0xFF2196F3); 3 -> Color(0xFFFFC107); 4 -> Color(0xFFFF69B4); 5 -> Color(0xFFF44336); else -> Color.Gray
}

fun getZoneName(zone: Int): String = when (zone) {
    1 -> "Отдых"; 2 -> "Жиросжигание"; 3 -> "Кардио"; 4 -> "Силовая"; 5 -> "Максимальная"; else -> ""
}

fun getZoneDescription(zone: Int): String = when (zone) {
    1 -> "Восстановление и разминка"; 2 -> "🔥 Оптимальное жиросжигание"; 3 -> "Укрепление сердца и легких"
    4 -> "💪 Развитие силы"; 5 -> "⚠️ Максимальная нагрузка"; else -> ""
}

fun getZoneRange(zone: Int, z1: Int, z2: Int, z3: Int, z4: Int): String = when (zone) {
    1 -> "70-$z1"; 2 -> "$z1-$z2"; 3 -> "$z2-$z3"; 4 -> "$z3-$z4"; 5 -> "$z4-180"; else -> ""
}

fun getBatteryColor(level: Int): Color = when {
    level > 50 -> Color(0xFF4CAF50)
    level > 20 -> Color(0xFFFFC107)
    else -> Color(0xFFF44336)
}

@Composable
fun HeartRateChart(hrHistory: List<Int>, z1: Int, z2: Int, z3: Int, z4: Int) {
    if (hrHistory.isEmpty()) return
    val minHR = 70f; val maxHR = 180f; val maxPoints = 60
    val zoneColors = listOf(Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFFFC107), Color(0xFFFF69B4), Color(0xFFF44336))

    Row(modifier = Modifier.fillMaxWidth().height(180.dp)) {
        Column(Modifier.width(35.dp).fillMaxHeight(), Arrangement.SpaceBetween) {
            listOf("180", "$z4", "$z3", "$z2", "$z1", "70").forEachIndexed { i, s ->
                Text(s, fontSize = 8.sp, color = when(i) { 0 -> Color(0xFFF44336); 1 -> Color(0xFFFF69B4); 2 -> Color(0xFFFFC107); 3 -> Color(0xFF2196F3); else -> Color(0xFF4CAF50) })
            }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val width = size.width; val height = size.height; val range = maxHR - minHR
            listOf(70f to z1.toFloat(), z1.toFloat() to z2.toFloat(), z2.toFloat() to z3.toFloat(), z3.toFloat() to z4.toFloat(), z4.toFloat() to 180f).forEachIndexed { i, (low, high) ->
                drawRect(zoneColors[i].copy(alpha = 0.15f), Offset(0f, height - ((high - minHR) / range * height)), Size(width, ((high - low) / range * height)))
            }
            listOf(z1.toFloat(), z2.toFloat(), z3.toFloat(), z4.toFloat()).forEach {
                drawLine(Color.Gray.copy(alpha = 0.3f), Offset(0f, height - ((it - minHR) / range * height)), Offset(width, height - ((it - minHR) / range * height)), 1f)
            }
            if (hrHistory.size >= 2) {
                val stepX = width / (maxPoints - 1); val offset = maxPoints - hrHistory.size
                val path = Path()
                hrHistory.forEachIndexed { i, hr ->
                    val x = (offset + i) * stepX; val y = height - ((hr - minHR) / range * height)
                    if (i == 0) path.moveTo(x, y.coerceIn(0f, height)) else path.lineTo(x, y.coerceIn(0f, height))
                }
                drawPath(path, Color.White.copy(alpha = 0.3f), style = Stroke(5f))
                drawPath(path, Color(0xFF2196F3), style = Stroke(2f))
                hrHistory.forEachIndexed { i, hr ->
                    val x = (offset + i) * stepX; val y = height - ((hr - minHR) / range * height)
                    drawCircle(getZoneColor(when { hr <= z1 -> 1; hr <= z2 -> 2; hr <= z3 -> 3; hr <= z4 -> 4; else -> 5 }), 2.5f, Offset(x, y.coerceIn(0f, height)))
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    deviceName: String, heartRate: Int, batteryLevel: Int, isConnected: Boolean, statusText: String,
    trainingHistory: List<MainActivity.TrainingSession>,
    zone1Max: Int, zone2Max: Int, zone3Max: Int, zone4Max: Int,
    onStartTraining: () -> Unit, onHistoryClick: (MainActivity.TrainingSession) -> Unit,
    onDeleteClick: (MainActivity.TrainingSession) -> Unit, onSettingsClick: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(deviceName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (batteryLevel >= 0) {
                    Text("$batteryLevel%", fontSize = 14.sp, color = getBatteryColor(batteryLevel), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                }
                IconButton(onClick = onSettingsClick, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Settings, "Настройки", tint = Color.Gray, modifier = Modifier.size(22.dp))
                }
            }
        }
        Text(statusText, fontSize = 12.sp, color = if (isConnected) Color(0xFF4CAF50) else Color.Gray)
        Spacer(Modifier.height(16.dp))

        val zone = if (heartRate > 0) when { heartRate <= zone1Max -> 1; heartRate <= zone2Max -> 2; heartRate <= zone3Max -> 3; heartRate <= zone4Max -> 4; else -> 5 } else 0
        val zoneColor = if (zone > 0) getZoneColor(zone) else Color.Gray

        Box(modifier = Modifier.size(200.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxSize(), CircleShape, color = zoneColor.copy(alpha = 0.1f)) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(if (heartRate > 0) "$heartRate" else "---", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = zoneColor, textAlign = TextAlign.Center)
                    Text("BPM", fontSize = 18.sp, color = zoneColor, textAlign = TextAlign.Center)
                    if (zone > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("Зона $zone: ${getZoneName(zone)}", fontSize = 14.sp, color = zoneColor, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text(getZoneRange(zone, zone1Max, zone2Max, zone3Max, zone4Max), fontSize = 12.sp, color = zoneColor.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        if (isConnected) {
            Button(onClick = onStartTraining, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), shape = RoundedCornerShape(16.dp)) {
                Text("НАЧАТЬ ТРЕНИРОВКУ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(24.dp))

        if (trainingHistory.isNotEmpty()) {
            Text("История тренировок", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(trainingHistory, key = { it.id }) { session ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { onHistoryClick(session) }) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(session.date, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                    Text("Макс: ${session.maxBPM}", fontSize = 12.sp)
                                    Text("Сред: ${session.avgBPM}", fontSize = 12.sp)
                                    Text(formatDuration(session.durationSeconds), fontSize = 12.sp)
                                }
                            }
                            IconButton(onClick = { onDeleteClick(session) }) {
                                Icon(Icons.Default.Delete, "Удалить", tint = Color(0xFFF44336), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        } else {
            Text("История тренировок пуста", fontSize = 14.sp, color = Color.Gray)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun TrainingScreen(heartRate: Int, hrHistory: List<Int>, zone1Max: Int, zone2Max: Int, zone3Max: Int, zone4Max: Int, onStopTraining: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ТРЕНИРОВКА", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
        Spacer(Modifier.height(12.dp))
        val zone = if (heartRate > 0) when { heartRate <= zone1Max -> 1; heartRate <= zone2Max -> 2; heartRate <= zone3Max -> 3; heartRate <= zone4Max -> 4; else -> 5 } else 0
        val zoneColor = if (zone > 0) getZoneColor(zone) else Color.Gray
        Box(Modifier.size(160.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxSize(), CircleShape, color = zoneColor.copy(alpha = 0.1f)) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(if (heartRate > 0) "$heartRate" else "---", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = zoneColor)
                    Text("BPM", fontSize = 16.sp, color = Color.Gray)
                    if (zone > 0) { Text("Зона $zone", fontSize = 14.sp, color = zoneColor, fontWeight = FontWeight.Bold); Text(getZoneName(zone), fontSize = 12.sp, color = zoneColor) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(8.dp)) {
                Text("График пульса", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                HeartRateChart(hrHistory = hrHistory, z1 = zone1Max, z2 = zone2Max, z3 = zone3Max, z4 = zone4Max)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onStopTraining, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)), shape = RoundedCornerShape(14.dp)) {
            Text("ЗАВЕРШИТЬ ТРЕНИРОВКУ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SessionDetailScreen(session: MainActivity.TrainingSession?, zone1Max: Int, zone2Max: Int, zone3Max: Int, zone4Max: Int, onBack: () -> Unit) {
    if (session == null) return
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text("Детали тренировки", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(session.date, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Длит.", fontSize = 12.sp, color = Color.Gray)
                        Text(formatDuration(session.durationSeconds), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Макс", fontSize = 12.sp, color = Color.Gray)
                        Text("${session.maxBPM}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF44336))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Сред", fontSize = 12.sp, color = Color.Gray)
                        Text("${session.avgBPM}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Зоны пульса", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        listOf(1 to "Отдых", 2 to "Жиросжигание", 3 to "Кардио", 4 to "Силовая", 5 to "Макс.").forEach { (zone, name) ->
            val pct = session.zonePercentages[zone] ?: 0f
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(getZoneColor(zone)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("$name (${getZoneRange(zone, zone1Max, zone2Max, zone3Max, zone4Max)})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(getZoneDescription(zone), fontSize = 10.sp, color = Color.Gray)
                    }
                    Text("${pct.roundToInt()}%", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = getZoneColor(zone))
                }
            }
        }
        if (session.hrData.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("График пульса", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            HeartRateChart(hrHistory = session.hrData.takeLast(60), z1 = zone1Max, z2 = zone2Max, z3 = zone3Max, z4 = zone4Max)
        }
    }
}

@Composable
fun SettingsScreen(z1: Int, z2: Int, z3: Int, z4: Int, onSave: (Int, Int, Int, Int) -> Unit, onBack: () -> Unit) {
    var nz1 by remember { mutableStateOf(z1.toString()) }
    var nz2 by remember { mutableStateOf(z2.toString()) }
    var nz3 by remember { mutableStateOf(z3.toString()) }
    var nz4 by remember { mutableStateOf(z4.toString()) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text("Настройка зон", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = getZoneColor(1).copy(alpha = 0.1f))) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(getZoneColor(1)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Зона 1: Отдых", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = getZoneColor(1))
                        Text(getZoneDescription(1), fontSize = 10.sp, color = Color.Gray)
                    }
                    Text("70 - ", fontSize = 14.sp)
                    OutlinedTextField(value = nz1, onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) nz1 = it },
                        modifier = Modifier.width(65.dp).height(44.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
                }
            }
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = getZoneColor(2).copy(alpha = 0.1f))) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(getZoneColor(2)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Зона 2: Жиросжигание 🔥", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = getZoneColor(2))
                        Text(getZoneDescription(2), fontSize = 10.sp, color = Color.Gray)
                    }
                    Text("$nz1 - ", fontSize = 14.sp)
                    OutlinedTextField(value = nz2, onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) nz2 = it },
                        modifier = Modifier.width(65.dp).height(44.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
                }
            }
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = getZoneColor(3).copy(alpha = 0.1f))) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(getZoneColor(3)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Зона 3: Кардио", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = getZoneColor(3))
                        Text(getZoneDescription(3), fontSize = 10.sp, color = Color.Gray)
                    }
                    Text("$nz2 - ", fontSize = 14.sp)
                    OutlinedTextField(value = nz3, onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) nz3 = it },
                        modifier = Modifier.width(65.dp).height(44.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
                }
            }
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = getZoneColor(4).copy(alpha = 0.1f))) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(getZoneColor(4)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Зона 4: Силовая 💪", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = getZoneColor(4))
                        Text(getZoneDescription(4), fontSize = 10.sp, color = Color.Gray)
                    }
                    Text("$nz3 - ", fontSize = 14.sp)
                    OutlinedTextField(value = nz4, onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) nz4 = it },
                        modifier = Modifier.width(65.dp).height(44.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
                }
            }
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = getZoneColor(5).copy(alpha = 0.1f))) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(getZoneColor(5)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Зона 5: Максимальная ⚠️", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = getZoneColor(5))
                        Text(getZoneDescription(5), fontSize = 10.sp, color = Color.Gray)
                    }
                    Text("$nz4 - 180", fontSize = 14.sp, color = Color.Gray)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                val v1 = nz1.toIntOrNull() ?: return@Button; val v2 = nz2.toIntOrNull() ?: return@Button
                val v3 = nz3.toIntOrNull() ?: return@Button; val v4 = nz4.toIntOrNull() ?: return@Button
                if (v1 < v2 && v2 < v3 && v3 < v4 && v1 >= 70 && v4 <= 180) onSave(v1, v2, v3, v4)
            }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("СОХРАНИТЬ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
