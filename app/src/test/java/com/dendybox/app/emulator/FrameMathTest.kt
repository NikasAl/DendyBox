package com.dendybox.app.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Letterbox-математика игрового кадра ([FrameMath.letterbox]) — чистая
 * функция. Ключевые контракты:
 *  - без резерва — ПРЕЖНЯЯ математика один в один (целый масштаб, центри-
 *    рование, дробное вписывание на крошечных областях): дефолтный вид
 *    (классический скин, без корпуса) не меняется вообще;
 *  - резерв под корпус («Гаджет») сохраняет целый масштаб, когда поля
 *    letterbox и так оставляют ≥ 2·reserve вокруг кадра (FHD+ телефоны —
 *    картинка остаётся чёткой, ничего не сжимается);
 *  - при малых полях (HD+ экраны с высотой, кратной 240: раньше фрейм
 *    выступал за кромку корпуса) кадр сжимается ровно настолько, чтобы
 *    вокруг осталось 2·reserve — корпус вмещает фрейм с запроектированным
 *    запасом (отступ тела + зазор + безель);
 *  - вырожденные входы (резерв съедает область) деградируют к вписыванию
 *    без резерва — NaN/отрицательных размеров не бывает; кадр всегда
 *    в пределах поверхности и отцентрирован.
 */
class FrameMathTest {

    private val fw = 256f
    private val fh = 240f

    // ------------------------------------------------------------------
    // Без резерва — прежняя математика (регрессия дефолта)
    // ------------------------------------------------------------------

    @Test
    fun `без резерва целый масштаб и центрирование как раньше`() {
        // FHD+ ландшафт: floor(min(2400/256, 1080/240)) = 4 -> 1024x960
        assertEquals(FRect(688f, 60f, 1712f, 1020f), FrameMath.letterbox(2400f, 1080f, fw, fh))
        // HD+ ландшафт: floor(min(1600/256, 720/240)) = 3 -> 768x720 (полей по вертикали нет)
        assertEquals(FRect(416f, 0f, 1184f, 720f), FrameMath.letterbox(1600f, 720f, fw, fh))
    }

    @Test
    fun `без резерва крошечная область — дробное вписывание`() {
        // min(300/256, 200/240) < 1 -> дробный масштаб 0.8333
        val r = FrameMath.letterbox(300f, 200f, fw, fh)
        assertEquals(200f, r.height, 1e-3f)
        assertEquals(256f * (200f / 240f), r.width, 1e-3f)
        assertEquals(0f, r.top, 1e-4f)
        assertEquals((300f - r.width) / 2f, r.left, 1e-4f)
    }

    // ------------------------------------------------------------------
    // Резерв под корпус
    // ------------------------------------------------------------------

    @Test
    fun `резерв не меняет целый масштаб когда поля достаточны`() {
        // FHD+ @3x: reserve 17dp = 51px, поля по вертикали 60px >= 51 ->
        // прежний целый масштаб 4x, картинка остаётся чёткой
        val plain = FrameMath.letterbox(2400f, 1080f, fw, fh)
        assertEquals(plain, FrameMath.letterbox(2400f, 1080f, fw, fh, 51f))
        // граница: поля ровно равны резерву — целый масштаб сохраняется
        assertEquals(plain, FrameMath.letterbox(2400f, 1080f, fw, fh, 60f))
    }

    @Test
    fun `резерв сжимает кадр при нулевых полях letterbox`() {
        // HD+ @2x: reserve 17dp = 34px; целый масштаб 3x даёт поля 0 < 34 ->
        // дробный: min((1600-68)/256, (720-68)/240) = 652/240
        val r = FrameMath.letterbox(1600f, 720f, fw, fh, 34f)
        assertEquals(652f, r.height, 1e-3f)
        assertEquals(34f, r.top, 1e-3f)
        assertEquals(686f, r.bottom, 1e-3f)
        assertEquals(256f * (652f / 240f), r.width, 1e-2f)
        // горизонтальные поля большие — кадр центрирован
        assertEquals((1600f - r.width) / 2f, r.left, 1e-3f)
    }

    @Test
    fun `резерв сжимает кадр на планшете с кратной высотой`() {
        // 1200 = 240*5: целый масштаб 5x касался краёв; с резервом 25.5px
        // высота ровно 1200-51 = 1149
        val r = FrameMath.letterbox(1920f, 1200f, fw, fh, 25.5f)
        assertEquals(1149f, r.height, 1e-3f)
        assertEquals(25.5f, r.top, 1e-3f)
        assertEquals(1174.5f, r.bottom, 1e-3f)
    }

    @Test
    fun `резерв чуть больше полей переключает на дробный масштаб`() {
        // поля 60px, резерв 60.5px — уже дробный: высота 1080-121 = 959
        val r = FrameMath.letterbox(2400f, 1080f, fw, fh, 60.5f)
        assertEquals(959f, r.height, 1e-3f)
        assertEquals(60.5f, r.top, 1e-3f)
        assertTrue(r.width < 1024f) // чуть меньше целого 4x
    }

    // ------------------------------------------------------------------
    // Тотальность: кадр всегда в пределах и центрирован
    // ------------------------------------------------------------------

    @Test
    fun `кадр в пределах поверхности и отцентрирован на всём переборе`() {
        val screens = listOf(
            2400f to 1080f, 2340f to 1080f, 2160f to 1080f, 1600f to 720f,
            1920f to 1200f, 2560f to 1440f, 1280f to 720f, 300f to 200f,
            1024f to 768f, 2520f to 1080f
        )
        for ((w, h) in screens) {
            for (reserve in listOf(0f, 17f, 34f, 51f, 80f)) {
                val r = FrameMath.letterbox(w, h, fw, fh, reserve)
                assertTrue("$w x $h, r=$reserve: NaN", !r.width.isNaN() && !r.height.isNaN())
                assertTrue("$w x $h, r=$reserve: пустой кадр", r.width > 0f && r.height > 0f)
                assertTrue(
                    "$w x $h, r=$reserve: вышел влево/вправо",
                    r.left >= -1e-3f && r.right <= w + 1e-3f
                )
                assertTrue(
                    "$w x $h, r=$reserve: вышел вверх/вниз",
                    r.top >= -1e-3f && r.bottom <= h + 1e-3f
                )
                assertEquals(
                    "$w x $h, r=$reserve: не центрирован по горизонтали",
                    w - r.right, r.left, 1e-3f
                )
                assertEquals(
                    "$w x $h, r=$reserve: не центрирован по вертикали",
                    h - r.bottom, r.top, 1e-3f
                )
            }
        }
    }

    @Test
    fun `резерв больше половины экрана — деградация к вписыванию без резерва`() {
        // 100px высоты, резерв 80px: доступно -60px -> фолбэк на s0, без NaN
        val r = FrameMath.letterbox(100f, 100f, fw, fh, 80f)
        assertEquals(100f, r.width, 1e-3f)
        assertEquals(100f * (240f / 256f), r.height, 1e-3f)
    }
}
