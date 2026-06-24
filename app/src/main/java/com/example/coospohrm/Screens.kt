package com.example.coospohrm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.util.Locale
import kotlin.math.roundToInt

fun formatDuration(s: Int): String =
    String.format(Locale.US, "%d:%02d", s / 60, s % 60)

fun zoneColor(z: Int): Color = when (z) {
    1 -> Color(0xFF4CAF50); 2 -> Color(0xFF2196F3); 3 -> Color(0xFFFFC107)
    4 -> Color(0xFFFF69B4); 5 -> Color(0xFFF44336); else -> Color.Gray
}
fun zoneName(z: Int): String = when (z) {
    1 -> "Отдых"; 2 -> "Жиросжигание"; 3 -> "Кардио"; 4 -> "Силовая"; 5 -> "Максимальная"; else -> ""
}
fun zoneDesc(z: Int): String = when (z) {
    1 -> "Восстановление и разминка"; 2 -> "🔥 Оптимальное жиросжигание"
    3 -> "Укрепление сердца и легких"; 4 -> "💪 Развитие силы"
    5 -> "⚠️ Максимальная нагрузка"; else -> ""
}
fun zoneRange(z: Int, hz: HeartRateZones): String = when (z) {
    1 -> "70-${hz.z1}"; 2 -> "${hz.z1}-${hz.z2}"; 3 -> "${hz.z2}-${hz.z3}"
    4 -> "${hz.z3}-${hz.z4}"; 5 -> "${hz.z4}-200"; else -> ""
}
fun batteryColor(l: Int): Color = when {
    l > 50 -> Color(0xFF4CAF50); l > 20 -> Color(0xFFFFC107); else -> Color(0xFFF44336)
}

// ---------- HR chart ----------
@Composable
fun HeartRateChart(hr: List<Int>, hz: HeartRateZones) {
    if (hr.isEmpty()) return
    val minH = 70f; val maxH = 200f; val mp = 60
    val zc = listOf(
        Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFFFC107),
        Color(0xFFFF69B4), Color(0xFFF44336),
    )
    Row(Modifier.fillMaxWidth().height(180.dp)) {
        Column(Modifier.width(35.dp).fillMaxHeight(), Arrangement.SpaceBetween) {
            listOf("200", "${hz.z4}", "${hz.z3}", "${hz.z2}", "${hz.z1}", "70")
                .forEachIndexed { i, s ->
                    Text(s, fontSize = 8.sp, color = when (i) {
                        0 -> zc[4]; 1 -> zc[3]; 2 -> zc[2]; 3 -> zc[1]; else -> zc[0]
                    })
                }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val w = size.width; val h = size.height; val r = maxH - minH
            val ranges = listOf(
                70f to hz.z1.toFloat(), hz.z1.toFloat() to hz.z2.toFloat(),
                hz.z2.toFloat() to hz.z3.toFloat(), hz.z3.toFloat() to hz.z4.toFloat(),
                hz.z4.toFloat() to maxH,
            )
            ranges.forEachIndexed { i, (lo, hi) ->
                drawRect(zc[i].copy(alpha = 0.15f),
                    Offset(0f, h - ((hi - minH) / r * h)),
                    Size(w, ((hi - lo) / r * h)))
            }
            if (hr.size >= 2) {
                val sx = w / (mp - 1)
                val off = mp - hr.size.coerceAtMost(mp)
                val visible = hr.takeLast(mp)
                val p = Path()
                visible.forEachIndexed { i, v ->
                    val x = (off + i) * sx
                    val y = h - ((v - minH) / r * h)
                    if (i == 0) p.moveTo(x, y.coerceIn(0f, h))
                    else p.lineTo(x, y.coerceIn(0f, h))
                }
                drawPath(p, Color.White.copy(alpha = 0.3f), style = Stroke(5f))
                drawPath(p, Color(0xFF2196F3), style = Stroke(2f))
            }
        }
    }
}

