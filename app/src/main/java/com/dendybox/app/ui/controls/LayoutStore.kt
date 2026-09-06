package com.dendybox.app.ui.controls

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Раскладка экранных кнопок — ГРУППОВАЯ: весь блок (A, B, турбо, Start/Select)
 * перемещается и масштабируется как единое целое.
 *
 * [Group.x]/[Group.y] — якорь группы (центр кластера) в долях экрана,
 * [Group.scale] — масштаб всех кнопок (1.0 = базовые размеры из SPECS).
 * Сами кнопки позиционируются относительно якоря офсетами в dp (CtrlSpec.dx/dy),
 * умноженными на масштаб.
 *
 * Хранится в SharedPreferences, изменения применяются сразу.
 */
class LayoutStore(private val prefs: SharedPreferences) {

    data class Group(val x: Float, val y: Float, val scale: Float)

    var group: Group by mutableStateOf(Group(DEF_X, DEF_Y, DEF_SCALE))
        private set

    init {
        val g = prefs.getString("group", null)
        if (g != null) {
            try {
                val o = org.json.JSONObject(g)
                group = Group(
                    x = o.getDouble("x").toFloat(),
                    y = o.getDouble("y").toFloat(),
                    scale = o.getDouble("s").toFloat()
                )
            } catch (_: Exception) {
                // повреждённые данные — используем значения по умолчанию
            }
        }
    }

    /** Обновить группу; координаты зажимаются в допустимые пределы. */
    fun updateGroup(g: Group) {
        val s = g.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        group = Group(
            x = g.x.coerceIn(0.02f, 0.98f),
            y = g.y.coerceIn(0.02f, 0.98f),
            scale = s
        )
        persist()
    }

    fun reset() {
        group = Group(DEF_X, DEF_Y, DEF_SCALE)
        persist()
    }

    private fun persist() {
        val o = org.json.JSONObject()
            .put("x", group.x.toDouble())
            .put("y", group.y.toDouble())
            .put("s", group.scale.toDouble())
        prefs.edit().putString("group", o.toString()).apply()
    }

    companion object {
        // Дефолт: правый нижний угол, под игровой картинкой (в портретной ориентации)
        const val DEF_X = 0.78f
        const val DEF_Y = 0.80f
        const val DEF_SCALE = 1.0f
        const val MIN_SCALE = 0.55f
        const val MAX_SCALE = 1.8f
    }
}
