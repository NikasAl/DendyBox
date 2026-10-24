package com.dendybox.app.ui.controls

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Шаг комбо: какие кнопки нажимаются, сколько держатся и когда начинаются.
 *
 * - [btn] — главная кнопка шага; [with] — дополнительные кнопки, нажимаемые
 *   ОДНОВРЕМЕННО с ней и отпускаемые вместе с ней;
 * - [delayMs] — пауза перед началом шага: от ОТПУСКАНИЯ предыдущего шага
 *   (старое поведение) или от его НАЖАТИЯ ([fromPress]) — так задаются
 *   перекрывающиеся удержания и сдвиги старта между кнопками. У первого
 *   шага пауза всегда считается от нажатия самой комбо-кнопки;
 * - [holdMs] — сколько мс набор держится нажатым (100 — прежнее поведение,
 *   «клик»); произвольные удержания — press-and-hold.
 */
data class ComboStep(
    val btn: String,
    val delayMs: Int,
    val with: List<String> = emptyList(),
    val holdMs: Int = ComboJson.DEFAULT_HOLD_MS,
    val fromPress: Boolean = false
)

/**
 * Программируемая комбо-кнопка: имя (рисуется на кнопке) и последовательность
 * нажатий. [preset] — кнопка из пресета игры (roms/<flavor>.json), её нельзя
 * редактировать и удалять в конфигураторе.
 */
data class ComboButton(
    val id: String,
    val name: String,
    val steps: List<ComboStep>,
    val preset: Boolean = false
)

/**
 * Чистая (без Android-состояния) работа с JSON комбо-кнопок — используется
 * и хранилищем [ComboStore], и GameConfig (пресеты из roms/<flavor>.json),
 * и покрыта юнит-тестами.
 *
 * Формат:
 * ```json
 * [{"id":"u1","name":"Суперудар","steps":[{"b":"A","d":0},{"b":"B","d":100}]}]
 * ```
 * Новые поля шага (обязательными не являются — старые записи читаются как
 * раньше и сериализуются обратно без новых ключей):
 * ```json
 * [{"id":"u2","name":"Зажать A+B",
 *   "steps":[
 *     {"b":"A","d":0,"h":1000},                       // удержание 1000 мс
 *     {"b":"B","d":100,"h":1000,"anchor":"press"}     // +100 мс от НАЖАТИЯ A
 *   ]}]
 * ```
 *   * `w`      — массив дополнительных кнопок: нажимаются одновременно
 *               с главной и отпускаются вместе с ней;
 *   * `h`      — удержание шага, мс (кратно 100, 100–2000; нет — 100);
 *   * `anchor` — "press": пауза `d` отсчитывается от НАЖАТИЯ предыдущего
 *               шага, а не от его отпускания (перекрывающиеся удержания).
 * `d` теперь тоже опционален (нет — 0).
 * Битые элементы молча пропускаются (пресет игры не должен ронять приложение).
 */
object ComboJson {

    /** Кнопки джойстика, доступные в шагах комбо. */
    val BTN_KEYS = listOf("LEFT", "RIGHT", "UP", "DOWN", "A", "B", "START", "SELECT")

    /** Значения "anchor", означающие отсчёт паузы от НАЖАТИЯ предыдущего шага. */
    private val ANCHOR_PRESS = setOf("press", "start")

    /** Подписи кнопок для редактора/сводки. */
    fun btnLabel(btn: String): String = when (btn) {
        "LEFT" -> "←"; "RIGHT" -> "→"; "UP" -> "↑"; "DOWN" -> "↓"
        else -> btn
    }

    /** Максимальное число шагов в одной комбо-кнопке. */
    const val MAX_STEPS = 12

    /** Минимальный/максимальный шаг задержки (кратно 100), мс. */
    const val MAX_DELAY_MS = 500

    /** Длительность удержания шага (кратно 100), мс: по умолчанию/мин и макс. */
    const val MIN_HOLD_MS = 100
    const val MAX_HOLD_MS = 2000
    const val DEFAULT_HOLD_MS = MIN_HOLD_MS

    /** Человеческая сводка последовательности: «A · B +100», «A ×1000 · B +100 ×1000». */
    fun stepsText(steps: List<ComboStep>): String = steps.joinToString(" · ") {
        val btns = (listOf(it.btn) + it.with).joinToString("+") { b -> btnLabel(b) }
        val delay = if (it.delayMs > 0) " +${it.delayMs}" else ""
        val hold = if (it.holdMs != DEFAULT_HOLD_MS) " ×${it.holdMs}" else ""
        btns + delay + hold
    }

    fun validSteps(steps: List<ComboStep>): Boolean =
        steps.isNotEmpty() && steps.size <= MAX_STEPS &&
            steps.all { s ->
                s.btn in BTN_KEYS && s.delayMs in 0..MAX_DELAY_MS && s.delayMs % 100 == 0 &&
                    s.holdMs in MIN_HOLD_MS..MAX_HOLD_MS && s.holdMs % 100 == 0 &&
                    s.with.all { it in BTN_KEYS } &&
                    s.with.distinct().size == s.with.size &&
                    s.btn !in s.with
            }

