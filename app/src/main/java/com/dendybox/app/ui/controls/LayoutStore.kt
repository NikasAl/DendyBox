package com.dendybox.app.ui.controls

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/**
 * Раскладка экранных кнопок: позиции (доли экрана) и размер (dp) для каждого контрола.
 * Хранится в SharedPreferences, изменения сразу применяются и сохраняются.
 */
class LayoutStore(private val prefs: SharedPreferences) {

    data class Pos(val x: Float, val y: Float, val size: Float)

    private val map = mutableStateMapOf<String, Pos>()

    init {
        prefs.getString("layout", null)?.let { s ->
            try {
                val o = JSONObject(s)
                o.keys().forEach { id ->
                    val p = o.getJSONObject(id)
                    map[id] = Pos(
                        x = p.getDouble("x").toFloat(),
                        y = p.getDouble("y").toFloat(),
                        size = p.getDouble("s").toFloat()
                    )
                }
            } catch (_: Exception) {
                // повреждённая раскладка — используем значения по умолчанию
            }
        }
    }

    fun pos(id: String, defX: Float, defY: Float, defSize: Float): Pos =
        map[id] ?: Pos(defX, defY, defSize)

    fun size(id: String, def: Float): Float = map[id]?.size ?: def

    fun setPos(id: String, p: Pos) {
        map[id] = p
        persist()
    }

    fun setSize(id: String, size: Float) {
        val p = map[id] ?: return
        map[id] = p.copy(size = size)
        persist()
    }

    fun reset() {
        map.clear()
        persist()
    }

    private fun persist() {
        val o = JSONObject()
        map.forEach { (id, p) ->
            o.put(
                id,
                JSONObject()
                    .put("x", p.x.toDouble())
                    .put("y", p.y.toDouble())
                    .put("s", p.size.toDouble())
            )
        }
        prefs.edit().putString("layout", o.toString()).apply()
    }
}
