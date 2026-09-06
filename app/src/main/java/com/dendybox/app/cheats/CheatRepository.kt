package com.dendybox.app.cheats

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Чит = RAM-патч (адрес:значение[, сравнение]) ИЛИ код Game Genie.
 *
 * RAM-читы применяются ядром напрямую в память (каждый кадр, до retro_run) —
 * это точная семантика RAM-читов FCEUX. Коды Game Genie передаются ядру через
 * libretro cheat API (retro_cheat_set) — ядро патчит шину адреса правильно.
 *
 * Формат FCEUX .cht — текстовые строки:
 *   0738:09        0738=09        0x0738:0x09       — RAM
 *   0738+FF:09     (опциональное сравнение)
 *   SXIOPO                                           — Game Genie (6 или 8 букв)
 * Комментарии: // ; #
 */
data class Cheat(
    val raw: String,            // исходная строка (для экспорта .cht)
    val title: String,          // комментарий или пояснение
    val enabled: Boolean = true,
    val ggCode: String? = null, // != null -> это код Game Genie
    val addr: Int = 0,          // только для RAM-читов
    val value: Int = 0,
    val cmp: Int = -1
)

class CheatRepository(private val context: Context, private val romHash: String) {

    companion object {
        // Алфавит Game Genie (NES): буква -> полубайт
        private val GG_MAP = mapOf(
            'A' to 0x0, 'P' to 0x1, 'Z' to 0x2, 'L' to 0x3,
            'G' to 0x4, 'I' to 0x5, 'T' to 0x6, 'Y' to 0x7,
            'E' to 0x8, 'O' to 0x9, 'X' to 0xA, 'U' to 0xB,
            'K' to 0xC, 'S' to 0xD, 'V' to 0xE, 'N' to 0xF
        )
        private val GG_RE = Regex("^([APZLGITYEOXUKSVN]{6})([APZLGITYEOXUKSVN]{2})?$")
        private val RAM_RE = Regex(
            "^\\s*(?:0x|\\$)?([0-9A-Fa-f]{1,4})\\s*" +            // адрес
                "(?:\\+\\s*(?:0x|\\$)?([0-9A-Fa-f]{1,2}))?\\s*" + // сравнение (опц.)
                "[:=]\\s*(?:0x|\\$)?([0-9A-Fa-f]{1,2})\\s*$"      // значение
        )

        /** Расшифровка 6-буквенного кода GG для отображения: адрес/значение. */
        fun decodeGG(code: String): Pair<Int, Int>? {
            val c = code.trim().uppercase()
            val m = GG_RE.matchEntire(c) ?: return null
            val n = m.groupValues[1].map { GG_MAP[it]!! }
            val value = (n[0] shl 4) or n[1]
            val addr = 0x8000 or ((n[5] and 0x7) shl 12) or (n[4] shl 8) or (n[3] shl 4) or n[2]
            return addr to value
        }

        fun isGG(line: String): Boolean = GG_RE.matchEntire(line.trim().uppercase()) != null
    }

    val cheats = mutableStateListOf<Cheat>()

    /** Счётчик изменений — UI и движок узнают, что надо пересобрать активный набор. */
    val revision = mutableStateOf(0)

    private val prefs = context.getSharedPreferences("cheats_$romHash", Context.MODE_PRIVATE)

    init {
        load()
    }

    // ------------------------------------------------------------------ //

