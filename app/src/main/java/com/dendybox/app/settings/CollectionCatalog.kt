package com.dendybox.app.settings

import android.content.Context
import org.json.JSONObject

/** Одна игра сборника: заголовок для меню и путь рома внутри assets. */
data class CollectionEntry(val index: Int, val title: String, val assetPath: String)

/** Сборник «X игр в 1» либо null (обычная игра — один ROM assets/rom.nes). */
data class CollectionData(
    val title: String?,
    val background: String?, // assets-путь постера меню («games/background.webp»), null — нет
    val menuAlign: String,   // выравнивание блоков меню: left | center | right
    val games: List<CollectionEntry>
)

/**
 * Сборник игр («X in 1»): в APK лежат assets/games/0.nes, 1.nes, …
 * и манифест assets/games/games.json вида
 *
 * ```json
 * { "title": "Контра: Сборник",
 *   "background": "background.webp",
 *   "menuAlign": "left",
 *   "games": [ {"file": "0.nes", "title": "Контра"},
 *              {"file": "1.nes", "title": "Супер Контра"} ] }
 * ```
 *
 * Манифест, ромы и фон-постер (metadata/<flavor>/background.*, автоматически
 * пережимается в WebP) копирует сборкой задача prepare<Flavor>Rom, когда у
 * flavor'а в roms/games.json задан массив "games" (см. app/build.gradle.kts).
 *
 * Особенности сборника в приложении:
 *  * до выбора игры движок не запускается — сначала меню CollectionMenu;
 *  * сейвы/читы привязаны к SHA-1 конкретного рома, так что у игр сборника
 *    они раздельные без дополнительных усилий;
 *  * пункт «Выход из игры» в меню паузы возвращает в меню сборника
 *    (автосейв уже записан при входе в меню паузы).
 */
object CollectionCatalog {

    fun load(ctx: Context): CollectionData? {
        val text = try {
            ctx.assets.open("games/games.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return null // файла нет — обычная одиночная игра
        }
        return try {
            val root = JSONObject(text)
            val arr = root.getJSONArray("games")
            val games = (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    val file = o.getString("file")
                    // защита от выхода за пределы папки сборника
                    if (file.contains('/') || file.contains('\\') || file.contains("..")) {
                        return@mapNotNull null
                    }
                    CollectionEntry(
                        index = i,
                        title = o.optString("title").takeUnless { it.isBlank() } ?: "Игра ${i + 1}",
                        assetPath = "games/$file"
                    )
                } catch (_: Exception) {
                    null // битый элемент пропускаем, остальные работают
                }
            }
            if (games.isEmpty()) null else {
                // Фон-постер меню: имя файла из манифеста, та же защита от
                // выхода за пределы папки сборника, что и у ромов
                val background = root.optString("background").takeUnless { it.isBlank() }
                    ?.takeIf { bg -> !bg.contains('/') && !bg.contains('\\') && !bg.contains("..") }
                    ?.let { "games/$it" }
                // Выравнивание блоков меню под постер (задаётся в games.json,
                // в манифест попадает только не-center — см. build.gradle.kts)
                val align = root.optString("menuAlign").takeUnless { it.isBlank() }
                    ?.takeIf { it == "left" || it == "right" || it == "center" } ?: "center"
                CollectionData(
                    title = root.optString("title").takeUnless { it.isBlank() },
                    background = background,
                    menuAlign = align,
                    games = games
                )
            }
        } catch (_: Exception) {
            null // битый манифест — ведём себя как обычная игра (rom.nes всё равно нет)
        }
    }
}
