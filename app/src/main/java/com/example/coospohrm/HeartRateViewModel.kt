package com.example.coospohrm

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TrainingState(
    val isTraining: Boolean = false,
    val startedAtMs: Long = 0L,
    val durationSeconds: Int = 0,
    val maxBPM: Int = 0,
    val totalCalories: Float = 0f,
    val caloriesPerHour: Float = 0f,
    val activity: ActivityType = ActivityType.FITNESS_MODERATE,
    val hrHistory: List<Int> = emptyList(),
)

class HeartRateViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TrainingRepository(app)
    private val handler = Handler(Looper.getMainLooper())
    private val TAG = "HR_VM"

    // BLE
    private val ble = BleHeartRateManager(app) { hr -> onHeartRate(hr) }
    val bleState: StateFlow<BleState> get() = ble.state

    // Settings
    private val _zones = MutableStateFlow(repo.loadZones())
    val zones: StateFlow<HeartRateZones> = _zones.asStateFlow()

    private val _weight = MutableStateFlow(repo.loadWeight())
    val weight: StateFlow<Float> = _weight.asStateFlow()

    private val _age = MutableStateFlow(repo.loadAge())
    val age: StateFlow<Int> = _age.asStateFlow()

    // History
    private val _history = MutableStateFlow(repo.loadHistory())
    val history: StateFlow<List<TrainingSession>> = _history.asStateFlow()

    // Sleep history
    private val _sleepHistory = MutableStateFlow(repo.loadSleepHistory())
    val sleepHistory: StateFlow<List<SleepSession>> = _sleepHistory.asStateFlow()

    // Training
    private val _training = MutableStateFlow(TrainingState())
    val training: StateFlow<TrainingState> = _training.asStateFlow()

    // Sleep
    private val _sleepSession = MutableStateFlow<SleepSession?>(null)
    val sleepSession: StateFlow<SleepSession?> = _sleepSession.asStateFlow()

    private var sleepStartMs: Long = 0L
    private val sleepHrData = mutableListOf<Int>()
    private val sleepAwakeningTimes = mutableListOf<String>()
    private var sleepAwakenings = 0
    private var sleepMinHR = Int.MAX_VALUE
    private var sleepMaxHR = 0

    // Navigation
    private val _screen = MutableStateFlow<Screen>(Screen.Main)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private var lastHrTickMs: Long = 0L
    private var reconnectAttempt = 0
    private val reconnectRunnable = Runnable { ble.connectToPairedDevice() }

    init {
        viewModelScope.launch {
            ble.state.collect { st ->
                if (!st.isConnected && reconnectAttempt > 0) scheduleReconnect()
                if (st.isConnected) reconnectAttempt = 0
            }
        }
        Log.d(TAG, "init: sleepHistory size = ${_sleepHistory.value.size}")
    }

    fun navigate(s: Screen) { _screen.value = s }

    fun connect() { reconnectAttempt = 1; ble.connectToPairedDevice() }
    fun disconnect() { handler.removeCallbacks(reconnectRunnable); reconnectAttempt = 0; ble.disconnect() }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        if (reconnectAttempt > 6) return
        val delay = (2.0.pow(min(reconnectAttempt, 6)) * 1000L).toLong()
        reconnectAttempt++
        handler.postDelayed(reconnectRunnable, delay)
    }

    fun saveSettings(z: HeartRateZones, w: Float, ageYears: Int) {
        _zones.value = z; _weight.value = w; _age.value = ageYears
        viewModelScope.launch(Dispatchers.IO) { repo.saveZones(z); repo.saveWeight(w); repo.saveAge(ageYears) }
    }

    fun selectActivityAndStart(a: ActivityType) {
        lastHrTickMs = 0L
        _training.value = TrainingState(isTraining = true, startedAtMs = System.currentTimeMillis(), activity = a)
        _screen.value = Screen.Training
    }

    fun stopTraining() { _training.update { it.copy(isTraining = false) }; _screen.value = Screen.Main }

    fun saveTrainingAndStop() {
        val t = _training.value
        val duration = ((System.currentTimeMillis() - t.startedAtMs) / 1000).toInt()
        val avg = if (t.hrHistory.isNotEmpty()) t.hrHistory.average().roundToInt() else 0
        val z = _zones.value
        val zoneCounts = mutableMapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0)
        t.hrHistory.forEach { hr -> val zn = z.zoneOf(hr); zoneCounts[zn] = (zoneCounts[zn] ?: 0) + 1 }
        val total = t.hrHistory.size
        val zp = zoneCounts.mapValues { if (total > 0) it.value.toFloat() / total * 100f else 0f }
        val session = TrainingSession(durationSeconds = duration, maxBPM = t.maxBPM, avgBPM = avg, hrData = t.hrHistory, zonePercentages = zp, calories = t.totalCalories, activityType = t.activity.displayName, userWeight = _weight.value, userAge = _age.value)
        val newList = listOf(session) + _history.value
        _history.value = newList
        viewModelScope.launch(Dispatchers.IO) { repo.saveHistory(newList) }
        stopTraining()
    }

    fun deleteSession(id: String) {
        val newList = _history.value.filterNot { it.id == id }
        _history.value = newList
        viewModelScope.launch(Dispatchers.IO) { repo.saveHistory(newList) }
    }

    // ---------- SLEEP ----------
    fun startSleepMode() {
        sleepStartMs = System.currentTimeMillis()
        sleepHrData.clear()
        sleepAwakeningTimes.clear()
        sleepAwakenings = 0
        sleepMinHR = Int.MAX_VALUE
        sleepMaxHR = 0
        _sleepSession.value = SleepSession(
            id = UUID.randomUUID().toString(),
            startTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        )
        _screen.value = Screen.Sleep
    }

    fun stopSleepMode() {
        val session = _sleepSession.value
        Log.d(TAG, "stopSleepMode: session=$session, duration=${session?.durationMinutes}")

        _sleepSession.value = null
        _screen.value = Screen.Main

        if (session == null) return
        // Убрали проверку на минимальную длительность — сохраняем всегда
        // if (session.durationMinutes < 1) { ... return }

        val finalSession = session.copy(
            date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()),
            hrData = sleepHrData.toList(),
        )

        val newList = listOf(finalSession) + _sleepHistory.value
        _sleepHistory.value = newList
        Log.d(TAG, "Сон сохранен! Всего записей: ${newList.size}")

        viewModelScope.launch(Dispatchers.IO) {
            repo.saveSleepHistory(newList)
            Log.d(TAG, "Сон записан в SharedPreferences")
        }
    }

    fun deleteSleepSession(id: String) {
        val newList = _sleepHistory.value.filterNot { it.id == id }
        _sleepHistory.value = newList
        viewModelScope.launch(Dispatchers.IO) { repo.saveSleepHistory(newList) }
    }

    // ---------- HR tick ----------
    private fun onHeartRate(hr: Int) {
        val now = SystemClock.elapsedRealtime()

        val t = _training.value
        if (t.isTraining && hr > 0) {
            val dtMs = if (lastHrTickMs == 0L) 0L else (now - lastHrTickMs).coerceIn(0L, 5000L)
            lastHrTickMs = now
            val maxHr = (220 - _age.value).coerceAtLeast(60)
            val intensity = (hr.toFloat() / maxHr.toFloat()).coerceIn(0.4f, 1.4f)
            val kcalPerSecond = (t.activity.met * _weight.value * 0.0175 / 60.0).toFloat() * intensity
            val deltaKcal = kcalPerSecond * (dtMs / 1000f)
            val newHr = (t.hrHistory + hr).takeLast(600)
            _training.update {
                it.copy(durationSeconds = ((System.currentTimeMillis() - it.startedAtMs) / 1000).toInt(), maxBPM = maxOf(it.maxBPM, hr), totalCalories = it.totalCalories + deltaKcal, caloriesPerHour = kcalPerSecond * 3600f, hrHistory = newHr)
            }
        }

        // Sleep
        if (_sleepSession.value != null && hr > 0) {
            sleepHrData.add(hr)
            if (hr < sleepMinHR) sleepMinHR = hr
            if (hr > sleepMaxHR) sleepMaxHR = hr

            if (sleepHrData.size >= 2) {
                if (hr - sleepHrData[sleepHrData.size - 2] > 15) {
                    sleepAwakenings++
                    sleepAwakeningTimes.add(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                }
            }

            val duration = ((System.currentTimeMillis() - sleepStartMs) / 60000).toInt()
            val avg = if (sleepHrData.isNotEmpty()) sleepHrData.average().roundToInt() else 0
            val resting = if (sleepHrData.size >= 10) sleepHrData.sorted().take(sleepHrData.size / 4).average().roundToInt() else avg

            val quality = when {
                sleepHrData.size < 30 -> SleepQuality.UNKNOWN
                avg < resting + 5 && sleepAwakenings <= 2 -> SleepQuality.EXCELLENT
                avg < resting + 10 && sleepAwakenings <= 5 -> SleepQuality.GOOD
                avg < resting + 15 && sleepAwakenings <= 10 -> SleepQuality.FAIR
                else -> SleepQuality.POOR
            }

            _sleepSession.value = SleepSession(
                id = _sleepSession.value?.id ?: UUID.randomUUID().toString(),
                startTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sleepStartMs)),
                endTime = if (duration > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) else "",
                durationMinutes = duration,
                minHR = if (sleepMinHR == Int.MAX_VALUE) 0 else sleepMinHR,
                maxHR = sleepMaxHR,
                avgHR = avg,
                restingHR = resting,
                awakenings = sleepAwakenings,
                awakeningTimes = sleepAwakeningTimes.toList(),
                qualityName = quality.displayName,
                qualityDescription = quality.description,
            )
        }
    }

    override fun onCleared() {
        handler.removeCallbacksAndMessages(null)
        ble.disconnect()
        super.onCleared()
    }
}