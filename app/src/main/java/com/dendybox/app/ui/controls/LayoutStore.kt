package com.dendybox.app.ui.controls

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Раскладка экранного управления — НЕЗАВИСИМЫЕ группы:
 *
 *  [GroupId.DPAD] — крестовина P1 (всегда видима);
 *  [GroupId.AB]   — кластер P1 «как на джойстике Dendy»: турбо B′/A′ сверху,
 *                   основные B/A под ними; пары можно скрывать ([abOff]);
 *  [GroupId.META] — пилюли Select / Start отдельным блоком;
 *  [GroupId.DPAD2]/[GroupId.AB2] — второй набор для локальной игры на двоих
 *                   (видим только в режиме «2 игрока», турбо-кнопок нет);
 *  [GroupId.COMBO] — комбо-кнопки: группа №0 из [comboGroups] (наполнение —
 *                   ComboStore: пресеты игры + пользовательские).
 *
 * Каждая группа независимо перемещается и масштабируется в редакторе
 * (Screen.EDIT). [Group.x]/[Group.y] — якорь группы (центр) в долях экрана,
 * [Group.scale] — масштаб всех элементов группы (1.0 = базовые dp-размеры).
 *
 * КОМБО-ГРУППЫ ([comboGroups]): комбо-кнопки можно делить на несколько
 * блоков — у каждого своё имя, якорь/масштаб и упорядоченный список
 * участников (ключи [ComboStore.disabledKey] — те же устойчивые ключи,
 * что у отключений: свои «u:id», пресеты «pres:имя»). Кнопки, не
 * прописанные ни в одной группе, показываются в группе №0 после её
 * участников (созданная кнопка появляется там автоматически). Ключи
 * отсутствующих кнопок (другой ROM сборника, удалённая кнопка) остаются
 * «дремать» в списке — расстановка восстанавливается при возврате рома.
 * Группа №0 заменяет старое поле «combo»: старый конфиг без раздела
 * comboGroups читается как одна группа с позицией «combo».
 *
 * Кроме групп конфиг хранит:
 *  * ключи ОТКЛЮЧЁННЫХ комбо-кнопок ([combosOff]): кнопка с таким ключом
 *    не рисуется и не активна, но её определение сохраняется — включение
 *    возвращает её. Ключи считает [ComboStore.disabledKey];
 *  * скрытые ПОДБЛОКИ кластера A/B ([abOff]): «main» — пара A/B,
 *    «turbo» — пара турбо A′/B′. Скрытая пара не рисуется и не занимает
 *    места, оставшаяся обнимается рамкой по контенту.
 *
 * Хранится в SharedPreferences под ключом layout_v3 (при смене модели
 * раскладки старые сохранённые позиции несовместимы — сбрасываются).
 */
class LayoutStore(private val prefs: SharedPreferences) {

    /** Блок комбо-кнопок: имя, якорь/масштаб и участники в порядке сетки. */
    data class ComboGroup(
        val name: String,
        val x: Float,
        val y: Float,
        val scale: Float,
        /** Ключи кнопок (ComboStore.disabledKey) в порядке отображения. */
        val keys: List<String> = emptyList()
    )

    enum class GroupId { DPAD, AB, META, DPAD2, AB2, COMBO }

    data class Group(val x: Float, val y: Float, val scale: Float)

    var dpad: Group by mutableStateOf(DEF_DPAD)
        private set
    var ab: Group by mutableStateOf(DEF_AB)
        private set
    var meta: Group by mutableStateOf(DEF_META)
        private set
    var dpad2: Group by mutableStateOf(DEF_DPAD2)
        private set
    var ab2: Group by mutableStateOf(DEF_AB2)
        private set

    /**
     * Комбо-группы (минимум одна); №0 — [GroupId.COMBO] (совместимость:
     * group/updateGroup(GroupId.COMBO) работают с ней).
     */
    var comboGroups: List<ComboGroup> by mutableStateOf(listOf(DEF_COMBO_GROUP))
        private set

    /** Ключи отключённых комбо-кнопок (см. [ComboStore.disabledKey]). */
    var combosOff: Set<String> by mutableStateOf(emptySet())
        private set

