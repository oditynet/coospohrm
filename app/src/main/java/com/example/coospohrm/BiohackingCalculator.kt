package com.example.coospohrm

import kotlin.math.abs
import kotlin.math.sqrt

object BiohackingCalculator {

    // ---------- HRV метрики ----------

    fun calculateRMSSD(rrIntervals: List<Double>): Double {
        if (rrIntervals.size < 2) return 0.0
        var sumSquares = 0.0
        for (i in 1 until rrIntervals.size) {
            val diff = rrIntervals[i] - rrIntervals[i - 1]
            sumSquares += diff * diff
        }
        return sqrt(sumSquares / (rrIntervals.size - 1))
    }

    fun calculateSDNN(rrIntervals: List<Double>): Double {
        if (rrIntervals.isEmpty()) return 0.0
        val mean = rrIntervals.average()
        val variance = rrIntervals.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    fun calculatePNN50(rrIntervals: List<Double>): Double {
        if (rrIntervals.size < 2) return 0.0
        var count = 0
        for (i in 1 until rrIntervals.size) {
            if (abs(rrIntervals[i] - rrIntervals[i - 1]) > 50) count++
        }
        return (count.toDouble() / (rrIntervals.size - 1)) * 100.0
    }

    fun calculateHRVMetrics(rrIntervals: List<Double>): HRVMetrics {
        return HRVMetrics(
            rmssd = calculateRMSSD(rrIntervals),
            sdnn = calculateSDNN(rrIntervals),
            pnn50 = calculatePNN50(rrIntervals),
            meanRR = if (rrIntervals.isNotEmpty()) rrIntervals.average() else 0.0,
            stressIndex = calculateStressIndex(rrIntervals)
        )
    }

    // ---------- Индекс стресса (Баевского) ----------

    fun calculateStressIndex(rrIntervals: List<Double>): Double {
        if (rrIntervals.size < 10) return 0.0

        val mo = calculateMode(rrIntervals)
        val amo = mo / rrIntervals.size * 100.0
        val dx = rrIntervals.max() - rrIntervals.min()

        return if (dx > 0 && mo > 0) {
            amo / (2 * dx * mo) * 1000.0
        } else 0.0
    }

    fun stressLevelFromIndex(index: Double): String {
        return when {
            index <= 0 -> ""
            index < 50 -> "Низкий стресс 😊"
            index < 150 -> "Умеренный стресс 😐"
            index < 300 -> "Высокий стресс 😟"
            else -> "Очень высокий стресс 😫"
        }
    }

    private fun calculateMode(values: List<Double>): Double {
        return values.groupBy { it }.maxByOrNull { it.value.size }?.key ?: values.average()
    }

    // ---------- VO2max ----------

    fun estimateVO2max(restingHR: Int, maxHR: Int, age: Int, isMale: Boolean): Double {
        val hrRatio = if (restingHR > 0) maxHR.toDouble() / restingHR.toDouble() else 1.0
        val baseVO2max = 15.3 * hrRatio
        val ageCorrection = (age - 30) * 0.3
        val genderCorrection = if (isMale) 3.5 else 0.0
        return (baseVO2max - ageCorrection + genderCorrection).coerceIn(10.0, 80.0)
    }

    // ---------- Восстановление пульса ----------

    fun heartRateRecovery(peakHR: Int, hrAfter2Min: Int): Int {
        return peakHR - hrAfter2Min
    }

    // ---------- Биологический возраст сердца ----------

    fun biologicalHeartAge(
        restingHR: Int,
        rmssd: Double,
        hrr: Int,
        chronologicalAge: Int
    ): Int {
        var score = 0

        // Пульс покоя
        score += when {
            restingHR == 0 -> 0
            restingHR < 50 -> 15
            restingHR < 55 -> 10
            restingHR < 60 -> 5
            restingHR < 70 -> 0
            restingHR < 80 -> -5
            else -> -10
        }

        // HRV
        score += when {
            rmssd > 60 -> 10
            rmssd > 50 -> 7
            rmssd > 40 -> 3
            rmssd > 30 -> 0
            rmssd > 20 -> -3
            rmssd > 0 -> -7
            else -> 0
        }

        // Восстановление
        score += when {
            hrr > 30 -> 10
            hrr > 25 -> 5
            hrr > 20 -> 0
            hrr > 15 -> -5
            hrr > 0 -> -10
            else -> 0
        }

        return (chronologicalAge - score / 2).coerceIn(chronologicalAge - 20, chronologicalAge + 20)
    }

    // ---------- Утренняя готовность ----------

    fun morningReadiness(
        morningHR: Int,
        morningRMSSD: Double,
        baselineHR: Int,
        baselineRMSSD: Double,
        sleepHours: Double
    ): ReadinessScore {
        var score = 100

        if (baselineHR > 0 && morningHR > baselineHR + 5) score -= 20
        else if (baselineHR > 0 && morningHR > baselineHR + 3) score -= 10

        if (baselineRMSSD > 0) {
            val ratio = morningRMSSD / baselineRMSSD
            if (ratio < 0.8) score -= 25
            else if (ratio < 0.9) score -= 15
            else if (ratio < 1.0) score -= 5
        }

        if (sleepHours < 6) score -= 20
        else if (sleepHours < 7) score -= 10

        return ReadinessScore(
            score = score.coerceIn(0, 100),
            status = when {
                score > 80 -> "Готов к интенсивной нагрузке"
                score > 60 -> "Готов к умеренной нагрузке"
                score > 40 -> "Готов к легкой нагрузке"
                else -> "Нужен отдых"
            },
            recommendation = when {
                score > 80 -> "HIIT, силовые, бег"
                score > 60 -> "Кардио, средняя нагрузка"
                score > 40 -> "Йога, ходьба, растяжка"
                else -> "Отдых, медитация, сон"
            }
        )
    }

    // ---------- Индекс здоровья сердца ----------

    fun heartHealthScore(restingHR: Int, rmssd: Double, hrr: Int, age: Int): Int {
        var score = 100

        if (restingHR > 80) score -= 25
        else if (restingHR > 70) score -= 15
        else if (restingHR > 60) score -= 5
        else if (restingHR > 50) score += 5

        if (rmssd < 20) score -= 25
        else if (rmssd < 30) score -= 15
        else if (rmssd < 40) score -= 5
        else if (rmssd > 50) score += 10

        if (hrr < 12) score -= 20
        else if (hrr < 20) score -= 10
        else if (hrr > 30) score += 10

        return score.coerceIn(0, 100)
    }
}