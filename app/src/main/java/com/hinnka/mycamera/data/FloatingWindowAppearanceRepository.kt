package com.hinnka.mycamera.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.floatingWindowAppearanceStore by preferencesDataStore(name = "floating_window_appearance")

data class FloatingWindowAppearance(
    val transparencyPercent: Int = 55,
    val blurStrengthDp: Float = 22f,
) {
    fun normalized() = copy(
        transparencyPercent = transparencyPercent.coerceIn(0, 100),
        blurStrengthDp = blurStrengthDp.coerceIn(0f, 50f),
    )

    companion object {
        val DEFAULT = FloatingWindowAppearance()
    }
}

class FloatingWindowAppearanceRepository(context: Context) {
    private val store = context.applicationContext.floatingWindowAppearanceStore
    private val transparencyKey = intPreferencesKey("transparency_percent")
    private val blurStrengthKey = floatPreferencesKey("blur_strength_dp")

    val appearance: Flow<FloatingWindowAppearance> = store.data
        .map { preferences ->
            FloatingWindowAppearance(
                transparencyPercent = preferences[transparencyKey] ?: 55,
                blurStrengthDp = preferences[blurStrengthKey] ?: 22f,
            ).normalized()
        }
        .distinctUntilChanged()

    suspend fun save(appearance: FloatingWindowAppearance) {
        val value = appearance.normalized()
        store.edit { preferences ->
            preferences[transparencyKey] = value.transparencyPercent
            preferences[blurStrengthKey] = value.blurStrengthDp
        }
    }
}
