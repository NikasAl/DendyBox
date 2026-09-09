package com.dendybox.app.ui.controls

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Раскладка экранного управления — ТРИ НЕЗАВИСИМЫЕ группы:
 *
 *  [GroupId.DPAD] — крестовина (всегда видима, фиксированная позиция);
 *  [GroupId.AB]   — кластер «как на джойстике Dendy»: турбо B′/A′ сверху,
 *                   основные B/A под ними;
 *  [GroupId.META] — пилюли Select / Start отдельным блоком.
 *
 * Каждая группа независимо перемещается и масштабируется в редакторе
 * (Screen.EDIT). [Group.x]/[Group.y] — якорь группы (центр) в долях экрана,
 * [Group.scale] — масштаб всех элементов группы (1.0 = базовые dp-размеры).
 *
 * Хранится в SharedPreferences под ключом layout_v3 (при смене модели
 * раскладки старые сохранённые позиции несовместимы — сбрасываются).
 */
class LayoutStore(private val prefs: SharedPreferences) {

    enum class GroupId { DPAD, AB, META }

    data class Group(val x: Float, val y: Float, val scale: Float)

    var dpad: Group by mutableStateOf(DEF_DPAD)
        private set
    var ab: Group by mutableStateOf(DEF_AB)
        private set
    var meta: Group by mutableStateOf(DEF_META)
        private set

    init {
        val raw = prefs.getString(PREF_KEY, null)
        if (raw != null) {
            try {
                val o = org.json.JSONObject(raw)
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
            } catch (_: Exception) {
                // повреждённые данные — используем значения по умолчанию
            }
        }
        // санитизация на случай отредактированных вручную prefs
        dpad = sanitize(dpad)
        ab = sanitize(ab)
        meta = sanitize(meta)
    }

    fun group(id: GroupId): Group = when (id) {
        GroupId.DPAD -> dpad
        GroupId.AB -> ab
        GroupId.META -> meta
    }

    /** Обновить группу; значения зажимаются в допустимые пределы. */
    fun updateGroup(id: GroupId, g: Group) {
        val v = sanitize(g)
        when (id) {
            GroupId.DPAD -> dpad = v
            GroupId.AB -> ab = v
            GroupId.META -> meta = v
        }
        persist()
    }

    /** Текущая раскладка в том же JSON, что и в prefs (для экспорта в файл). */
    fun toJson(): String {
        fun j(g: Group) = org.json.JSONObject()
            .put("x", g.x.toDouble())
            .put("y", g.y.toDouble())
            .put("s", g.scale.toDouble())
        return org.json.JSONObject()
            .put("dpad", j(dpad))
            .put("ab", j(ab))
            .put("meta", j(meta))
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
        persist()
        return true
    }

    /** Вернуть все группы к заводской раскладке. */
    fun resetAll() {
        dpad = DEF_DPAD
        ab = DEF_AB
        meta = DEF_META
        persist()
    }

    private fun sanitize(g: Group) = Group(
        x = g.x.coerceIn(0.02f, 0.98f),
        y = g.y.coerceIn(0.02f, 0.98f),
        scale = g.scale.coerceIn(MIN_SCALE, MAX_SCALE)
    )

    private fun persist() {
        prefs.edit().putString(PREF_KEY, toJson()).apply()
    }

    companion object {
        private const val PREF_KEY = "layout_v3"

        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.0f

        // Дефолт (ландшафт): крестовина слева, A/B справа, Select/Start по центру.
        // x/y — якорь группы в долях экрана, scale — множитель базовых dp-размеров.
        // Обновляется скриптом: scripts/layout_to_defaults.py <layout.json>
        // (JSON — тот же, что пишет кнопка «Экспорт» в редакторе раскладки)
        val DEF_DPAD = Group(0.133f, 0.756f, 1.507f)
        val DEF_AB = Group(0.873f, 0.748f, 1.394f)
        val DEF_META = Group(0.854f, 0.265f, 1.0f)

        fun title(id: GroupId): String = when (id) {
            GroupId.DPAD -> "Крестовина"
            GroupId.AB -> "Кнопки A/B"
            GroupId.META -> "Select/Start"
        }
    }
}
