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
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TrainingState(
    val isTraining: Boolean = false,
    val startedAtMs: Long = 0L,
    val activeSeconds: Int = 0,
    val totalDurationSeconds: Int = 0,
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

    private val ble = BleHeartRateManager(app) { hr, rr -> onHeartRate(hr, rr) }
    val bleState: StateFlow<BleState> get() = ble.state

    private val _zones = MutableStateFlow(repo.loadZones())
    val zones: StateFlow<HeartRateZones> = _zones.asStateFlow()

    private val _weight = MutableStateFlow(repo.loadWeight())
    val weight: StateFlow<Float> = _weight.asStateFlow()

    private val _age = MutableStateFlow(repo.loadAge())
    val age: StateFlow<Int> = _age.asStateFlow()

    private val _isMale = MutableStateFlow(repo.loadGender())
    val isMale: StateFlow<Boolean> = _isMale.asStateFlow()

    private val _history = MutableStateFlow(repo.loadHistory())
    val history: StateFlow<List<TrainingSession>> = _history.asStateFlow()

    private val _sleepHistory = MutableStateFlow(repo.loadSleepHistory())
    val sleepHistory: StateFlow<List<SleepSession>> = _sleepHistory.asStateFlow()

    private val _training = MutableStateFlow(TrainingState())
    val training: StateFlow<TrainingState> = _training.asStateFlow()

    private val _sleepSession = MutableStateFlow<SleepSession?>(null)
    val sleepSession: StateFlow<SleepSession?> = _sleepSession.asStateFlow()

    private var sleepStartMs: Long = 0L
    private val sleepHrData = mutableListOf<Int>()
    private val sleepAwakeningTimes = mutableListOf<String>()
    private var sleepAwakenings = 0
    private var sleepMinHR = Int.MAX_VALUE
    private var sleepMaxHR = 0

    private val _screen = MutableStateFlow<Screen>(Screen.Main)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private var lastHrTickMs: Long = 0L

    // Биохакинг
    private val _biohackingData = MutableStateFlow(repo.loadBiohackingData())
    val biohackingData: StateFlow<BiohackingData> = _biohackingData.asStateFlow()

    private var rrIntervalsBuffer = mutableListOf<Double>()
    private var baselineHR = 0
    private var baselineRMSSD = 0.0

    // Автоопределение пиков и восстановления
    private var peakHR = 0
    private var peakTimestamp = 0L
    private var recoveryHR = 0
    private var isRecovering = false
    private var recoveryStartTimestamp = 0L
    private val recoveryResults = mutableListOf<Int>()

    private val sleepHrBuffer = ArrayDeque<Int>(4)
    private var sleepStartDelayMs = 60_000L
    private var sleepDataCollectionStarted = false

    init {
        Log.d(TAG, "init: sleepHistory size = ${_sleepHistory.value.size}")
        Log.d(TAG, "init: biohackingData hasMorning=${_biohackingData.value.hasMorningData}, hasTraining=${_biohackingData.value.hasTrainingData}")
    }

    fun navigate(s: Screen) { _screen.value = s }
    fun connect() { ble.connectToPairedDevice() }
    fun disconnect() { ble.disconnect() }

    fun saveSettings(z: HeartRateZones, w: Float, a: Int, male: Boolean) {
        _zones.value = z; _weight.value = w; _age.value = a; _isMale.value = male
        viewModelScope.launch(Dispatchers.IO) {
            repo.saveZones(z); repo.saveWeight(w); repo.saveAge(a); repo.saveGender(male)
        }
    }

    fun selectActivityAndStart(a: ActivityType) {
        lastHrTickMs = 0L
        peakHR = 0; peakTimestamp = 0L; recoveryHR = 0
        isRecovering = false; recoveryStartTimestamp = 0L
        recoveryResults.clear()
        _training.value = TrainingState(isTraining = true, startedAtMs = System.currentTimeMillis(), activity = a)
        _screen.value = Screen.Training
    }

    fun stopTraining() { _training.update { it.copy(isTraining = false) }; _screen.value = Screen.Main }

    fun saveTrainingAndStop() {
        val t = _training.value
        val avg = if (t.hrHistory.isNotEmpty()) t.hrHistory.average().roundToInt() else 0
        val z = _zones.value
        val zoneCounts = mutableMapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0)
        t.hrHistory.forEach { hr -> val zn = z.zoneOf(hr); zoneCounts[zn] = (zoneCounts[zn] ?: 0) + 1 }
        val total = t.hrHistory.size
        val zp = zoneCounts.mapValues { if (total > 0) it.value.toFloat() / total * 100f else 0f }
        val session = TrainingSession(
            durationSeconds = t.activeSeconds, maxBPM = t.maxBPM, avgBPM = avg,
            hrData = t.hrHistory, zonePercentages = zp, calories = t.totalCalories,
            activityType = t.activity.displayName, userWeight = _weight.value, userAge = _age.value,
        )
        val newList = listOf(session) + _history.value
        _history.value = newList
        viewModelScope.launch(Dispatchers.IO) { repo.saveHistory(newList) }
        calculatePostTrainingMetrics(t.maxBPM)
        stopTraining()
    }

    fun deleteSession(id: String) {
        val newList = _history.value.filterNot { it.id == id }
        _history.value = newList
        viewModelScope.launch(Dispatchers.IO) { repo.saveHistory(newList) }
    }

    fun startSleepMode() {
        sleepStartMs = System.currentTimeMillis()
        sleepHrData.clear()
        sleepAwakeningTimes.clear()
        sleepAwakenings = 0
        sleepMinHR = Int.MAX_VALUE
        sleepMaxHR = 0
        sleepHrBuffer.clear()
        rrIntervalsBuffer.clear()
        sleepDataCollectionStarted = false

        _sleepSession.value = SleepSession(
            id = UUID.randomUUID().toString(),
            startTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        )
        _screen.value = Screen.Sleep
    }

    fun stopSleepMode() {
        val session = _sleepSession.value
        _sleepSession.value = null
        _screen.value = Screen.Main
        if (session == null) return
        val finalSession = session.copy(
            date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()),
            hrData = sleepHrData.toList(),
        )
        val newList = listOf(finalSession) + _sleepHistory.value
        _sleepHistory.value = newList
        viewModelScope.launch(Dispatchers.IO) { repo.saveSleepHistory(newList) }
        calculateMorningMetrics(finalSession)
    }

    fun deleteSleepSession(id: String) {
        val newList = _sleepHistory.value.filterNot { it.id == id }
        _sleepHistory.value = newList
        viewModelScope.launch(Dispatchers.IO) { repo.saveSleepHistory(newList) }
    }

    // ---------- Биохакинг: утренние метрики ----------
    private fun calculateMorningMetrics(session: SleepSession) {
        val rrMetrics = if (rrIntervalsBuffer.isNotEmpty()) {
            BiohackingCalculator.calculateHRVMetrics(rrIntervalsBuffer)
        } else null
        val rmssd = rrMetrics?.rmssd ?: 0.0

        if (baselineHR == 0 && session.restingHR > 0) {
            baselineHR = session.restingHR
            baselineRMSSD = rmssd
        }

        val readiness = if (rmssd > 0 && baselineRMSSD > 0) {
            BiohackingCalculator.morningReadiness(
                morningHR = session.restingHR, morningRMSSD = rmssd,
                baselineHR = baselineHR, baselineRMSSD = baselineRMSSD,
                sleepHours = session.durationMinutes / 60.0
            )
        } else null

        val current = _biohackingData.value
        _biohackingData.value = current.copy(
            morningReadiness = readiness ?: current.morningReadiness,
            morningRMSSD = if (rmssd > 0) rmssd else current.morningRMSSD,
            morningHRV = when { rmssd > 50 -> "Высокая"; rmssd > 30 -> "Нормальная"; rmssd > 0 -> "Низкая"; else -> current.morningHRV },
            restingHR = if (session.restingHR > 0) session.restingHR else current.restingHR,
            hasMorningData = true,
            lastSleepEnd = session.endTime.ifEmpty { current.lastSleepEnd },
            heartAge = BiohackingCalculator.biologicalHeartAge(
                restingHR = session.restingHR, rmssd = rmssd,
                hrr = current.recoveryRate, chronologicalAge = _age.value
            ),
            heartHealthScore = BiohackingCalculator.heartHealthScore(
                restingHR = session.restingHR, rmssd = rmssd,
                hrr = current.recoveryRate, age = _age.value
            )
        )
        viewModelScope.launch(Dispatchers.IO) { repo.saveBiohackingData(_biohackingData.value) }
    }

    // ---------- Биохакинг: метрики после тренировки ----------
    private fun calculatePostTrainingMetrics(trainingPeakHR: Int) {
        val bestRecovery = if (recoveryResults.isNotEmpty()) {
            recoveryResults.maxOrNull() ?: 0
        } else {
            if (recoveryHR > 0) trainingPeakHR - recoveryHR else 0
        }

        val vo2max = BiohackingCalculator.estimateVO2max(
            restingHR = _biohackingData.value.restingHR,
            maxHR = trainingPeakHR,
            age = _age.value,
            isMale = _isMale.value
        )

        val current = _biohackingData.value
        _biohackingData.value = current.copy(
            vo2max = if (vo2max > 0) vo2max else current.vo2max,
            recoveryRate = if (bestRecovery > 0) bestRecovery else current.recoveryRate,
            peakHR = if (trainingPeakHR > 0) trainingPeakHR else current.peakHR,
            recoveryHR2min = if (recoveryHR > 0) recoveryHR else current.recoveryHR2min,
            hasTrainingData = true,
            lastTrainingEnd = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            heartAge = BiohackingCalculator.biologicalHeartAge(
                restingHR = current.restingHR, rmssd = current.morningRMSSD,
                hrr = bestRecovery, chronologicalAge = _age.value
            ),
            heartHealthScore = BiohackingCalculator.heartHealthScore(
                restingHR = current.restingHR, rmssd = current.morningRMSSD,
                hrr = bestRecovery, age = _age.value
            )
        )
        viewModelScope.launch(Dispatchers.IO) { repo.saveBiohackingData(_biohackingData.value) }
    }

    private fun updateStressIndex(rrIntervals: List<Double>) {
        if (rrIntervals.size < 10) return
        val index = BiohackingCalculator.calculateStressIndex(rrIntervals)
        if (index > 0) {
            val current = _biohackingData.value
            _biohackingData.value = current.copy(
                stressIndex = index,
                stressLevel = BiohackingCalculator.stressLevelFromIndex(index)
            )
        }
    }

    // ---------- HR tick ----------
    private fun onHeartRate(hr: Int, rrIntervals: List<Double>) {
        val nowElapsed = SystemClock.elapsedRealtime()

        if (rrIntervals.isNotEmpty()) {
            rrIntervalsBuffer.addAll(rrIntervals)
            if (rrIntervalsBuffer.size > 1000) rrIntervalsBuffer = rrIntervalsBuffer.takeLast(1000).toMutableList()
            updateStressIndex(rrIntervals)
        }

        val t = _training.value
        if (t.isTraining && hr > 0) {
            val dtMs = if (lastHrTickMs == 0L) 0L else (nowElapsed - lastHrTickMs).coerceIn(0L, 5000L)
            lastHrTickMs = nowElapsed
            val maxHr = (220 - _age.value).coerceAtLeast(60)
            val intensity = (hr.toFloat() / maxHr.toFloat()).coerceIn(0.4f, 1.4f)
            val kcalPerSecond = (t.activity.met * _weight.value * 0.0175 / 60.0).toFloat() * intensity
            val deltaKcal = kcalPerSecond * (dtMs / 1000f)
            val newHr = (t.hrHistory + hr).takeLast(600)
            val activeInc = if (dtMs > 0 && dtMs < 5000) (dtMs / 1000f).roundToInt() else 0

            _training.update {
                it.copy(
                    totalDurationSeconds = ((System.currentTimeMillis() - it.startedAtMs) / 1000).toInt(),
                    activeSeconds = it.activeSeconds + activeInc,
                    maxBPM = maxOf(it.maxBPM, hr),
                    totalCalories = it.totalCalories + deltaKcal,
                    caloriesPerHour = if (it.activeSeconds > 0) it.totalCalories / (it.activeSeconds / 3600f) else kcalPerSecond * 3600f,
                    hrHistory = newHr,
                )
            }

            if (hr > peakHR) {
                peakHR = hr; peakTimestamp = System.currentTimeMillis(); isRecovering = false
            }

            if (!isRecovering && peakHR > 0 && hr < peakHR * 0.85) {
                isRecovering = true; recoveryStartTimestamp = System.currentTimeMillis()
            }

            if (isRecovering && recoveryStartTimestamp > 0) {
                val recoveryTime = (System.currentTimeMillis() - recoveryStartTimestamp) / 1000
                if (recoveryTime in 110..130) {
                    recoveryHR = hr
                    val hrr = peakHR - recoveryHR
                    if (hrr > 0) {
                        recoveryResults.add(hrr)
                        Log.d(TAG, "Восстановление: пик=$peakHR, ч/з 2мин=$recoveryHR, HRR=$hrr")
                    }
                    peakHR = 0; isRecovering = false
                }
            }
        } else if (t.isTraining) {
            _training.update { it.copy(totalDurationSeconds = ((System.currentTimeMillis() - it.startedAtMs) / 1000).toInt()) }
        }

        // Sleep
        if (_sleepSession.value != null && hr > 0) {
            val elapsedSinceStart = System.currentTimeMillis() - sleepStartMs

            if (elapsedSinceStart < sleepStartDelayMs) {
                val remainingSeconds = ((sleepStartDelayMs - elapsedSinceStart) / 1000).toInt()
                _sleepSession.value = _sleepSession.value?.copy(
                    durationMinutes = 0,
                    qualityName = "Подготовка... $remainingSeconds сек"
                )
                return
            }

            if (!sleepDataCollectionStarted) {
                sleepDataCollectionStarted = true
                sleepStartMs = System.currentTimeMillis()
                sleepHrData.clear()
                Log.d(TAG, "Начало сбора данных сна")
            }

            sleepHrData.add(hr)
            sleepHrBuffer.add(hr)

            if (sleepHrBuffer.size > 4) sleepHrBuffer.removeFirst()
            if (hr < sleepMinHR) sleepMinHR = hr
            if (hr > sleepMaxHR) sleepMaxHR = hr

            if (sleepHrBuffer.size >= 4) {
                val firstHalf = sleepHrBuffer.take(2).average()
                val secondHalf = sleepHrBuffer.takeLast(2).average()
                if (secondHalf - firstHalf >= 8) {
                    sleepAwakenings++
                    sleepAwakeningTimes.add(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                    sleepHrBuffer.clear()
                    Log.d(TAG, "Пробуждение: пульс вырос с ${firstHalf.roundToInt()} до ${secondHalf.roundToInt()}")
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
                maxHR = sleepMaxHR, avgHR = avg, restingHR = resting,
                awakenings = sleepAwakenings, awakeningTimes = sleepAwakeningTimes.toList(),
                qualityName = quality.displayName, qualityDescription = quality.description,
            )
        }
    }

    override fun onCleared() {
        handler.removeCallbacksAndMessages(null)
        ble.disconnect()
        super.onCleared()
    }
}