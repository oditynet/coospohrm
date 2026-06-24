package com.example.coospohrm

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class TrainingRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val TAG = "TrainingRepo"

    companion object {
        private const val PREFS_NAME = "training_history"
        private const val HISTORY_KEY = "history"
        private const val SLEEP_HISTORY_KEY = "sleep_history"
        private const val ZONES_KEY = "heart_rate_zones"
        private const val WEIGHT_KEY = "user_weight"
        private const val AGE_KEY = "user_age"
    }

    fun loadHistory(): List<TrainingSession> {
        return try {
            val json = prefs.getString(HISTORY_KEY, null) ?: return emptyList()
            val type = object : TypeToken<List<TrainingSession>>() {}.type
            gson.fromJson<List<TrainingSession>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "loadHistory error: ${e.message}")
            emptyList()
        }
    }

    fun saveHistory(history: List<TrainingSession>) {
        val json = gson.toJson(history)
        prefs.edit().putString(HISTORY_KEY, json).apply()
        Log.d(TAG, "saveHistory: ${history.size} записей")
    }

    fun loadSleepHistory(): List<SleepSession> {
        return try {
            val json = prefs.getString(SLEEP_HISTORY_KEY, null) ?: return emptyList()
            val type = object : TypeToken<List<SleepSession>>() {}.type
            val result = gson.fromJson<List<SleepSession>>(json, type) ?: emptyList()
            Log.d(TAG, "loadSleepHistory: ${result.size} записей")
            result
        } catch (e: Exception) {
            Log.e(TAG, "loadSleepHistory error: ${e.message}")
            emptyList()
        }
    }

    fun saveSleepHistory(history: List<SleepSession>) {
        val json = gson.toJson(history)
        prefs.edit().putString(SLEEP_HISTORY_KEY, json).apply()
        Log.d(TAG, "saveSleepHistory: ${history.size} записей сохранено")
    }

    fun loadZones(): HeartRateZones {
        val raw = prefs.getString(ZONES_KEY, null) ?: return HeartRateZones()
        val parts = raw.split(",").mapNotNull { it.toIntOrNull() }
        return if (parts.size == 4) HeartRateZones(parts[0], parts[1], parts[2], parts[3]) else HeartRateZones()
    }

    fun saveZones(z: HeartRateZones) {
        prefs.edit().putString(ZONES_KEY, "${z.z1},${z.z2},${z.z3},${z.z4}").apply()
    }

    fun loadWeight(): Float = prefs.getFloat(WEIGHT_KEY, 70f)
    fun saveWeight(w: Float) = prefs.edit().putFloat(WEIGHT_KEY, w).apply()
    fun loadAge(): Int = prefs.getInt(AGE_KEY, 30)
    fun saveAge(a: Int) = prefs.edit().putInt(AGE_KEY, a).apply()
}