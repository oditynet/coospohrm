package com.example.coospohrm

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class TrainingRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "training_history"
        private const val HISTORY_KEY = "history"
        private const val ZONES_KEY = "heart_rate_zones"
        private const val WEIGHT_KEY = "user_weight"
        private const val AGE_KEY = "user_age"
    }

    fun loadHistory(): List<TrainingSession> = runCatching {
        val json = prefs.getString(HISTORY_KEY, null) ?: return emptyList()
        gson.fromJson<List<TrainingSession>>(
            json, object : TypeToken<List<TrainingSession>>() {}.type
        ) ?: emptyList()
    }.getOrDefault(emptyList())

    fun saveHistory(history: List<TrainingSession>) {
        prefs.edit().putString(HISTORY_KEY, gson.toJson(history)).apply()
    }

    fun loadZones(): HeartRateZones {
        val raw = prefs.getString(ZONES_KEY, null) ?: return HeartRateZones()
        val parts = raw.split(",").mapNotNull { it.toIntOrNull() }
        return if (parts.size == 4) HeartRateZones(parts[0], parts[1], parts[2], parts[3])
        else HeartRateZones()
    }

    fun saveZones(z: HeartRateZones) {
        prefs.edit().putString(ZONES_KEY, "${z.z1},${z.z2},${z.z3},${z.z4}").apply()
    }

    fun loadWeight(): Float = prefs.getFloat(WEIGHT_KEY, 70f)
    fun saveWeight(w: Float) = prefs.edit().putFloat(WEIGHT_KEY, w).apply()

    fun loadAge(): Int = prefs.getInt(AGE_KEY, 30)
    fun saveAge(a: Int) = prefs.edit().putInt(AGE_KEY, a).apply()
}
