package com.example.coospohrm

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

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

    // Training
    private val _training = MutableStateFlow(TrainingState())
    val training: StateFlow<TrainingState> = _training.asStateFlow()

    // Navigation
    private val _screen = MutableStateFlow<Screen>(Screen.Main)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    // Калории по реальному времени
    private var lastHrTickMs: Long = 0L

    // Reconnect backoff
    private var reconnectAttempt = 0
    private val reconnectRunnable = Runnable { ble.connectToPairedDevice() }

    init {
        viewModelScope.launch {
            ble.state.collect { st ->
                if (!st.isConnected && reconnectAttempt > 0) scheduleReconnect()
                if (st.isConnected) reconnectAttempt = 0
            }
        }
    }

    fun navigate(s: Screen) { _screen.value = s }

    // ---------- BLE control ----------
    fun connect() { reconnectAttempt = 1; ble.connectToPairedDevice() }
    fun disconnect() {
        handler.removeCallbacks(reconnectRunnable)
        reconnectAttempt = 0
        ble.disconnect()
    }
    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        if (reconnectAttempt > 6) return                              // лимит
        val delay = (2.0.pow(min(reconnectAttempt, 6)) * 1000L).toLong() // 2,4,8,16,32,64s
        reconnectAttempt++
        handler.postDelayed(reconnectRunnable, delay)
    }

    // ---------- Settings ----------
    fun saveSettings(z: HeartRateZones, w: Float, ageYears: Int) {
        _zones.value = z; _weight.value = w; _age.value = ageYears
        viewModelScope.launch(Dispatchers.IO) {
            repo.saveZones(z); repo.saveWeight(w); repo.saveAge(ageYears)
        }
    }

    // ---------- Training ----------
    fun selectActivityAndStart(a: ActivityType) {
        lastHrTickMs = 0L
        _training.value = TrainingState(
            isTraining = true,
            startedAtMs = System.currentTimeMillis(),
            activity = a,
        )
        _screen.value = Screen.Training
    }

    fun requestStopTraining(): TrainingState = _training.value
    fun stopTraining() {
        _training.update { it.copy(isTraining = false) }
        _screen.value = Screen.Main
    }

    fun saveTrainingAndStop() {
        val t = _training.value
        val duration = ((System.currentTimeMillis() - t.startedAtMs) / 1000).toInt()
        val avg = if (t.hrHistory.isNotEmpty()) t.hrHistory.average().roundToInt() else 0
        val z = _zones.value
        val zoneCounts = mutableMapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0)
        t.hrHistory.forEach { hr -> val zn = z.zoneOf(hr); zoneCounts[zn] = (zoneCounts[zn] ?: 0) + 1 }
        val total = t.hrHistory.size
        val zp = zoneCounts.mapValues { if (total > 0) it.value.toFloat() / total * 100f else 0f }
        val session = TrainingSession(
            durationSeconds = duration, maxBPM = t.maxBPM, avgBPM = avg,
            hrData = t.hrHistory, zonePercentages = zp, calories = t.totalCalories,
            activityType = t.activity.displayName, userWeight = _weight.value, userAge = _age.value,
        )
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

    // ---------- HR tick (от BleManager) ----------
    private fun onHeartRate(hr: Int) {
        val now = SystemClock.elapsedRealtime()
        val t = _training.value
        val newHistoryGlobal = t.hrHistory // мы используем training.hrHistory для графика

        if (!t.isTraining) { lastHrTickMs = now; return }

        val dtMs = if (lastHrTickMs == 0L) 0L else (now - lastHrTickMs).coerceIn(0L, 5000L)
        lastHrTickMs = now

        // Калории через MET с учётом возраста: kcal = MET * weight * (dt_hours)
        // плюс мягкая поправка по интенсивности HR относительно maxHR
        val maxHr = (220 - _age.value).coerceAtLeast(60)
        val intensity = (hr.toFloat() / maxHr.toFloat()).coerceIn(0.4f, 1.4f)
        val kcalPerSecond = (t.activity.met * _weight.value * 0.0175 / 60.0).toFloat() * intensity
        val deltaKcal = kcalPerSecond * (dtMs / 1000f)

        val newHr = (t.hrHistory + hr).takeLast(MAX_HR_POINTS)
        _training.update {
            it.copy(
                durationSeconds = ((System.currentTimeMillis() - it.startedAtMs) / 1000).toInt(),
                maxBPM = maxOf(it.maxBPM, hr),
                totalCalories = it.totalCalories + deltaKcal,
                caloriesPerHour = kcalPerSecond * 3600f,
                hrHistory = newHr,
            )
        }
    }

    override fun onCleared() {
        handler.removeCallbacksAndMessages(null)
        ble.disconnect()
        super.onCleared()
    }

    companion object { const val MAX_HR_POINTS = 600 }
}
