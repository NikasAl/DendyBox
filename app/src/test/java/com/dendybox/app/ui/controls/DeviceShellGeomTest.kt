package com.dendybox.app.ui.controls

import com.dendybox.app.emulator.FRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Геометрия корпуса ([shellRects]) — чистая функция «кадр -> тело + окно».
 * Ключевой контракт задачи «корпус узкий по высоте»: корпус ВСЕГДА накрывает
 * игровой кадр — окно содержит кадр (в границах поверхности), тело содержит
 * окно; на узких полях letterbox тело расширяется к краю поверхности
 * (раньше окно зажималось в тело, и кадр/безель резались краем — фрейм
 * выступал за верхнюю/нижнюю кромку корпуса). Резерв движка
 * ([DeviceSkins.reserveDp]) оставляет вокруг кадра место под полный запас
 * (отступ тела + зазор + безель) — с ним корпус выглядит одинаково
 * «запроектированно» на любых экранах; у FHD+ (поля >= резерва) резерв
 * ничего не меняет, у HD+ (поля 0) кадр слегка сжимается движком.
 *
 * Пиксельные значения соответствуют плотностям: 3x (m=18, gap=9, bezel=24)
 * и 2x (m=12, gap=6, bezel=16) для 17dp суммарного резерва.
 */
class DeviceShellGeomTest {

    // FHD+ ландшафт @3x: кадр 1024x960, поля по вертикали 60px
    private val fhdFrame = FRect(688f, 60f, 1712f, 1020f)

    // HD+ ландшафт @2x: кадр после резерва движка (34px сверху/снизу),
    // как его отдаёт FrameMath.letterbox(1600, 720, 256, 240, 34)
    private val hdPlusReserved = run {
        val r = com.dendybox.app.emulator.FrameMath.letterbox(1600f, 720f, 256f, 240f, 34f)
        FRect(r.left, r.top, r.right, r.bottom)
    }

    // ------------------------------------------------------------------
    // Достаточные поля: геометрия прежняя (регрессия)
    // ------------------------------------------------------------------

    @Test
    fun `на достаточных полях окно и тело прежние`() {
        val (body, win) = shellRects(2400f, 1080f, fhdFrame, m3, gap3, bezel3)
        // окно = кадр ± (зазор+безель), тело = окно ± отступ — без зажимов
        assertEquals(FRect(655f, 27f, 1745f, 1053f), win)
        assertEquals(FRect(637f, 9f, 1763f, 1071f), body)
    }

    @Test
    fun `кольцо безеля вокруг кадра равномерное при достаточных полях`() {
        val (_, win) = shellRects(2400f, 1080f, fhdFrame, m3, gap3, bezel3)
        // зазор 9px остаётся прозрачным (чёрный фон), безель 24px
        assertEquals(27f, win.top, 1e-4f)
        assertEquals(60f - 9f, win.top + bezel3, 1e-4f)
        assertEquals(33f, fhdFrame.top - win.top, 1e-4f)
    }

    // ------------------------------------------------------------------
    // Дефект «корпус узкий по высоте»: нулевые поля
    // ------------------------------------------------------------------

    @Test
    fun `при нулевых полях тело доходит до края поверхности и накрывает кадр`() {
        // HD+ БЕЗ резерва: кадр 768x720 касается верхнего/нижнего края —
        // раньше окно зажималось в тело (фрейм выступал), теперь тело
        // расширяется к краю поверхности
        val frame = FRect(416f, 0f, 1184f, 720f)
        val (body, win) = shellRects(1600f, 720f, frame, m2, gap2, bezel2)
        assertEquals(FRect(394f, 0f, 1206f, 720f), win)
        assertEquals(FRect(382f, 0f, 1218f, 720f), body)
        // кадр внутри окна (касается, но не выступает)
        assertTrue(win.top <= frame.top && win.bottom >= frame.bottom)
        assertTrue(win.left <= frame.left && win.right >= frame.right)
        // тело внутри окна
        assertTrue(body.top <= win.top && body.bottom >= win.bottom)
        assertTrue(body.left <= win.left && body.right >= win.right)
    }