    /** Импорт текста (.cht или скопированных строк). Возвращает (распознано, пропущено). */
    fun importText(text: String): Pair<Int, Int> {
        var parsed = 0
        var skipped = 0
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val cheat = parseLine(line)
            if (cheat != null) {
                cheats.add(cheat)
                parsed++
            } else {
                skipped++
            }
        }
        persist()
        return parsed to skipped
    }

    /** Ручной ввод: адрес : значение [: сравнение]. */
    fun addRam(addrHex: String, valueHex: String, cmpHex: String?, title: String): Boolean {
        val addr = addrHex.removePrefix("0x").removePrefix("$").toIntOrNull(16) ?: return false
        val value = valueHex.removePrefix("0x").removePrefix("$").toIntOrNull(16) ?: return false
        if (addr !in 0..0xFFFF || value !in 0..0xFF) return false
        val cmp = cmpHex?.trim()?.takeIf { it.isNotEmpty() }
            ?.removePrefix("0x")?.removePrefix("$")?.toIntOrNull(16) ?: -1
        if (cmp !in -1..0xFF) return false
        cheats.add(
            Cheat(
                raw = formatLine(addr, value, cmp),
                title = title.ifBlank { "" },
                addr = addr, value = value, cmp = cmp
            )
        )
        persist()
        return true
    }

    fun addGG(code: String): Boolean {
        val c = code.trim().uppercase()
        if (GG_RE.matchEntire(c) == null) return false
        cheats.add(Cheat(raw = c, title = "Game Genie", ggCode = c))
        persist()
        return true
    }

    /** Ручной ввод одной строкой: «0738:09», «0738+FF:09» или код Game Genie. */
    fun addFromText(text: String, title: String): Boolean {
        val cheat = parseLine(text.trim()) ?: return false
        cheats.add(if (title.isBlank()) cheat else cheat.copy(title = title))
        persist()
        return true
    }

    fun remove(index: Int) {
        cheats.removeAt(index)
        persist()
    }

    fun setEnabled(index: Int, enabled: Boolean) {
        cheats[index] = cheats[index].copy(enabled = enabled)
        persist()
    }

    fun clearAll() {
        cheats.clear()
        persist()
    }

    // ------------------------------------------------------------------ //

    /** Активные RAM-читы тройками [addr, value, cmp] для Native.setCheats. */
    fun packedActiveRam(): IntArray {
        val list = ArrayList<Int>()
        for (c in cheats) {
            if (!c.enabled || c.ggCode != null) continue
            list.add(c.addr)
            list.add(c.value)
            list.add(c.cmp)
        }
        return list.toIntArray()
    }

    /** Активные коды Game Genie для Native.setGgCheats. */
    fun activeGg(): List<String> =
        cheats.filter { it.enabled && it.ggCode != null }.map { it.ggCode!! }

    fun exportText(): String = cheats.joinToString("\n") { c ->
        if (c.title.isNotBlank()) "${c.raw} // ${c.title}" else c.raw
    }

    // ------------------------------------------------------------------ //

    private fun parseLine(line: String): Cheat? {
        // Отделяем комментарий (//, ; или #) — берём первый встретившийся разделитель
        val idx = listOf(line.indexOf("//"), line.indexOf(";"), line.indexOf("#"))
            .filter { it >= 0 }
            .minOrNull()
        val body = if (idx != null) line.substring(0, idx).trim() else line.trim()
        val comment = if (idx != null) {
            line.substring(idx).trimStart('/', ';', '#', ' ').trim()
        } else ""
        if (body.isEmpty()) return null

        // 1) Game Genie
        if (GG_RE.matchEntire(body.uppercase()) != null) {
            return Cheat(raw = body.uppercase(), title = comment.ifBlank { "Game Genie" }, ggCode = body.uppercase())
        }

        // 2) RAM-чит
        val m = RAM_RE.matchEntire(body) ?: return null
        val addr = m.groupValues[1].toInt(16)
        val cmpStr = m.groupValues[2]
        val value = m.groupValues[3].toInt(16)
        val cmp = if (cmpStr.isEmpty()) -1 else cmpStr.toInt(16)
        return Cheat(
            raw = formatLine(addr, value, cmp),
            title = comment,
            addr = addr, value = value, cmp = cmp
        )
    }

    private fun formatLine(addr: Int, value: Int, cmp: Int): String =
        if (cmp >= 0) "%04X+%02X:%02X".format(addr, cmp, value) else "%04X:%02X".format(addr, value)

    private fun persist() {
        val arr = JSONArray()
        for (c in cheats) {
            arr.put(
                JSONObject()
                    .put("raw", c.raw)
                    .put("title", c.title)
                    .put("enabled", c.enabled)
                    .put("gg", c.ggCode ?: "")
                    .put("addr", c.addr)
                    .put("value", c.value)
                    .put("cmp", c.cmp)
            )
        }
        prefs.edit().putString("data", arr.toString()).apply()
        revision.value++
    }

    private fun load() {
        val s = prefs.getString("data", null) ?: return
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                cheats.add(
                    Cheat(
                        raw = o.getString("raw"),
                        title = o.getString("title"),
                        enabled = o.getBoolean("enabled"),
                        ggCode = o.getString("gg").ifEmpty { null },
                        addr = o.getInt("addr"),
                        value = o.getInt("value"),
                        cmp = o.getInt("cmp")
                    )
                )
            }
        } catch (_: Exception) {
            // повреждённые данные — начинаем с пустого списка
        }
    }
}
