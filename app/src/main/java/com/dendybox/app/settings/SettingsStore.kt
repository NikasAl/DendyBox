package com.dendybox.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.dendybox.app.ui.controls.ControlSkins
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Настройки приложения. SharedPreferences + зеркальные StateFlow для Compose.
 */
object SettingsStore {
    private lateinit var prefs: SharedPreferences

    val turboHzA = MutableStateFlow(14f)      // частота автоповтора A, Гц
    val turboHzB = MutableStateFlow(14f)      // частота автоповтора B, Гц
    val sound = MutableStateFlow(true)
    val haptics = MutableStateFlow(true)      // вибрация при уроне (если игра задаёт байт урона)
    val dpadSize = MutableStateFlow(58f)      // радиус крестовины, dp
    val controlsOpacity = MutableStateFlow(0.55f) // общая видимость контролов: заливки, рамки, крестовина, подписи
    // Скин кнопок: идентификатор из ControlSkins (classic | gadget | console).
    // Строка хранится здесь, отрисовка — через ControlSkins.byId в ui.controls;
    // валидация набора id при старте берётся из реестра ControlSkins.ALL
    // (единый источник правды — хардкод валидатора больше не отстаёт от UI).
    val controlSkin = MutableStateFlow("classic")
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
        controlSkin.value = prefs.getString("controlSkin", "classic") ?: "classic"
        twoLocal.value = prefs.getBoolean("twoLocal", false)
        // Миграция старого тумблера «Корпус (гаджет)»: корпус теперь часть
        // скина. Если корпус был включён при классическом скине кнопок —
        // переносим выбор на скин «Гаджет» целиком (корпус составлял с ним
        // единый дизайн; кнопки без корпуса в новом виде не существуют).
        // Ключ deviceSkin после миграции удаляется — настройка упразднена.
        val legacyDevice = prefs.getString("deviceSkin", null)
        if (legacyDevice != null) {
            if (legacyDevice == "gadget" && controlSkin.value == "classic") {
                controlSkin.value = "gadget"
            }
            edit().putString("controlSkin", controlSkin.value).remove("deviceSkin").apply()
        }
        // Сохранённый id из реестра ControlSkins.ALL (классика/гаджет/приставка)
        // — как есть; неизвестный (удалённые неон/пиксель/тёмный/денди/карбон/
        // аркада) — дефолт и вычистка из prefs, чтобы не остался мусор от
        // прежних версий. Регрессия 04.11: валидатор хардкодил {classic, gadget}
        // и молча сбрасывал добавленный позже «Приставку» (console) на классику
        // при каждом старте — теперь набор берётся из реестра.
        val storedSkin = controlSkin.value
        val knownSkins = ControlSkins.ALL.mapTo(HashSet()) { it.id }
        val normalized = normalizeControlSkin(storedSkin, knownSkins)
        if (normalized != storedSkin) {
            controlSkin.value = normalized
            edit().putString("controlSkin", normalized).apply()
        }
    }

    fun setTurboHzA(v: Float) { turboHzA.value = v; edit().putFloat("turboHzA", v) }
    fun setTurboHzB(v: Float) { turboHzB.value = v; edit().putFloat("turboHzB", v) }
    fun setSound(v: Boolean) { sound.value = v; edit().putBoolean("sound", v) }
    fun setHaptics(v: Boolean) { haptics.value = v; edit().putBoolean("haptics", v) }
    fun setDpadSize(v: Float) { dpadSize.value = v; edit().putFloat("dpadSize", v) }
    fun setControlsOpacity(v: Float) { controlsOpacity.value = v; edit().putFloat("controlsOpacity", v) }
    fun setControlSkin(v: String) { controlSkin.value = v; edit().putString("controlSkin", v) }

    /**
     * Валидация сохранённого id скина: id из [knownIds] — как есть, иначе дефолт
     * (классика). Чистая функция — инициализация передаёт актуальный набор из
     * ControlSkins.ALL, юнит-тесты — произвольные.
     * @return нормализованный id
     */
    fun normalizeControlSkin(stored: String, knownIds: Set<String>): String =
        if (stored in knownIds) stored else ControlSkins.DEFAULT_ID
    fun setTwoLocal(v: Boolean) { twoLocal.value = v; edit().putBoolean("twoLocal", v) }

    private fun edit() = prefs.edit()
}