    /** Скрытые подблоки кластера A/B: [AB_MAIN] и/или [AB_TURBO]. */
    var abOff: Set<String> by mutableStateOf(emptySet())
        private set

    init {
        val raw = prefs.getString(PREF_KEY, null)
        var ok = false
        if (raw != null) {
            try {
                val o = org.json.JSONObject(raw)
                // Разделы появились позже раскладки v3 — отсутствие не ошибка
                combosOff = readCombosOff(o)
                abOff = readAbOff(o)
                fun read(k: String, def: Group): Group {
                    val g = o.optJSONObject(k) ?: return def
                    return try {
                        Group(
                            x = g.getDouble("x").toFloat(),
                            y = g.getDouble("y").toFloat(),
                            scale = g.getDouble("s").toFloat()
                        )
                    } catch (_: Exception) {
                        def
                    }
                }
                dpad = read("dpad", DEF_DPAD)
                ab = read("ab", DEF_AB)
                meta = read("meta", DEF_META)
                dpad2 = read("dpad2", DEF_DPAD2)
                ab2 = read("ab2", DEF_AB2)
                // комбо-группы: нет раздела — одна группа из старого «combo»
                comboGroups = readComboGroups(o)
                ok = true
            } catch (_: Exception) {
                // повреждённые данные — используем значения по умолчанию
            }
        }
        if (!ok) comboGroups = listOf(DEF_COMBO_GROUP)
        // санитизация на случай отредактированных вручную prefs
        dpad = sanitize(dpad)
        ab = sanitize(ab)
        meta = sanitize(meta)
        dpad2 = sanitize(dpad2)
        ab2 = sanitize(ab2)
    }

    fun group(id: GroupId): Group = when (id) {
        GroupId.DPAD -> dpad
        GroupId.AB -> ab
        GroupId.META -> meta
        GroupId.DPAD2 -> dpad2
        GroupId.AB2 -> ab2
        // комбо-группа №0 — преемник старого поля combo
        GroupId.COMBO -> comboGroups[0].let { Group(it.x, it.y, it.scale) }
    }

    /** Обновить группу; значения зажимаются в допустимые пределы. */
    fun updateGroup(id: GroupId, g: Group) {
        val v = sanitize(g)
        when (id) {
            GroupId.DPAD -> dpad = v
            GroupId.AB -> ab = v
            GroupId.META -> meta = v
            GroupId.DPAD2 -> dpad2 = v
            GroupId.AB2 -> ab2 = v
            GroupId.COMBO -> updateComboGroupPos(0, v.x, v.y, v.scale)
        }
        if (id != GroupId.COMBO) persist()
    }

    /** Текущая раскладка в том же JSON, что и в prefs (для экспорта в файл). */
    fun toJson(): String {
        fun j(g: Group) = org.json.JSONObject()
            .put("x", g.x.toDouble())
            .put("y", g.y.toDouble())
            .put("s", g.scale.toDouble())
        val off = org.json.JSONArray()
        combosOff.sorted().forEach { off.put(it) }
        val abOffArr = org.json.JSONArray()
        abOff.sorted().forEach { abOffArr.put(it) }
        val cgs = org.json.JSONArray()
        comboGroups.forEach { cg ->
            cgs.put(
                org.json.JSONObject()
                    .put("name", cg.name)
                    .put("x", cg.x.toDouble())
                    .put("y", cg.y.toDouble())
                    .put("s", cg.scale.toDouble())
                    .put("keys", org.json.JSONArray().apply { cg.keys.forEach { put(it) } })
            )
        }
        return org.json.JSONObject()
            .put("dpad", j(dpad))
            .put("ab", j(ab))
            .put("meta", j(meta))
            .put("dpad2", j(dpad2))
            .put("ab2", j(ab2))
            // легаси-поле: позиция комбо-группы №0 (старые версии/скрипты)
            .put("combo", j(Group(comboGroups[0].x, comboGroups[0].y, comboGroups[0].scale)))
            .put("comboGroups", cgs)
            .put("combosOff", off)
            .put("abOff", abOffArr)
            .toString()
    }