    fun stepToJson(s: ComboStep): JSONObject = JSONObject().apply {
        put("b", s.btn)
        put("d", s.delayMs)
        if (s.with.isNotEmpty()) put("w", JSONArray(s.with))
        if (s.holdMs != DEFAULT_HOLD_MS) put("h", s.holdMs)
        if (s.fromPress) put("anchor", "press")
    }

    fun stepFromJson(o: JSONObject): ComboStep? = try {
        val with = ArrayList<String>()
        val w = o.optJSONArray("w")
        if (w != null) for (i in 0 until w.length()) with.add(w.getString(i).trim().uppercase())
        val s = ComboStep(
            btn = o.getString("b").trim().uppercase(),
            delayMs = o.optInt("d", 0),
            with = with,
            holdMs = o.optInt("h", DEFAULT_HOLD_MS),
            fromPress = o.optString("anchor", "").trim().lowercase() in ANCHOR_PRESS
        )
        if (validSteps(listOf(s))) s else null
    } catch (_: Exception) {
        null
    }

    fun buttonToJson(b: ComboButton): JSONObject = JSONObject()
        .put("id", b.id)
        .put("name", b.name)
        .put("steps", JSONArray().apply { b.steps.forEach { put(stepToJson(it)) } })

    /** null — запись битая (нет имени, шагов или недопустимые значения). */
    fun buttonFromJson(o: JSONObject): ComboButton? = try {
        val steps = ArrayList<ComboStep>()
        val arr = o.getJSONArray("steps")
        for (i in 0 until arr.length()) {
            val s = stepFromJson(arr.getJSONObject(i)) ?: return null
            steps.add(s)
        }
        val b = ComboButton(
            id = o.getString("id"),
            name = o.getString("name").trim(),
            steps = steps
        )
        if (b.name.isNotEmpty() && validSteps(b.steps)) b else null
    } catch (_: Exception) {
        null
    }

    fun listToJson(buttons: List<ComboButton>): String = JSONArray().apply {
        buttons.forEach { put(buttonToJson(it)) }
    }.toString()

    fun listFromJson(text: String): List<ComboButton> {
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            return emptyList()
        }
        // Изоляция ПО ЭЛЕМЕНТУ: одна битая запись (не объект и т.п.) не
        // должна убивать весь список — пресет игры соберётся частично
        return (0 until arr.length()).mapNotNull { i ->
            try {
                buttonFromJson(arr.getJSONObject(i))
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Хранилище ПОЛЬЗОВАТЕЛЬСКИХ комбо-кнопок (глобальный набор, все игры) —
 * по образцу [SettingsStore]: SharedPreferences + зеркальное Compose-состояние.
 * Пресеты конкретной игры живут в GameConfig (roms/<flavor>.json) и
 * подмешиваются в [effective].
 *
 * Кнопки рисуются на игровой поверхности отдельной группой
 * (LayoutStore.GroupId.COMBO — позиция/масштаб блока в редакторе раскладки);
 * нажатие запускает проигрывание последовательности ([com.dendybox.app.input.ComboRunner]
 * через GamepadInput).
 */
object ComboStore {
    private const val PREF_KEY = "combos_v1"
    private lateinit var prefs: SharedPreferences

    /** Пользовательские кнопки (без пресетов игры). */
    var buttons: List<ComboButton> by mutableStateOf(emptyList())
        private set

    fun init(ctx: Context) {
        if (this::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("combos", Context.MODE_PRIVATE)
        buttons = ComboJson.listFromJson(prefs.getString(PREF_KEY, null) ?: "[]")
    }

    /**
     * Полный набор для текущей игры: пресеты из конфига игры (id «p0», «p1»…,
     * только чтение) + пользовательские кнопки. Пресеты идут первыми — это
     * «главные» приёмы игры.
     */
    fun effective(): List<ComboButton> =
        com.dendybox.app.settings.GameConfig.comboPresets.mapIndexed { i, c ->
            c.copy(id = "p$i")
        } + buttons

    /** Уникальный id новой пользовательской кнопки. */
    fun newId(): String {
        val base = "u" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        var id = base
        var n = 1
        while (buttons.any { it.id == id }) id = base + "_" + (n++)
        return id
    }

    fun add(b: ComboButton) {
        buttons = buttons + b
        persist()
    }

    fun update(b: ComboButton) {
        buttons = buttons.map { if (it.id == b.id) b else it }
        persist()
    }

    fun remove(id: String) {
        buttons = buttons.filterNot { it.id == id }
        persist()
    }

    private fun persist() {
        if (!this::prefs.isInitialized) return
        prefs.edit().putString(PREF_KEY, ComboJson.listToJson(buttons)).apply()
    }
}
