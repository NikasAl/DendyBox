package com.dendybox.app.emulator

import kotlin.math.floor
import kotlin.math.min

/**
 * Прямоугольник в px поверхности — чистые числа без android.graphics,
 * чтобы letterbox-математика и геометрия корпуса были JVM-тестируемыми.
 */
data class FRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Letterbox-математика игрового кадра — ЧИСТАЯ функция (раньше была частью
 * [EmulatorEngine.frameRect], извлечена для тестов).
 *
 * Без резерва ([reservePx] = 0) поведение прежнее: целый масштаб
 * (floor от вписывания кадра [fw]×[fh] в область [vw]×[vh]; на крошечных
 * областях, где кадр и в 1× не помещается, — дробное вписывание), кадр
 * центрируется, остаток — чёрные поля letterbox.
 *
 * Резерв ([reservePx] > 0) — отступ кадра от краёв экрана под корпус скина
 * «Гаджет» (1px — волосяная линия безеля): кадр не касается кромок, а
 * картинка остаётся максимально большой.
 *  - если целый масштаб уже оставляет вокруг кадра ≥ 2·reserve с каждой
 *    стороны — он сохраняется (чёткая картинка без фильтрации не портится;
 *    на типичных телефонах резерв 1px ничего не меняет);
 *  - иначе (поля letterbox меньше резерва: высота поверхности, кратная
 *    240, — кадр касался бы кромок) масштаб уменьшается ДО дробного ровно
 *    настолько, чтобы после центрирования вокруг кадра осталось 2·reserve
 *    по ограничивающей оси — при резерве 1px кадр сжимается всего на ~2px
 *    по высоте (картинка при этом слегка фильтруется — isFilterBitmap в
 *    drawFrame включается сам по нецелому масштабу);
 *  - вырожденные области (резерв съедает всё) деградируют к вписыванию без
 *    резерва — NaN/отрицательных размеров не бывает.
 */
object FrameMath {

    fun letterbox(vw: Float, vh: Float, fw: Float, fh: Float, reservePx: Float = 0f): FRect {
        val w = vw.coerceAtLeast(1f)
        val h = vh.coerceAtLeast(1f)
        val s0 = min(w / fw, h / fh)

        if (reservePx <= 0f) {
            val scale = if (s0 >= 1f) floor(s0) else s0
            return centered(w, h, fw * scale, fh * scale)
        }

        val sInt = if (s0 >= 1f) floor(s0) else 0f
        val fits = sInt >= 1f &&
            (w - fw * sInt) >= 2f * reservePx &&
            (h - fh * sInt) >= 2f * reservePx
        val scale = if (fits) {
            sInt
        } else {
            val aw = (w - 2f * reservePx).coerceAtLeast(0f)
            val ah = (h - 2f * reservePx).coerceAtLeast(0f)
            val sf = min(aw / fw, ah / fh)
            if (sf > 0f) sf else s0
        }
        return centered(w, h, fw * scale, fh * scale)
    }

    private fun centered(vw: Float, vh: Float, w: Float, h: Float): FRect {
        val l = (vw - w) / 2f
        val t = (vh - h) / 2f
        return FRect(l, t, l + w, t + h)
    }
}
