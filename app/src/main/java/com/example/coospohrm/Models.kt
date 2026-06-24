package com.example.coospohrm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object BleUuids {
    val HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER_NAME = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val MODEL_NUMBER = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

data class TrainingSession(
    val id: String = UUID.randomUUID().toString(),
    val date: String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()),
    val durationSeconds: Int = 0,
    val maxBPM: Int = 0,
    val avgBPM: Int = 0,
    val hrData: List<Int> = emptyList(),
    val zonePercentages: Map<Int, Float> = emptyMap(),
    val calories: Float = 0f,
    val activityType: String = "Фитнес",
    val userWeight: Float = 70f,
    val userAge: Int = 30,
)

data class SleepSession(
    val id: String = UUID.randomUUID().toString(),
    val date: String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()),
    val startTime: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
    val endTime: String = "",
    val durationMinutes: Int = 0,
    val minHR: Int = 0,
    val maxHR: Int = 0,
    val avgHR: Int = 0,
    val restingHR: Int = 0,
    val awakenings: Int = 0,
    val awakeningTimes: List<String> = emptyList(),
    val qualityName: String = SleepQuality.UNKNOWN.displayName,
    val qualityDescription: String = SleepQuality.UNKNOWN.description,
    val hrData: List<Int> = emptyList(),
) {
    val quality: SleepQuality
        get() = when (qualityName) {
            "Отличный" -> SleepQuality.EXCELLENT
            "Хороший" -> SleepQuality.GOOD
            "Средний" -> SleepQuality.FAIR
            "Плохой" -> SleepQuality.POOR
            else -> SleepQuality.UNKNOWN
        }
}

enum class SleepQuality(val displayName: String, val description: String) {
    EXCELLENT("Отличный", "Глубокий восстановительный сон"),
    GOOD("Хороший", "Нормальный сон"),
    FAIR("Средний", "Поверхностный сон"),
    POOR("Плохой", "Стресс или переутомление"),
    UNKNOWN("Неизвестно", "")
}

enum class ActivityType(val met: Double, val displayName: String) {
    CYCLING_LIGHT(4.0, "🚴 Велосипед, прогулка"),
    CYCLING_MODERATE(8.0, "🚴 Велосипед, умеренно"),
    CYCLING_FAST(10.0, "🚴 Велосипед, быстро"),
    WALKING_SLOW(2.5, "🚶 Ходьба, медленно"),
    WALKING_NORMAL(3.5, "🚶 Ходьба, обычно"),
    WALKING_FAST(5.0, "🚶 Ходьба, быстро"),
    WALKING_VERY_FAST(6.3, "🚶 Ходьба, очень быстро"),
    RUNNING_JOG(6.0, "🏃 Бег, трусца"),
    RUNNING_8K(8.0, "🏃 Бег, 8 км/ч"),
    RUNNING_10K(10.0, "🏃 Бег, 10 км/ч"),
    RUNNING_12K(11.5, "🏃 Бег, 12 км/ч"),
    RUNNING_14K(14.0, "🏃 Бег, 14 км/ч"),
    ATHLETICS_THROW(4.0, "🏅 Легкая атлетика, метание"),
    ATHLETICS_JUMP(6.0, "🏅 Легкая атлетика, прыжки"),
    ATHLETICS_HURDLES(10.0, "🏅 Легкая атлетика, барьеры"),
    WEIGHT_LIGHT(3.0, "💪 Тяжелая атлетика, легкая"),
    WEIGHT_MODERATE(5.0, "💪 Тяжелая атлетика, средняя"),
    WEIGHT_INTENSE(6.0, "💪 Тяжелая атлетика, интенсивная"),
    FITNESS_LIGHT(3.5, "🏋️ Фитнес, легкий"),
    FITNESS_MODERATE(5.5, "🏋️ Фитнес, средний"),
    FITNESS_INTENSE(8.0, "🏋️ Фитнес, интенсивный"),
    CROSSFIT(8.0, "💪 Кроссфит"),
    YOGA(2.5, "🧘 Йога"),
    PILATES(3.0, "🧘 Пилатес"),
    STEP_AEROBICS(8.5, "🪜 Степ-аэробика"),
    JUMP_ROPE(12.3, "🪢 Скакалка"),
    SWIMMING_EASY(5.8, "🏊 Плавание, легко"),
    SWIMMING_FAST(9.8, "🏊 Плавание, быстро"),
    BOXING_BAG(5.5, "🥊 Бокс, груша"),
    BOXING_SPARRING(7.8, "🥊 Бокс, спарринг"),
    GOLF_WALKING(4.5, "⛳ Гольф, ходьба"),
    TENNIS_DOUBLES(5.0, "🎾 Теннис, парный"),
    VOLLEYBALL(8.0, "🏐 Волейбол"),
    STAIRS_SLOW(4.0, "🪜 Лестница, медленно"),
    STAIRS_FAST(8.8, "🪜 Лестница, быстро"),
}

data class HeartRateZones(
    val z1: Int = 100, val z2: Int = 120, val z3: Int = 140, val z4: Int = 160,
) {
    fun zoneOf(hr: Int): Int = when {
        hr <= z1 -> 1; hr <= z2 -> 2; hr <= z3 -> 3; hr <= z4 -> 4; else -> 5
    }
}

sealed class Screen {
    data object Main : Screen()
    data object Training : Screen()
    data object Sleep : Screen()
    data object Settings : Screen()
    data class SessionDetail(val sessionId: String) : Screen()
    data class SleepDetail(val sessionId: String) : Screen()
}