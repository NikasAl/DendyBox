package com.dendybox.app.ui.controls

import com.dendybox.app.emulator.FRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Геометрия корпуса ([shellRects]) — чистая функция «кадр -> тело + окно».
 * Контракт задачи «корпус заливает весь экран»: тело — ВСЯ поверхность
 * (фон-гаджет под контролами, виден на полях letterbox вместо чёрных полос),
 * окно — кадр, раздвинутый на 1px безеля и зажатый в поверхность; пиксели
 * игры не перекрываются (окно вырезано по кадру). Резерв движка
 * ([DeviceSkins.FRAME_PAD_PX] = 1px) гарантирует, что кадр не касается
 * кромок и кольцо безеля всегда читается: у экранов с полями >= 2px картинка
 * не меняется вовсе (целый масштаб), у кратных-240 высот кадр сжимается
 * движком всего на ~2px.
 */
class DeviceShellGeomTest {

    // FHD+ ландшафт @3x: кадр 1024x960, поля по вертикали 60px
    private val fhdFrame = FRect(688f, 60f, 1712f, 1020f)

    // ------------------------------------------------------------------
    // Достаточные поля: корпус — фон всего экрана, окно = кадр + 1px
    // ------------------------------------------------------------------

    @Test
    fun `тело — вся поверхность окно — кадр плюс 1px`() {
        val (body, win) = shellRects(2400f, 1080f, fhdFrame, PAD)
        // корпус заливает весь экран
        assertEquals(FRect(0f, 0f, 2400f, 1080f), body)
        // окно = кадр ± 1px безеля — без зажимов (поля достаточные)
        assertEquals(FRect(687f, 59f, 1713f, 1021f), win)
    }

    @Test
    fun `кольцо безеля вокруг кадра ровно 1px со всех сторон`() {
        val (_, win) = shellRects(2400f, 1080f, fhdFrame, PAD)
        assertEquals(1f, fhdFrame.top - win.top, 1e-4f)
        assertEquals(1f, fhdFrame.left - win.left, 1e-4f)
        assertEquals(1f, win.right - fhdFrame.right, 1e-4f)
        assertEquals(1f, win.bottom - fhdFrame.bottom, 1e-4f)
        // кольцо лежит в окне и не заходит на кадр
        assertTrue(win.top <= fhdFrame.top && win.bottom >= fhdFrame.bottom)
        assertTrue(win.left <= fhdFrame.left && win.right >= fhdFrame.right)
    }

    // ------------------------------------------------------------------
    // Нулевые поля: окно зажимается краем поверхности (движок с резервом
    // 1px таких кадров не отдаёт — это защита от «сырого» ввода)
    // ------------------------------------------------------------------

    @Test
    fun `при нулевых полях окно зажимается краем поверхности`() {
        // кадр 768x720 касается верхнего/нижнего края: окно раздвигается
        // на 1px в стороны, а по вертикали упирается в край
        val frame = FRect(416f, 0f, 1184f, 720f)
        val (body, win) = shellRects(1600f, 720f, frame, PAD)
        assertEquals(FRect(0f, 0f, 1600f, 720f), body)
        assertEquals(FRect(415f, 0f, 1185f, 720f), win)
        // кадр внутри окна (касается, но не выступает)
        assertTrue(win.top <= frame.top && win.bottom >= frame.bottom)
        assertTrue(win.left <= frame.left && win.right >= frame.right)
    }

    @Test
    fun `с резервом движка безель занимает весь отступ`() {
        // HD+ @2x: движок с резервом 1px даёт кадр 718px высоты, по 1px
        // сверху и снизу — окно упёрлось в края, кольцо безеля заняло отступ
        val r = com.dendybox.app.emulator.FrameMath.letterbox(1600f, 720f, 256f, 240f, PAD)
        val frame = FRect(r.left, r.top, r.right, r.bottom)
        assertEquals(1f, frame.top, 1e-2f)
        val (body, win) = shellRects(1600f, 720f, frame, PAD)
        assertEquals(FRect(0f, 0f, 1600f, 720f), body)
        assertEquals(0f, win.top, 1e-4f)
        assertEquals(720f, win.bottom, 1e-4f)
        assertEquals(1f, frame.top - win.top, 1e-4f)
        assertEquals(1f, win.bottom - frame.bottom, 1e-4f)
    }

    // ------------------------------------------------------------------
    // Инварианты вложенности на переборе устройств
    // ------------------------------------------------------------------

    @Test
    fun `тело — весь экран окно содержит кадр на всём переборе`() {
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
                val (body, win) = shellRects(w, h, ff, PAD)
                val tag = "$w x $h, frame=$ff"
                // тело — вся поверхность
                assertEquals("$tag: тело не весь экран", FRect(0f, 0f, w, h), body)
                // окно содержит кадр
                assertTrue("$tag: окно уже кадра по горизонтали", win.left <= ff.left + 1e-3f && win.right >= ff.right - 1e-3f)
                assertTrue("$tag: окно уже кадра по вертикали", win.top <= ff.top + 1e-3f && win.bottom >= ff.bottom - 1e-3f)
                // окно в границах поверхности, без вырожденности
                assertTrue("$tag: окно вне поверхности", win.left >= -1e-3f && win.right <= w + 1e-3f && win.top >= -1e-3f && win.bottom <= h + 1e-3f)
                assertTrue("$tag: вырожденное окно", win.width >= 0f && win.height >= 0f)
            }
        }
    }

    @Test
    fun `кадр больше поверхности не ломает геометрию`() {
        // защита от мусорного входа: окно зажимается в поверхность,
        // тело — вся поверхность, без NaN/отрицательных
        val (body, win) = shellRects(200f, 100f, FRect(-50f, -50f, 300f, 200f), PAD)
        assertEquals(FRect(0f, 0f, 200f, 100f), win)
        assertEquals(FRect(0f, 0f, 200f, 100f), body)
    }

    companion object {
        // толщина безеля = отступ кадра от кромок (DeviceSkins.FRAME_PAD_PX)
        private const val PAD = 1f
    }
}
