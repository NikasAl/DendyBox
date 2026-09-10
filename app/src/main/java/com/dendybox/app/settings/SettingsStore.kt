package com.dendybox.app.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Настройки приложения. SharedPreferences + зеркальные StateFlow для Compose.
 */
object SettingsStore {
    private lateinit var prefs: SharedPreferences

    val turboHzA = MutableStateFlow(14f)      // частота автоповтора A, Гц
    val turboHzB = MutableStateFlow(14f)      // частота автоповтора B, Гц
    val sound = MutableStateFlow(true)
    val haptics = MutableStateFlow(true)      // вибро-отклик крестовины
    val dpadSize = MutableStateFlow(58f)      // радиус крестовины, dp
    val controlsOpacity = MutableStateFlow(0.55f) // прозрачность кнопок
    val twoLocal = MutableStateFlow(false)    // локальный режим «2 игрока» на одном экране

    fun init(ctx: Context) {
        if (this::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        turboHzA.value = prefs.getFloat("turboHzA", 14f)
        turboHzB.value = prefs.getFloat("turboHzB", 14f)
        sound.value = prefs.getBoolean("sound", true)
        haptics.value = prefs.getBoolean("haptics", true)
        dpadSize.value = prefs.getFloat("dpadSize", 58f)
        controlsOpacity.value = prefs.getFloat("controlsOpacity", 0.55f)
        twoLocal.value = prefs.getBoolean("twoLocal", false)
    }

    fun setTurboHzA(v: Float) { turboHzA.value = v; edit().putFloat("turboHzA", v) }
    fun setTurboHzB(v: Float) { turboHzB.value = v; edit().putFloat("turboHzB", v) }
    fun setSound(v: Boolean) { sound.value = v; edit().putBoolean("sound", v) }
    fun setHaptics(v: Boolean) { haptics.value = v; edit().putBoolean("haptics", v) }
    fun setDpadSize(v: Float) { dpadSize.value = v; edit().putFloat("dpadSize", v) }
    fun setControlsOpacity(v: Float) { controlsOpacity.value = v; edit().putFloat("controlsOpacity", v) }
    fun setTwoLocal(v: Boolean) { twoLocal.value = v; edit().putBoolean("twoLocal", v) }

    private fun edit() = prefs.edit()
}
