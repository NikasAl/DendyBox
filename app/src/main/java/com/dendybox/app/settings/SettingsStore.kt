package com.dendybox.app.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Ядро настроек: читает один [SharedPreferences] при создании и зеркалит его
 * в StateFlow; каждый сеттер немедленно возвращает значение в prefs.
 * Выделено в класс (как LayoutStore), чтобы JVM-тест эмулировал рестарт:
 * два инстанса над одним in-memory prefs — «процесс с записью» и «процесс
 * после перезапуска» (SettingsRestartTest).
 *
 * @param validSkinIds набор валидных id скинов — реестр ControlSkins.IDS,
 *   его передаёт слой UI (MainActivity): сами настройки от ui.controls не
 *   зависят. Сохранённый id вне набора (скин удалён в новой версии)
 *   нормализуется в классику с чисткой prefs. Пустой набор — валидация
 *   не выполняется (byId при живой отрисовке всё равно фолбэкается).
 */
class SettingsCore(
    private val prefs: SharedPreferences,
    validSkinIds: Collection<String> = emptySet(),
) {
    val turboHzA = MutableStateFlow(14f)      // частота автоповтора A, Гц
    val turboHzB = MutableStateFlow(14f)      // частота автоповтора B, Гц
    val sound = MutableStateFlow(true)
    val haptics = MutableStateFlow(true)      // вибрация при уроне (если игра задаёт байт урона)
    val dpadSize = MutableStateFlow(58f)      // радиус крестовины, dp
    val controlsOpacity = MutableStateFlow(0.55f) // общая видимость контролов: заливки, рамки, крестовина, подписи
    // Скин кнопок: идентификатор из ControlSkins (classic | gadget | console).
    // Строка хранится здесь, ресолв в ControlSkin — в ui.controls (byId),
    // чтобы настройки не зависели от UI-слоя. КОРПУС — часть скина: у
    // «Гаджета» он есть, у остальных нет (ресолв — DeviceSkins.forControlSkin
    // в ui.controls); отдельной настройки корпуса нет.
    val controlSkin = MutableStateFlow("classic")
    val twoLocal = MutableStateFlow(false)    // локальный режим «2 игрока» на одном экране

    private val validSkinIds: Set<String> = validSkinIds.toSet()

    init {
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
        // Скины, которых больше нет в реестре (набор сокращался: неон/пиксель/
        // тёмный/денди/карбон/аркада), заменяются дефолтом — классикой; byId
        // и так резолвит неизвестный id при живой отрисовке, здесь же значение
        // вычищается из prefs. Валидные id — из реестра: жёсткий список здесь
        // рассинхронизируется при добавлении скинов (так «console» был потерян).
        if (validSkinIds.isNotEmpty() && controlSkin.value !in validSkinIds) {
            controlSkin.value = "classic"
            edit().putString("controlSkin", "classic").apply()
        }
    }

    // Каждая запись завершается apply(): Editor без терминальной операции
    // ничего не сохраняет — прежде сеттеры теряли выбор при смерти процесса.
    fun setTurboHzA(v: Float) { turboHzA.value = v; edit().putFloat("turboHzA", v).apply() }
    fun setTurboHzB(v: Float) { turboHzB.value = v; edit().putFloat("turboHzB", v).apply() }
    fun setSound(v: Boolean) { sound.value = v; edit().putBoolean("sound", v).apply() }
    fun setHaptics(v: Boolean) { haptics.value = v; edit().putBoolean("haptics", v).apply() }
    fun setDpadSize(v: Float) { dpadSize.value = v; edit().putFloat("dpadSize", v).apply() }
    fun setControlsOpacity(v: Float) { controlsOpacity.value = v; edit().putFloat("controlsOpacity", v).apply() }
    fun setControlSkin(v: String) { controlSkin.value = v; edit().putString("controlSkin", v).apply() }
    fun setTwoLocal(v: Boolean) { twoLocal.value = v; edit().putBoolean("twoLocal", v).apply() }

    private fun edit(): SharedPreferences.Editor = prefs.edit()
}

/**
 * Настройки приложения — синглтон поверх [SettingsCore] с тем же публичным
 * API. init идемпотентен: повторный вызов (рекомпозиция AppRoot, поворот)
 * состояние не сбрасывает.
 */
object SettingsStore {
    private var core: SettingsCore? = null

    private fun c(): SettingsCore =
        requireNotNull(core) { "SettingsStore.init не вызван" }

    val turboHzA: MutableStateFlow<Float> get() = c().turboHzA
    val turboHzB: MutableStateFlow<Float> get() = c().turboHzB
    val sound: MutableStateFlow<Boolean> get() = c().sound
    val haptics: MutableStateFlow<Boolean> get() = c().haptics
    val dpadSize: MutableStateFlow<Float> get() = c().dpadSize
    val controlsOpacity: MutableStateFlow<Float> get() = c().controlsOpacity
    val controlSkin: MutableStateFlow<String> get() = c().controlSkin
    val twoLocal: MutableStateFlow<Boolean> get() = c().twoLocal

    /**
     * @param validSkinIds набор валидных id скинов (ControlSkins.IDS) —
     *   см. [SettingsCore]; без него сохранённый id не нормализуется.
     */
    fun init(ctx: Context, validSkinIds: Collection<String> = emptySet()) {
        if (core != null) return
        core = SettingsCore(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE), validSkinIds)
    }

    fun setTurboHzA(v: Float) = c().setTurboHzA(v)
    fun setTurboHzB(v: Float) = c().setTurboHzB(v)
    fun setSound(v: Boolean) = c().setSound(v)
    fun setHaptics(v: Boolean) = c().setHaptics(v)
    fun setDpadSize(v: Float) = c().setDpadSize(v)
    fun setControlsOpacity(v: Float) = c().setControlsOpacity(v)
    fun setControlSkin(v: String) = c().setControlSkin(v)
    fun setTwoLocal(v: Boolean) = c().setTwoLocal(v)
}
