package com.greenart7c3.morganite.models

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class Settings(
    val useTor: Boolean = false,
    val useTorForAllUrls: Boolean = false,
)

class SettingsManager(context: Context) {
    private val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        Settings(
            useTor = sharedPreferences.getBoolean("useTor", false),
            useTorForAllUrls = sharedPreferences.getBoolean("useTorForAllUrls", false)
        )
    )
    val settings = _settings.asStateFlow()

    fun updateUseTor(useTor: Boolean) {
        sharedPreferences.edit().putBoolean("useTor", useTor).apply()
        _settings.value = _settings.value.copy(useTor = useTor)
    }

    fun updateUseTorForAllUrls(useTorForAllUrls: Boolean) {
        sharedPreferences.edit().putBoolean("useTorForAllUrls", useTorForAllUrls).apply()
        _settings.value = _settings.value.copy(useTorForAllUrls = useTorForAllUrls)
    }
}
