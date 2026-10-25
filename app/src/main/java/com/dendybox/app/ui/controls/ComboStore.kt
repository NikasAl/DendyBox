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
 *   «клик»; сетка 50 мс, диапазон 50–500); press-and-hold.
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
 *     {"b":"A","d":0,"h":500},                       // удержание 500 мс
 *     {"b":"B","d":50,"h":350,"anchor":"press"}     // +50 мс от НАЖАТИЯ A
 *   ]}]
 * ```
 *   * `w`      — массив дополнительных кнопок: нажимаются одновременно
 *               с главной и отпускаются вместе с ней;
 *   * `h`      — удержание шага, мс (кратно 50, 50–500; нет — 100);
 *   * `anchor` — "press": пауза `d` отсчитывается от НАЖАТИЯ предыдущего
 *               шага, а не от его отпускания (перекрывающиеся удержания).
 * `d` опционален (нет — 0).
 * Строгие границы: шаг сетки 50 мс, пауза 0–500, удержание 50–500 — значение
 * вне границ или мимо сетки делает шаг недействительным, а запись с ним —
 * битой. Битые элементы молча пропускаются (пресет игры не должен ронять
 * приложение).
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

    /** Сетка времени: минимальный шаг любых задержек/удержаний, мс. */
    const val GRID_MS = 50

    /** Максимальный шаг задержки (кратно 50), мс. */
    const val MAX_DELAY_MS = 500

    /** Удержание шага (кратно 50), мс: минимум, максимум и прежний «клик». */
    const val MIN_HOLD_MS = 50
    const val MAX_HOLD_MS = 500
    const val DEFAULT_HOLD_MS = 100

    /** Человеческая сводка последовательности: «A · B +100», «A ×500 · B +50 ×350». */
    fun stepsText(steps: List<ComboStep>): String = steps.joinToString(" · ") {
        val btns = (listOf(it.btn) + it.with).joinToString("+") { b -> btnLabel(b) }
        val delay = if (it.delayMs > 0) " +${it.delayMs}" else ""
        val hold = if (it.holdMs != DEFAULT_HOLD_MS) " ×${it.holdMs}" else ""
        btns + delay + hold
    }

    fun validSteps(steps: List<ComboStep>): Boolean =
        steps.isNotEmpty() && steps.size <= MAX_STEPS &&
            steps.all { s ->
                s.btn in BTN_KEYS && s.delayMs in 0..MAX_DELAY_MS && s.delayMs % GRID_MS == 0 &&
                    s.holdMs in MIN_HOLD_MS..MAX_HOLD_MS && s.holdMs % GRID_MS == 0 &&
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
            // Строгие границы: вне 50–500 или мимо сетки 50 запись отбрасывается
            // целиком (валидация ниже), без сжатия и миграций
            holdMs = o.optInt("h", DEFAULT_HOLD_MS),
            fromPress = o.optString("anchor", "").trim().lowercase() in ANCHOR_PRESS
        )
        if (validSteps(listOf(s))) s else null
    } catch (_: Exception) {
        null
    }

    /**
     * Пресеты игры из JSON-массива (раздел `combos` в roms/<flavor>.json и
     * в записи игры сборника roms/games.json): id проставляется заглушкой
     * "preset" — настоящие ("p0", "p1"…) назначает ComboStore.effective().
     * Битые записи пропускаются; null — массива нет.
     */
    fun presetsFromJson(arr: JSONArray?): List<ComboButton>? {
        arr ?: return null
        val out = ArrayList<ComboButton>(arr.length())
        for (i in 0 until arr.length()) {
            val combo = try {
                buttonFromJson(arr.getJSONObject(i).put("id", "preset"))
            } catch (_: Exception) {
                null
            } ?: continue
            out.add(combo)
        }
        return out
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
 * Хранилище ПОЛЬЗОВАТЕЛЬСКИХ комбо-кнопок, разбитое на СЕКЦИИ ПО ИГРЕ —
 * по образцу [SettingsStore]: SharedPreferences + зеркальное Compose-состояние.
 * Пресеты конкретной игры живут в GameConfig (roms/<flavor>.json) и
 * подмешиваются в [effective].
 *
 * Свои кнопки привязаны к игре, как и пресеты сборника: кнопки, созданные
 * при активной игре X сборника, хранятся в секции X и показываются/играют
 * только в ней — при переключении игры (MainActivity → [selectRom]) набор
 * меняется вместе с пресетами. Для одиночной игры (flavor без сборника)
 * кнопки лежат в общей секции [FLAVOR_KEY] — поведение прежних версий.
 * Секции не смешиваются: у каждой игры свой набор.
 *
 * Ключ секции рома — название игры из манифеста сборника (title из
 * roms/games.json): устойчиво к перестановке/удалению игр, в отличие от
 * индекса и имени файла. Две записи с одинаковым названием разделят секцию.
 *
 * Формат prefs (ключ `combos_v2`):
 * ```json
 * {"flavor": [ ...кнопки одиночной игры... ],
 *  "roms": { "ТМНТ": [ ...кнопки этой игры... ] }}
 * ```
 * Первая версия хранила один общий массив (`combos_v1`) — при первом запуске
 * он переносится в секцию [FLAVOR_KEY] (то же поведение «свои кнопки везде»).
 *
 * Кнопки рисуются на игровой поверхности отдельной группой
 * (LayoutStore.GroupId.COMBO — позиция/масштаб блока в редакторе раскладки);
 * нажатие запускает проигрывание последовательности ([com.dendybox.app.input.ComboRunner]
 * через GamepadInput).
 */
object ComboStore {
    private const val PREF_KEY = "combos_v2"
    private const val PREF_KEY_V1 = "combos_v1"

    /** Ключ секции вне сборника: одиночная игра flavor'а (и меню сборника). */
    internal const val FLAVOR_KEY = "flavor"

    private lateinit var prefs: SharedPreferences

    /** Все секции своих кнопок; пустые секции не хранятся. */
    private var sections: Map<String, List<ComboButton>> by mutableStateOf(emptyMap())

    /** Ключ активной секции: null — flavor (одиночная игра или меню сборника). */
    var activeSection: String? by mutableStateOf(null)
        private set

    /**
     * Свои кнопки АКТИВНОЙ секции: активной игры сборника либо flavor'а
     * (одиночная игра). Реактивно — при [selectRom] конфигуратор и блок
     * комбо-кнопок на поверхности обновляются сами.
     */
    val buttons: List<ComboButton>
        get() = sections[activeSection ?: FLAVOR_KEY] ?: emptyList()

    fun init(ctx: Context) {
        if (this::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("combos", Context.MODE_PRIVATE)
        val v2 = prefs.getString(PREF_KEY, null)
        sections = if (v2 != null) {
            parseSections(v2)
        } else {
            // Миграция первой версии (общий массив): все свои кнопки
            // переезжают в секцию flavor — прежнее поведение «видны везде»
            parseSections(prefs.getString(PREF_KEY_V1, null))
                .also { if (it.isNotEmpty()) persist() }
        }
        selectRom(null)
    }

    /**
     * Загружен ром сборника: активировать секцию своих кнопок этой игры
     * ([romKey] — название игры из манифеста; null — flavor: одиночная игра
     * или меню сборника). Вызывается из MainActivity рядом с
     * GameConfig.selectRom — свои кнопки и пресеты переключаются синхронно.
     */
    fun selectRom(romKey: String?) {
        activeSection = romKey
    }

    /**
     * Кнопки на игровой поверхности: пресеты из конфига игры (id «p0», «p1»…,
     * только чтение) + свои кнопки активной секции, БЕЗ отключённых (см.
     * [disabledProvider]). Пресеты идут первыми — это «главные» приёмы игры.
     * Конфигуратор (CombosScreen) показывает полный список, включая
     * отключённые — там кнопка включается обратно.
     */
    fun effective(): List<ComboButton> =
        withoutDisabled(
            com.dendybox.app.settings.GameConfig.comboPresets.mapIndexed { i, c ->
                c.copy(id = "p$i")
            } + buttons,
            disabledProvider()
        )

    /**
     * Ключи отключённых кнопок — раскладка (LayoutStore.combosOff).
     * Подключается в GameScreen при создании раскладки; по умолчанию пусто
     * (все включены) — так и в юнит-тестах, и до первого захода в игру.
     */
    internal var disabledProvider: () -> Set<String> = { emptySet() }

    /**
     * Стабильный ключ отключения кнопки (хранится в конфиге раскладки):
     *   * свои кнопки — по id («u:…»): id выдаёт [newId] один раз и
     *     переживает переименование; id уникален во ВСЕХ секциях, так что
     *     ключ не конфликтует между играми сборника;
     *   * пресеты — по имени («pres:…»): id «p0…» назначается по индексу
     *     и меняется при переключении ROM сборника, имя задаёт картридж.
     * Отключение прячет кнопку с поверхности, но определение (у [ComboStore]
     * или у пресетов ROM) не трогает — включение возвращает кнопку на место.
     */
    fun disabledKey(b: ComboButton): String =
        if (b.id.startsWith("u")) "u:" + b.id else "pres:" + b.name

    /** Список без отключённых (ключи — [disabledKey]); порядок сохранён. */
    fun withoutDisabled(all: List<ComboButton>, off: Set<String>): List<ComboButton> =
        all.filter { disabledKey(it) !in off }

    /** Уникальный id новой пользовательской кнопки — во всех секциях сразу. */
    fun newId(): String =
        uniqueId("u" + java.lang.Long.toString(System.currentTimeMillis(), 36))

    /** Свободный id с базой [base]: конфликт проверяется по ВСЕМ секциям. */
    internal fun uniqueId(base: String): String {
        var id = base
        var n = 1
        while (sections.values.any { list -> list.any { it.id == id } }) id = base + "_" + (n++)
        return id
    }

    /** Новая кнопка — в активную секцию (текущая игра сборника либо flavor). */
    fun add(b: ComboButton) {
        val key = activeSection ?: FLAVOR_KEY
        sections = sections + (key to (sections[key] ?: emptyList()) + b)
        persist()
    }

    /** Изменение кнопки активной секции; секции без кнопок не создаёт. */
    fun update(b: ComboButton) {
        val key = activeSection ?: FLAVOR_KEY
        val list = sections[key] ?: return
        sections = sections + (key to list.map { if (it.id == b.id) b else it })
        persist()
    }

    /** Удаление кнопки из активной секции; опустевшая секция убирается. */
    fun remove(id: String) {
        val key = activeSection ?: FLAVOR_KEY
        val list = sections[key] ?: return
        val next = list.filterNot { it.id == id }
        sections = if (next.isEmpty()) sections - key else sections + (key to next)
        persist()
    }

    /**
     * Разбор конфига секций: объект `{"flavor":[…],"roms":{"X":[…]}}`;
     * старый формат (плоский массив `combos_v1`) читается как секция flavor.
     * Битые кнопки изолируются по элементу (ComboJson.listFromJson), пустые
     * секции не попадают в результат; мусор — пустая карта, без исключений.
     */
    internal fun parseSections(text: String?): Map<String, List<ComboButton>> {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyMap()
        return try {
            if (trimmed.startsWith("[")) {
                // старый формат v1: один общий список → секция flavor
                mapOfFlavor(ComboJson.listFromJson(trimmed))
            } else {
                val root = JSONObject(trimmed)
                val out = HashMap<String, List<ComboButton>>()
                mapOfFlavor(ComboJson.listFromJson(root.optJSONArray("flavor")?.toString() ?: "[]"))
                    .let { out.putAll(it) }
                val roms = root.optJSONObject("roms")
                if (roms != null) {
                    roms.keys().forEach { key ->
                        val list = ComboJson.listFromJson(roms.optJSONArray(key)?.toString() ?: "[]")
                        if (list.isNotEmpty()) out[key] = list
                    }
                }
                out
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun mapOfFlavor(list: List<ComboButton>): Map<String, List<ComboButton>> =
        if (list.isEmpty()) emptyMap() else mapOf(FLAVOR_KEY to list)

    /** Конфиг секций в JSON prefs ([PREF_KEY]); пустые секции не пишутся. */
    internal fun sectionsToJson(map: Map<String, List<ComboButton>>): String {
        val root = JSONObject()
        val flavor = JSONArray()
        map[FLAVOR_KEY]?.forEach { flavor.put(ComboJson.buttonToJson(it)) }
        if (flavor.length() > 0) root.put("flavor", flavor)
        val roms = JSONObject()
        map.entries
            .filter { it.key != FLAVOR_KEY && it.value.isNotEmpty() }
            .forEach { (key, list) ->
                roms.put(key, JSONArray().apply { list.forEach { put(ComboJson.buttonToJson(it)) } })
            }
        if (roms.length() > 0) root.put("roms", roms)
        return root.toString()
    }

    /** Подмена состояния целиком (юнит-тесты); активная секция — [active]. */
    internal fun applySections(
        map: Map<String, List<ComboButton>>,
        active: String? = null
    ) {
        sections = map
        activeSection = active
    }

    /** Снимок секций для проверок в юнит-тестах. */
    internal fun sectionsSnapshot(): Map<String, List<ComboButton>> = sections

    private fun persist() {
        if (!this::prefs.isInitialized) return
        prefs.edit().putString(PREF_KEY, sectionsToJson(sections)).apply()
    }
}