// ---------- MAIN ----------
@Composable
fun MainScreen(
    ble: BleState, zones: HeartRateZones, history: List<TrainingSession>,
    onStartClick: () -> Unit, onSessionClick: (TrainingSession) -> Unit,
    onSessionDelete: (TrainingSession) -> Unit, onSettingsClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(ble.deviceName, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ble.batteryLevel >= 0) {
                    Text("${ble.batteryLevel}%", fontSize = 14.sp,
                        color = batteryColor(ble.batteryLevel), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, null, tint = Color.Gray)
                }
            }
        }
        Text(ble.statusText, fontSize = 12.sp,
            color = if (ble.isConnected) Color(0xFF4CAF50) else Color.Gray)
        Spacer(Modifier.height(16.dp))

        val zone = if (ble.heartRate > 0) zones.zoneOf(ble.heartRate) else 0
        val zc = if (zone > 0) zoneColor(zone) else Color.Gray
        Box(Modifier.size(200.dp).clip(CircleShape), Alignment.Center) {
            Surface(Modifier.fillMaxSize(), CircleShape, color = zc.copy(alpha = 0.1f)) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(if (ble.heartRate > 0) "${ble.heartRate}" else "---",
                        fontSize = 64.sp, fontWeight = FontWeight.Bold, color = zc)
                    Text("BPM", fontSize = 18.sp, color = zc)
                    if (zone > 0) {
                        Text("Зона $zone: ${zoneName(zone)}",
                            fontSize = 14.sp, color = zc, fontWeight = FontWeight.Bold)
                        Text(zoneRange(zone, zones), fontSize = 12.sp, color = zc.copy(alpha = 0.7f))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (ble.isConnected) {
            Button(onClick = onStartClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                shape = RoundedCornerShape(16.dp)) {
                Text("НАЧАТЬ ТРЕНИРОВКУ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(24.dp))

        if (history.isNotEmpty()) {
            Text("История тренировок", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            history.forEach { s ->
                Card(
                    onClick = { onSessionClick(s) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.activityType, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50))
                            Text(s.date, fontSize = 12.sp, color = Color.Gray)
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text("Макс: ${s.maxBPM}", fontSize = 12.sp)
                                Text("Сред: ${s.avgBPM}", fontSize = 12.sp)
                                Text(formatDuration(s.durationSeconds), fontSize = 12.sp)
                            }
                            Text("🔥 ${s.calories.roundToInt()} ккал", fontSize = 12.sp,
                                color = Color(0xFFFF9800), fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { onSessionDelete(s) }) {
                            Icon(Icons.Default.Delete, null, tint = Color(0xFFF44336))
                        }
                    }
                }
            }
        } else {
            Text("История тренировок пуста", fontSize = 14.sp, color = Color.Gray)
        }
    }
}

// ---------- TRAINING ----------
@Composable
fun TrainingScreen(
    ble: BleState, training: TrainingState, zones: HeartRateZones, onStopClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(training.activity.displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = Color(0xFF4CAF50))
        Text("ТРЕНИРОВКА", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(formatDuration(training.durationSeconds), fontSize = 14.sp, color = Color.Gray)
        Spacer(Modifier.height(12.dp))

        val zone = if (ble.heartRate > 0) zones.zoneOf(ble.heartRate) else 0
        val zc = if (zone > 0) zoneColor(zone) else Color.Gray
        Box(Modifier.size(160.dp).clip(CircleShape), Alignment.Center) {
            Surface(Modifier.fillMaxSize(), CircleShape, color = zc.copy(alpha = 0.1f)) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(if (ble.heartRate > 0) "${ble.heartRate}" else "---",
                        fontSize = 56.sp, fontWeight = FontWeight.Bold, color = zc)
                    Text("BPM", fontSize = 16.sp, color = Color.Gray)
                    if (zone > 0) {
                        Text("Зона $zone", fontSize = 14.sp, color = zc, fontWeight = FontWeight.Bold)
                        Text(zoneName(zone), fontSize = 12.sp, color = zc)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            CalorieCard("🔥 Сожжено", training.totalCalories.roundToInt(), "ккал", Modifier.weight(1f))
            CalorieCard("🔥 В час", training.caloriesPerHour.roundToInt(), "ккал/ч", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(8.dp)) {
                Text("График пульса", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                HeartRateChart(training.hrHistory, zones)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStopClick,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
            shape = RoundedCornerShape(14.dp)) {
            Text("ЗАВЕРШИТЬ ТРЕНИРОВКУ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CalorieCard(title: String, value: Int, unit: String, modifier: Modifier) {
    Card(modifier.padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 11.sp, color = Color.Gray)
            Text("$value", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
            Text(unit, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

// ---------- SESSION DETAIL ----------
@Composable
fun SessionDetailScreen(session: TrainingSession?, zones: HeartRateZones, onBack: () -> Unit) {
    if (session == null) { onBack(); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Детали тренировки", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(session.activityType, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50))
                Text(session.date, fontSize = 14.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    StatCol("Длит.", formatDuration(session.durationSeconds), Color.Black)
                    StatCol("Макс", "${session.maxBPM}", Color(0xFFF44336))
                    StatCol("Сред", "${session.avgBPM}", Color(0xFF2196F3))
                    StatCol("Калории", "${session.calories.roundToInt()}", Color(0xFFFF9800))
                }
                Text("Возраст: ${session.userAge}  Вес: ${session.userWeight.roundToInt()} кг",
                    fontSize = 11.sp, color = Color.Gray)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Зоны пульса", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        (1..5).forEach { z ->
            val pct = session.zonePercentages[z] ?: 0f
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(zoneColor(z)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${zoneName(z)} (${zoneRange(z, zones)})",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(zoneDesc(z), fontSize = 10.sp, color = Color.Gray)
                    }
                    Text("${pct.roundToInt()}%", fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, color = zoneColor(z))
                }
            }
        }
        if (session.hrData.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("График пульса", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            HeartRateChart(session.hrData.takeLast(60), zones)
        }
    }
}

@Composable
private fun StatCol(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

// ---------- SETTINGS (с возрастом) ----------
@Composable
fun SettingsScreen(
    zones: HeartRateZones, weight: Float, age: Int,
    onSave: (HeartRateZones, Float, Int) -> Unit, onBack: () -> Unit,
) {
    var nz1 by rememberSaveable { mutableStateOf(zones.z1.toString()) }
    var nz2 by rememberSaveable { mutableStateOf(zones.z2.toString()) }
    var nz3 by rememberSaveable { mutableStateOf(zones.z3.toString()) }
    var nz4 by rememberSaveable { mutableStateOf(zones.z4.toString()) }
    var nWeight by rememberSaveable { mutableStateOf(weight.toString()) }
    var nAge by rememberSaveable { mutableStateOf(age.toString()) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Настройки", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {

            // Вес
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("⚖️ Ваш вес", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Для расчёта калорий", fontSize = 11.sp, color = Color.Gray)
                    }
                    OutlinedTextField(
                        value = nWeight,
                        onValueChange = {
                            if (it.length <= 5 && it.all { c -> c.isDigit() || c == '.' }) nWeight = it
                        },
                        modifier = Modifier.width(90.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center),
                        singleLine = true,
                    )
                    Text(" кг", fontSize = 14.sp, color = Color.Gray)
                }
            }

            // Возраст (НОВОЕ)
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🎂 Ваш возраст", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Для maxHR = 220 − возраст", fontSize = 11.sp, color = Color.Gray)
                    }
                    OutlinedTextField(
                        value = nAge,
                        onValueChange = {
                            if (it.length <= 3 && it.all { c -> c.isDigit() }) nAge = it
                        },
                        modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center),
                        singleLine = true,
                    )
                    Text(" лет", fontSize = 14.sp, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Настройка пульсовых зон", fontSize = 16.sp, fontWeight = FontWeight.Bold)

            ZoneRow(1, "Отдых", prevLabel = "70 - ",
                value = nz1, onChange = { nz1 = it })
            ZoneRow(2, "Жиросжигание 🔥", prevLabel = "$nz1 - ",
                value = nz2, onChange = { nz2 = it })
            ZoneRow(3, "Кардио", prevLabel = "$nz2 - ",
                value = nz3, onChange = { nz3 = it })
            ZoneRow(4, "Силовая 💪", prevLabel = "$nz3 - ",
                value = nz4, onChange = { nz4 = it })

            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(containerColor = zoneColor(5).copy(alpha = 0.1f))) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(zoneColor(5)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Зона 5: Максимальная ⚠️", fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, color = zoneColor(5))
                        Text(zoneDesc(5), fontSize = 10.sp, color = Color.Gray)
                    }
                    Text("$nz4 - 200", fontSize = 14.sp, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val v1 = nz1.toIntOrNull(); val v2 = nz2.toIntOrNull()
                    val v3 = nz3.toIntOrNull(); val v4 = nz4.toIntOrNull()
                    val w = nWeight.toFloatOrNull(); val a = nAge.toIntOrNull()
                    if (v1 != null && v2 != null && v3 != null && v4 != null &&
                        w != null && a != null &&
                        v1 < v2 && v2 < v3 && v3 < v4 &&
                        v1 in 50..200 && v4 in 60..220 &&
                        w in 20f..300f && a in 5..120
                    ) onSave(HeartRateZones(v1, v2, v3, v4), w, a)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("СОХРАНИТЬ", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ZoneRow(z: Int, name: String, prevLabel: String, value: String, onChange: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = zoneColor(z).copy(alpha = 0.1f))) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(zoneColor(z)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Зона $z: $name", fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, color = zoneColor(z))
                Text(zoneDesc(z), fontSize = 10.sp, color = Color.Gray)
            }
            Text(prevLabel, fontSize = 14.sp)
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) onChange(it) },
                modifier = Modifier.width(72.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center),
                singleLine = true,
            )
        }
    }
}