    /**
     * Применить раскладку из JSON (импорт из файла); значения зажимаются
     * в допустимые пределы. false — если данные не похожи на раскладку.
     */
    fun applyJson(raw: String): Boolean {
        val o = try {
            org.json.JSONObject(raw)
        } catch (_: Exception) {
            return false
        }
        fun read(k: String): Group? = try {
            val g = o.getJSONObject(k)
            Group(
                x = g.getDouble("x").toFloat(),
                y = g.getDouble("y").toFloat(),
                scale = g.getDouble("s").toFloat()
            )
        } catch (_: Exception) {
            null
        }
        val d = read("dpad") ?: return false
        val a = read("ab") ?: return false
        val m = read("meta") ?: return false
        dpad = sanitize(d)
        ab = sanitize(a)
        meta = sanitize(m)
        // группы P2 необязательны: старый экспорт (3 группы) тоже импортируется
        read("dpad2")?.let { dpad2 = sanitize(it) }
        read("ab2")?.let { ab2 = sanitize(it) }
        // комбо-группы: старый экспорт без раздела — одна группа из «combo»
        comboGroups = readComboGroups(o)
        // отключённые комбо и скрытые пары A/B тоже необязательны: старый
        // экспорт без них означает «все включены» — импорт заменяет состояние целиком
        combosOff = readCombosOff(o)
        abOff = readAbOff(o)
        persist()
        return true
    }

    /**
     * Вернуть все группы к заводской раскладке. Отключённые комбо-кнопки,
     * скрытые пары A/B и лишние комбо-группы тоже сбрасываются
     * (заводское состояние — все включены, одна комбо-группа).
     */
    fun resetAll() {
        dpad = DEF_DPAD
        ab = DEF_AB
        meta = DEF_META
        dpad2 = DEF_DPAD2
        ab2 = DEF_AB2
        comboGroups = listOf(DEF_COMBO_GROUP)
        combosOff = emptySet()
        abOff = emptySet()
        persist()
    }

    /**
     * Включить/выключить комбо-кнопку с ключом [key] (см.
     * [ComboStore.disabledKey]): выключенная не рисуется и не активна
     * на игровой поверхности, определение кнопки не трогается.
     */
    fun setComboEnabled(key: String, enabled: Boolean) {
        val next = if (enabled) combosOff - key else combosOff + key
        if (next == combosOff) return
        combosOff = next
        persist()
    }

    /** Кнопка с ключом [key] показывается на игровой поверхности? */
    fun isComboEnabled(key: String): Boolean = key !in combosOff

    // ------------------------------------------------------------------
    // Комбо-группы: создание/переименование/удаление, состав и порядок
    // ------------------------------------------------------------------

    /** Позиция/масштаб комбо-группы [i] (значения зажимаются). */
    fun updateComboGroupPos(i: Int, x: Float, y: Float, scale: Float) {
        if (i !in comboGroups.indices) return
        val s = sanitize(Group(x, y, scale))
        comboGroups = comboGroups.mapIndexed { gi, g ->
            if (gi == i) g.copy(x = s.x, y = s.y, scale = s.scale) else g
        }
        persist()
    }

    /** Переименовать комбо-группу [i] (пустое имя игнорируется). */
    fun renameComboGroup(i: Int, name: String) {
        val nm = name.trim()
        if (i !in comboGroups.indices || nm.isEmpty()) return
        comboGroups = comboGroups.mapIndexed { gi, g ->
            if (gi == i) g.copy(name = nm) else g
        }
        persist()
    }

    /** Новая комбо-группа в заводском месте; возвращает её индекс. */
    fun addComboGroup(name: String? = null): Int {
        val nm = name?.trim()?.takeUnless { it.isEmpty() } ?: "Группа ${comboGroups.size + 1}"
        comboGroups = comboGroups + ComboGroup(nm, DEF_COMBO.x, DEF_COMBO.y, DEF_COMBO.scale)
        persist()
        return comboGroups.size - 1
    }

    /**
     * Удалить комбо-группу [i]; её кнопки переезжают в конец первой
     * оставшейся группы. false — группа последняя (удалять нельзя).
     */
    fun removeComboGroup(i: Int): Boolean {
        if (i !in comboGroups.indices || comboGroups.size <= 1) return false
        val removed = comboGroups[i]
        val rest = comboGroups.filterIndexed { gi, _ -> gi != i }.toMutableList()
        // кнопки удаляемой группы переезжают в конец первой оставшейся
        val first = rest[0]
        rest[0] = first.copy(keys = first.keys + removed.keys.filter { it !in first.keys })
        comboGroups = rest.toList()
        persist()
        return true
    }