    @Test
    fun `с резервом движка корпус вмещает кадр с полным запасом`() {
        // HD+ @2x: движок сжал кадр до 652px высоты, вокруг по 34px.
        // Сверху вниз: край экрана -> 12px тела -> 16px безеля -> 6px зазор -> кадр
        val (body, win) = shellRects(1600f, 720f, hdPlusReserved, m2, gap2, bezel2)
        assertEquals(34f, hdPlusReserved.top, 1e-2f)
        assertEquals(FRect(hdPlusReserved.left - 22f, 12f, hdPlusReserved.right + 22f, 708f), win)
        assertEquals(FRect(win.left - 12f, 0f, win.right + 12f, 720f), body)
        // полный запас: от края экрана до кадра ровно резерв 34px
        assertEquals(34f, hdPlusReserved.top - body.top, 1e-2f)
        assertEquals(720f - hdPlusReserved.bottom, body.bottom - hdPlusReserved.bottom, 1e-2f)
    }

    // ------------------------------------------------------------------
    // Инварианты вложенности на переборе устройств
    // ------------------------------------------------------------------

    @Test
    fun `тело содержит окно окно содержит кадр на всём переборе`() {
        val screens = listOf(
            2400f to 1080f, 1600f to 720f, 1920f to 1200f, 2560f to 1440f,
            1280f to 720f, 1024f to 768f, 300f to 200f
        )
        val frames = listOf(
            FRect(688f, 60f, 1712f, 1020f),   // FHD+ целый 4x
            FRect(416f, 0f, 1184f, 720f),     // HD+ касание краёв
            FRect(452f, 34f, 1148f, 686f),    // HD+ с резервом
            FRect(352f, 25f, 1578f, 1175f),   // планшет с резервом
            FRect(22f, 3f, 278f, 197f)        // крошечный экран
        )
        for ((w, h) in screens) {
            for (f in frames) {
                val ff = FRect(
                    f.left.coerceIn(0f, w), f.top.coerceIn(0f, h),
                    f.right.coerceIn(0f, w), f.bottom.coerceIn(0f, h)
                )
                val (body, win) = shellRects(w, h, ff, m3, gap3, bezel3)
                val tag = "$w x $h, frame=$ff"
                // окно содержит кадр, тело содержит окно
                assertTrue("$tag: окно уже кадра по горизонтали", win.left <= ff.left + 1e-3f && win.right >= ff.right - 1e-3f)
                assertTrue("$tag: окно уже кадра по вертикали", win.top <= ff.top + 1e-3f && win.bottom >= ff.bottom - 1e-3f)
                assertTrue("$tag: тело уже окна по горизонтали", body.left <= win.left + 1e-3f && body.right >= win.right - 1e-3f)
                assertTrue("$tag: тело уже окна по вертикали", body.top <= win.top + 1e-3f && body.bottom >= win.bottom - 1e-3f)
                // всё в границах поверхности, без вырожденности
                assertTrue("$tag: тело вне поверхности", body.left >= -1e-3f && body.right <= w + 1e-3f && body.top >= -1e-3f && body.bottom <= h + 1e-3f)
                assertTrue("$tag: вырожденное окно", win.width >= 0f && win.height >= 0f)
            }
        }
    }

    @Test
    fun `кадр больше поверхности не ломает геометрию`() {
        // защита от мусорного входа: окно зажимается в поверхность,
        // тело — в окно, без NaN/отрицательных
        val (body, win) = shellRects(200f, 100f, FRect(-50f, -50f, 300f, 200f), m3, gap3, bezel3)
        assertEquals(FRect(0f, 0f, 200f, 100f), win)
        assertEquals(FRect(0f, 0f, 200f, 100f), body)
    }

    companion object {
        // px при density 3x: 6/9/24
        private const val m3 = 18f
        private const val gap3 = 9f
        private const val bezel3 = 24f
        // px при density 2x: 12/6/16
        private const val m2 = 12f
        private const val gap2 = 6f
        private const val bezel2 = 16f
    }
}
