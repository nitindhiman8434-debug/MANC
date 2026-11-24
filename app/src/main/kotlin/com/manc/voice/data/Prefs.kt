package com.manc.voice.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "manc_prefs")

/** Persisted settings so strength, the voiceprint and auto-start survive restarts. */
object Prefs {
    private val KEY_STRENGTH = floatPreferencesKey("strength")
    private val KEY_NEURAL_WET = floatPreferencesKey("neural_wet")
    private val KEY_VOICEPRINT = stringPreferencesKey("voiceprint")
    private val KEY_VOICEPRINT_NEURAL = stringPreferencesKey("voiceprint_neural")
    private val KEY_AUTOSTART = booleanPreferencesKey("autostart")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Snapshot(val strength: Float?, val neuralWet: Float?, val voiceprint: String?, val voiceprintNeural: String?, val autoStart: Boolean)

    suspend fun load(ctx: Context): Snapshot {
        val p = ctx.dataStore.data.first()
        return Snapshot(p[KEY_STRENGTH], p[KEY_NEURAL_WET], p[KEY_VOICEPRINT], p[KEY_VOICEPRINT_NEURAL], p[KEY_AUTOSTART] ?: true)
    }

    fun loadBlocking(ctx: Context): Snapshot = try { runBlocking { load(ctx) } } catch (e: Exception) { Snapshot(null, null, null, null, true) }

    fun saveStrengthAsync(ctx: Context, v: Float) = scope.launch {
        try { ctx.dataStore.edit { it[KEY_STRENGTH] = v } } catch (_: Exception) {}
    }
    fun saveNeuralWetAsync(ctx: Context, v: Float) = scope.launch {
        try { ctx.dataStore.edit { it[KEY_NEURAL_WET] = v } } catch (_: Exception) {}
    }
    fun saveVoiceprintAsync(ctx: Context, s: String) = scope.launch {
        try { ctx.dataStore.edit { it[KEY_VOICEPRINT] = s } } catch (_: Exception) {}
    }
    fun saveNeuralVoiceprintAsync(ctx: Context, s: String) = scope.launch {
        try { ctx.dataStore.edit { it[KEY_VOICEPRINT_NEURAL] = s } } catch (_: Exception) {}
    }
    fun saveAutoStartAsync(ctx: Context, b: Boolean) = scope.launch {
        try { ctx.dataStore.edit { it[KEY_AUTOSTART] = b } } catch (_: Exception) {}
    }
}