    /** Группа кнопки с ключом [key]: первая содержащая, иначе №0 (не прописана). */
    fun comboGroupOfKey(key: String): Int =
        comboGroups.indexOfFirst { key in it.keys }.let { if (it >= 0) it else 0 }

    /** Переписать список участников комбо-группы [i] (пустые/дубли убираются). */
    fun setComboGroupKeys(i: Int, keys: List<String>) {
        if (i !in comboGroups.indices) return
        val clean = keys.filter { it.isNotBlank() }.distinct()
        comboGroups = comboGroups.mapIndexed { gi, g ->
            if (gi == i) g.copy(keys = clean) else g
        }
        persist()
    }

    // ------------------------------------------------------------------
    // Скрытие подблоков кластера A/B
    // ------------------------------------------------------------------

    /** Скрыта ли пара кластера A/B ([AB_MAIN] или [AB_TURBO])? */
    fun isAbHidden(part: String): Boolean = part in abOff

    /** Скрыть/вернуть пару кластера A/B ([AB_MAIN] или [AB_TURBO]). */
    fun setAbHidden(part: String, hidden: Boolean) {
        val next = if (hidden) abOff + part else abOff - part
        if (next == abOff) return
        abOff = next
        persist()
    }

    /** Раздел «combosOff» конфига: массив строк; нет/битый — пустой набор. */
    private fun readCombosOff(o: org.json.JSONObject): Set<String> = try {
        val arr = o.getJSONArray("combosOff")
        (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeUnless { it.isEmpty() }
        }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    /** Раздел «abOff»: только известные подблоки; нет/битый — пустой набор. */
    private fun readAbOff(o: org.json.JSONObject): Set<String> = try {
        val arr = o.getJSONArray("abOff")
        (0 until arr.length()).mapNotNull { i ->
            when (arr.optString(i)) {
                AB_MAIN -> AB_MAIN
                AB_TURBO -> AB_TURBO
                else -> null
            }
        }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    /**
     * Раздел «comboGroups»: массив групп; нет/пустой/битый — одна группа
     * с позицией легаси-поля «combo» (или заводская).
     */
    private fun readComboGroups(o: org.json.JSONObject): List<ComboGroup> {
        val fallback = try {
            val c = o.getJSONObject("combo")
            sanitize(Group(c.getDouble("x").toFloat(), c.getDouble("y").toFloat(), c.getDouble("s").toFloat()))
        } catch (_: Exception) {
            sanitize(DEF_COMBO)
        }
        val arr = o.optJSONArray("comboGroups") ?: return listOf(defaultComboGroup(fallback))
        val out = ArrayList<ComboGroup>()
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val pos = try {
                sanitize(
                    Group(
                        g.getDouble("x").toFloat(),
                        g.getDouble("y").toFloat(),
                        g.getDouble("s").toFloat()
                    )
                )
            } catch (_: Exception) {
                sanitize(DEF_COMBO)
            }
            val name = g.optString("name").takeUnless { it.isBlank() } ?: "Группа ${out.size + 1}"
            val keys = ArrayList<String>()
            val ka = g.optJSONArray("keys")
            if (ka != null) {
                for (k in 0 until ka.length()) {
                    val key = ka.optString(k)
                    if (key.isNotEmpty() && key !in keys) keys.add(key)
                }
            }
            out.add(ComboGroup(name, pos.x, pos.y, pos.scale, keys))
        }
        if (out.isEmpty()) return listOf(defaultComboGroup(fallback))
        return out
    }

    private fun defaultComboGroup(pos: Group) =
        ComboGroup("Комбо-кнопки", pos.x, pos.y, pos.scale)

    private fun sanitize(g: Group) = Group(
        x = g.x.coerceIn(0.02f, 0.98f),
        y = g.y.coerceIn(0.02f, 0.98f),
        scale = g.scale.coerceIn(MIN_SCALE, MAX_SCALE)
    )

    private fun persist() {
        prefs.edit().putString(PREF_KEY, toJson()).apply()
    }

    /**
     * Разовый пресет для локальной игры на двоих: P1 слева, P2 справа
     * (телефон лежит горизонтально между игроками). Вызывается при первом
     * включении режима «2 игрока»; масштаб P1 сохраняется, масштаб P2 —
     * как у P1. Повторные включения раскладку не трогают.
     */
    fun applyTwoPresetOnce() {
        if (prefs.getBoolean(PREF_2P_PRESET, false)) return
        dpad = sanitize(Group(0.07f, 0.72f, dpad.scale))
        ab = sanitize(Group(0.27f, 0.72f, ab.scale))
        dpad2 = sanitize(Group(0.73f, 0.72f, dpad.scale))
        ab2 = sanitize(Group(0.93f, 0.72f, ab.scale))
        persist()
        prefs.edit().putBoolean(PREF_2P_PRESET, true).apply()
    }

    /**
     * Разовый пресет пакета-скина «Гаджет» (референс — корпус консоли):
     * крестовина слева, кластер A/B справа, Select/Start по центру под
     * кадром, комбо-группа №0 — вверх по центру (чтобы не сталкивалась
     * с META). Вызывается при первом включении корпусного скина; масштабы
     * групп сохраняются, позиции зажимаются санитизацией. Повторные
     * включения и переключения скинов раскладку НЕ трогают — блоки
     * остаются перемещаемыми, пользовательские позиции не перекрываются:
     * комбо-группа сдвигается только если стоит на заводской точке.
     */
    fun applyGadgetPresetOnce() {
        if (prefs.getBoolean(PREF_GADGET_PRESET, false)) return
        dpad = sanitize(Group(0.13f, 0.75f, dpad.scale))
        ab = sanitize(Group(0.87f, 0.70f, ab.scale))
        meta = sanitize(Group(0.5f, 0.895f, meta.scale))
        val first = comboGroups[0]
        if (first.x == DEF_COMBO.x && first.y == DEF_COMBO.y) {
            comboGroups = comboGroups.mapIndexed { i, cg ->
                if (i == 0) cg.copy(y = 0.115f) else cg
            }
        }
        persist()
        prefs.edit().putBoolean(PREF_GADGET_PRESET, true).apply()
    }

    companion object {
        private const val PREF_KEY = "layout_v3"
        private const val PREF_2P_PRESET = "layout_2p_preset_v1"
        private const val PREF_GADGET_PRESET = "layout_gadget_preset_v1"

        /** Подблок кластера A/B: основная пара A/B. */
        const val AB_MAIN = "main"

        /** Подблок кластера A/B: пара турбо A′/B′. */
        const val AB_TURBO = "turbo"

        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.0f

        // Дефолт (ландшафт): крестовина слева, A/B справа, Select/Start по центру.
        // x/y — якорь группы в долях экрана, scale — множитель базовых dp-размеров.
        // Обновляется скриптом: scripts/layout_to_defaults.py <layout.json>
        // (JSON — тот же, что пишет кнопка «Экспорт» в редакторе раскладки)
        val DEF_DPAD = Group(0.133f, 0.756f, 1.507f)
        val DEF_AB = Group(0.873f, 0.748f, 1.394f)
        val DEF_META = Group(0.854f, 0.265f, 1.0f)

        // Дефолт второго набора (локальный 2P): P2 справа, зеркально P1
        val DEF_DPAD2 = Group(0.72f, 0.70f, 1.0f)
        val DEF_AB2 = Group(0.93f, 0.70f, 1.0f)

        // Комбо-кнопки: низ по центру, между крестовиной и A/B
        val DEF_COMBO = Group(0.5f, 0.86f, 1.0f)

        /** Заводская комбо-группа №0 (единственная после сброса). */
        val DEF_COMBO_GROUP = ComboGroup("Комбо-кнопки", DEF_COMBO.x, DEF_COMBO.y, DEF_COMBO.scale)

        fun title(id: GroupId): String = when (id) {
            GroupId.DPAD -> "Крестовина"
            GroupId.AB -> "Кнопки A/B"
            GroupId.META -> "Select/Start"
            GroupId.DPAD2 -> "Крестовина 2 (P2)"
            GroupId.AB2 -> "A/B игрока 2"
            GroupId.COMBO -> "Комбо-кнопки"
        }
    }
}
