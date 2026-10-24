package com.dendybox.app.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dendybox.app.ui.controls.ComboButton
import com.dendybox.app.ui.controls.ComboJson
import org.json.JSONObject

/**
 * Конфиг конкретной игры: `roms/<flavor>.json` → `assets/game.json`
 * (копируется сборкой рядом с ROM, файл опционален).
 *
 * Поддержан раздел `damage` — вибро-отклик при получении урона:
 * наблюдение за байтом RAM. Адрес ищется в fceux (Hex/Memory viewer).
 *
 * Поддержан раздел `combos` — пресеты программируемых комбо-кнопок:
 *  * одиночная игра — пресеты этого ROM;
 *  * сборник — общие пресеты для игр без собственного раздела `combos`
 *    (у большинства игр сборника пресеты задаются персонально — в записи
 *    игры в roms/games.json, см. CollectionCatalog).
 * Пресеты появляются на игровой поверхности автоматически, редактировать
 * их в конфигураторе нельзя. Шаг: `{"b":"A","d":100}` — кнопка
 * (LEFT/RIGHT/UP/DOWN/A/B/START/SELECT) и пауза в мс (кратно 50, 0–500)
 * после отпускания предыдущей кнопки. Опционально шаг может задавать:
 * `w` — массив дополнительных кнопок (нажимаются одновременно с главной
 * и отпускаются вместе с ней), `h` — длительность удержания в мс (кратно
 * 50, 50–500; нет — 100, press-and-hold вместо клика) и `anchor:"press"` —
 * пауза `d` отсчитывается от НАЖАТИЯ предыдущего шага (перекрывающиеся
 * удержания).
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

    /** Разобранный game.json — чистый разбор в [parseGameJson] (юнит-тесты). */
    internal data class Parsed(
        val netplay: Boolean,
        val combos: List<ComboButton>,
        val damage: DamageWatch?
    )

    /** Наблюдаемый байт урона или null (конфига нет — настройка не показывается). */
    var damageWatch: DamageWatch? = null
        private set

    /** Видимость сетевого режима; по умолчанию включён для всех игр. */
    var netplayEnabled: Boolean = true
        private set

    /** Пресеты комбо из конфига сборки (roms/<flavor>.json). */
    private var flavorCombos: List<ComboButton> = emptyList()

    /**
     * Пресеты комбо активного рома (id назначает ComboStore.effective()).
     * Реактивно: конфигуратор и блок комбо-кнопок обновляются при смене
     * игры сборника без пересоздания экранов.
     */
    var comboPresets: List<ComboButton> by mutableStateOf(emptyList())
        private set

    /** Название активного рома сборника (подпись в конфигураторе); null — одиночная игра. */
    var activeRomTitle: String? by mutableStateOf(null)
        private set

    /**
     * Загружен ром сборника: активировать его пресеты ([romCombos] из
     * манифеста; null — у записи игры раздела нет, остаются общие пресеты
     * конфига сборки) и подпись [romTitle]. Для одиночной игры ничего не
     * делает нужного — пресеты уже стоят из [load]; вызов с null безопасен.
     */
    fun selectRom(romCombos: List<ComboButton>?, romTitle: String?) {
        comboPresets = romCombos ?: flavorCombos
        activeRomTitle = romTitle
    }

    /** Читается один раз при старте приложения; отсутствие файла — не ошибка. */
    fun load(ctx: Context) {
        applyParsed(Parsed(true, emptyList(), null))
        val text = try {
            ctx.assets.open("game.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return
        }
        applyParsed(parseGameJson(text))
    }

    internal fun applyParsed(p: Parsed) {
        netplayEnabled = p.netplay
        damageWatch = p.damage
        flavorCombos = p.combos
        comboPresets = p.combos
        activeRomTitle = null
    }

    /**
     * Чистый разбор game.json: netplay, пресеты комбо, байт урона.
     * Битый json или раздел — значения по умолчанию (играем без
     * спец-настроек), исключений наружу нет.
     */
    internal fun parseGameJson(text: String): Parsed = try {
        val root = JSONObject(text)
        val damage = root.optJSONObject("damage")?.let { d ->
            val addr = parseNum(d, "addr") ?: return@let null
            val mode = when (d.optString("mode", "change").trim().lowercase()) {
                "dec" -> 1
                "inc" -> 2
                "eq" -> 3
                else -> 0
            }
            val value = parseNum(d, "value") ?: 0
            if (addr in 0..0x1FFF) DamageWatch(addr, mode, value) else null
        }
        Parsed(
            netplay = root.optBoolean("netplay", true),
            combos = ComboJson.presetsFromJson(root.optJSONArray("combos")) ?: emptyList(),
            damage = damage
        )
    } catch (_: Exception) {
        Parsed(true, emptyList(), null)
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
