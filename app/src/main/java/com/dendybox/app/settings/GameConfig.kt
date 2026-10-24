package com.dendybox.app.settings

import android.content.Context
import com.dendybox.app.ui.controls.ComboButton
import com.dendybox.app.ui.controls.ComboJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Конфиг конкретной игры: `roms/<flavor>.json` → `assets/game.json`
 * (копируется сборкой рядом с ROM, файл опционален).
 *
 * Поддержан раздел `damage` — вибро-отклик при получении урона:
 * наблюдение за байтом RAM. Адрес ищется в fceux (Hex/Memory viewer).
 *
 * Поддержан раздел `combos` — пресеты программируемых комбо-кнопок,
 * характерных для игры (суперудары и т.п.): появляются на игровой
 * поверхности автоматически, редактировать их в конфигураторе нельзя.
 * Шаг: `{"b":"A","d":100}` — кнопка (LEFT/RIGHT/UP/DOWN/A/B/START/SELECT)
 * и пауза в мс (кратно 100) после отпускания предыдущей кнопки.
 * Опционально шаг может задавать: `w` — массив дополнительных кнопок
 * (нажимаются одновременно с главной и отпускаются вместе с ней),
 * `h` — длительность удержания в мс (кратно 100, 100–2000; нет — 100,
 * press-and-hold вместо клика) и `anchor:"press"` — пауза `d` отсчитывается
 * от НАЖАТИЯ предыдущего шага (перекрывающиеся удержания). Старые записи
 * без новых полей играются как раньше.
 *
 * Пример:
 * ```json
 * {
 *   "netplay": false,
 *   "damage": { "addr": "0x00F0", "mode": "dec", "value": 0 },
 *   "combos": [
 *     { "name": "Суперудар", "steps": [ {"b":"A","d":0}, {"b":"B","d":100} ] }
 *   ]
 * }
 * ```
 *   * `netplay` — видимость сетевого режима (пункт «Игра по сети» в меню
 *               паузы): `false` — игра одиночная, сетевой режим скрывается.
 *               Флаг необязателен, по умолчанию `true`.
 *   * `addr`  — адрес байта (число или hex-строка "0x00F0"/"$00F0"),
 *               базовая RAM $0000–$07FF (зеркала $0800–$1FFF тоже годятся);
 *   * `mode`  — dec: байт УМЕНЬШИЛСЯ (типичный HP), inc: увеличился,
 *               eq: стал равен value (байты-метки урона), change: любое изменение;
 *   * `value` — эталон для mode=eq (число или hex-строка).
 *
 * Если файла нет или раздел damage не задан — настройка вибрации в
 * приложении не показывается (другой вибрации нет — нажатия не вибрируют).
 */
object GameConfig {

    data class DamageWatch(val addr: Int, val mode: Int, val value: Int)

    /** Наблюдаемый байт урона или null (конфига нет — настройка не показывается). */
    var damageWatch: DamageWatch? = null
        private set

    /** Видимость сетевого режима; по умолчанию включён для всех игр. */
    var netplayEnabled: Boolean = true
        private set

    /** Пресеты комбо-кнопок игры (id назначает ComboStore.effective()). */
    var comboPresets: List<ComboButton> = emptyList()
        private set

    /** Читается один раз при старте приложения; отсутствие файла — не ошибка. */
    fun load(ctx: Context) {
        damageWatch = null
        netplayEnabled = true
        comboPresets = emptyList()
        val text = try {
            ctx.assets.open("game.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return
        }
        try {
            val root = JSONObject(text)
            netplayEnabled = root.optBoolean("netplay", true)
            parseCombos(root.optJSONArray("combos"))?.let { comboPresets = it }
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

    /** Пресеты комбо: битые записи пропускаются, пустой/битый раздел — без пресетов. */
    private fun parseCombos(arr: JSONArray?): List<ComboButton>? {
        arr ?: return null
        val out = ArrayList<ComboButton>(arr.length())
        for (i in 0 until arr.length()) {
            val combo = try {
                ComboJson.buttonFromJson(
                    arr.getJSONObject(i).put("id", "preset")
                )
            } catch (_: Exception) {
                null
            } ?: continue
            out.add(combo)
        }
        return out
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
