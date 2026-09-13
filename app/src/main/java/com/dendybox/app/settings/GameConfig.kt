package com.dendybox.app.settings

import android.content.Context
import org.json.JSONObject

/**
 * Конфиг конкретной игры: `roms/<flavor>.json` → `assets/game.json`
 * (копируется сборкой рядом с ROM, файл опционален).
 *
 * Поддержан раздел `damage` — вибро-отклик при получении урона:
 * наблюдение за байтом RAM. Адрес ищется в fceux (Hex/Memory viewer).
 *
 * Пример:
 * ```json
 * {
 *   "damage": { "addr": "0x00F0", "mode": "dec", "value": 0 }
 * }
 * ```
 *   * `addr`  — адрес байта (число или hex-строка "0x00F0"/"$00F0"),
 *               базовая RAM $0000–$07FF (зеркала $0800–$1FFF тоже годятся);
 *   * `mode`  — dec: байт УМЕНЬШИЛСЯ (типичный HP), inc: увеличился,
 *               eq: стал равен value (байты-метки урона), change: любое изменение;
 *   * `value` — эталон для mode=eq (число или hex-строка).
 *
 * Если файла нет или раздел damage не задан — вибрация урона недоступна,
 * в «Настройках» остаётся только вибро-отклик нажатий.
 */
object GameConfig {

    data class DamageWatch(val addr: Int, val mode: Int, val value: Int)

    /** Наблюдаемый байт урона или null (конфига нет — настройка не упоминается). */
    var damageWatch: DamageWatch? = null
        private set

    /** Читается один раз при старте приложения; отсутствие файла — не ошибка. */
    fun load(ctx: Context) {
        damageWatch = null
        val text = try {
            ctx.assets.open("game.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return
        }
        try {
            val root = JSONObject(text)
            val d = root.optJSONObject("damage") ?: return
            val addr = parseNum(d, "addr") ?: return
            val mode = when (d.optString("mode", "change").trim().lowercase()) {
                "dec" -> 1
                "inc" -> 2
                "eq" -> 3
                else -> 0
            }
            val value = parseNum(d, "value") ?: 0
            if (addr in 0..0x1FFF) damageWatch = DamageWatch(addr, mode, value)
        } catch (_: Exception) {
            // битый конфиг — играем без вибрации урона
            damageWatch = null
        }
    }

    /** Число из JSON: Number или строка "0x1F" / "$1F" / десятичная. */
    private fun parseNum(o: JSONObject, key: String): Int? = try {
        when (val v = o.get(key)) {
            is Number -> v.toInt()
            is String -> {
                val s = v.trim().removePrefix("$")
                if (s.startsWith("0x", ignoreCase = true)) {
                    s.substring(2).toInt(16)
                } else {
                    s.toInt()
                }
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
