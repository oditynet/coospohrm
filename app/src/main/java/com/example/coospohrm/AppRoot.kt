package com.example.coospohrm

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun AppRoot(vm: HeartRateViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val ble by vm.bleState.collectAsStateWithLifecycle()
    val zones by vm.zones.collectAsStateWithLifecycle()
    val weight by vm.weight.collectAsStateWithLifecycle()
    val age by vm.age.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val training by vm.training.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var showActivityDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TrainingSession?>(null) }

    when (val s = screen) {
        Screen.Main -> MainScreen(
            ble = ble, zones = zones, history = history,
            onStartClick = { showActivityDialog = true },
            onSessionClick = { vm.navigate(Screen.SessionDetail(it.id)) },
            onSessionDelete = { pendingDelete = it },
            onSettingsClick = { vm.navigate(Screen.Settings) },
        )
        Screen.Training -> TrainingScreen(
            ble = ble, training = training, zones = zones,
            onStopClick = { showSaveDialog = true }
        )
        Screen.Settings -> SettingsScreen(
            zones = zones, weight = weight, age = age,
            onSave = { z, w, a -> vm.saveSettings(z, w, a); vm.navigate(Screen.Main) },
            onBack = { vm.navigate(Screen.Main) },
        )
        is Screen.SessionDetail -> {
            val session = history.firstOrNull { it.id == s.sessionId }
            SessionDetailScreen(session, zones) { vm.navigate(Screen.Main) }
        }
    }

    if (showActivityDialog) ActivityPickerDialog(
        current = training.activity,
        onDismiss = { showActivityDialog = false },
        onPick = {
            showActivityDialog = false
            vm.selectActivityAndStart(it)
            HeartRateForegroundService.start(ctx, "Тренировка: ${it.displayName}", "Запись пульса…")
        },
    )

    if (showSaveDialog) {
        val cur = training
        val duration = ((System.currentTimeMillis() - cur.startedAtMs) / 1000).toInt()
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Сохранить тренировку?") },
            text = {
                Text(
                    "Тип: ${cur.activity.displayName}\n" +
                    "Длительность: ${formatDuration(duration)}\n" +
                    "Макс BPM: ${cur.maxBPM}\n" +
                    "Калории: ${cur.totalCalories.roundToInt()} ккал"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    vm.saveTrainingAndStop()
                    HeartRateForegroundService.stop(ctx)
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    vm.stopTraining()
                    HeartRateForegroundService.stop(ctx)
                }) { Text("Отмена") }
            },
        )
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить тренировку?") },
            text = { Text("Действие нельзя отменить") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSession(s.id); pendingDelete = null }) {
                    Text("Удалить", color = Color(0xFFF44336))
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ActivityPickerDialog(
    current: ActivityType, onDismiss: () -> Unit, onPick: (ActivityType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите тип тренировки") },
        text = {
            LazyColumn(Modifier.height(400.dp)) {
                items(ActivityType.entries.toList()) { a ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onPick(a) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (a == current)
                                Color(0xFF4CAF50).copy(alpha = 0.1f) else Color.Transparent
                        )
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(a.displayName, Modifier.weight(1f), fontSize = 14.sp)
                            Text("MET: ${a.met}", fontSize = 12.sp, color = Color.Gray)
                            if (a == current) Icon(
                                Icons.Default.Check, null,
                                tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
