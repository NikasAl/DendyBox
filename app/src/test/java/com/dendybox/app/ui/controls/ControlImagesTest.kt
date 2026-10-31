package com.dendybox.app.ui.controls

import com.dendybox.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Image-механика скинов — картинки из ресурсов приложения
 * (res/drawable-nodpi) вместо параметрической формы/цветов. Ключевые
 * инварианты:
 *  - «Классический» (дефолт) картинок НЕ имеет — его вид не изменился;
 *  - «Гаджет» — крестовина и ВСЕ физические кнопки — вырезки из
 *    фото-референсов корпуса (scripts/photo_src);
 *  - «Приставка» — фото-крестовина и одна круглая кнопка на B/A/турбо;
 *    пилюли SELECT/START/SAVE/LOAD и системные — параметрика (фото
 *    не задано);
 *  - картинка разрешена ТОЛЬКО физическим кнопкам ([ControlSkin.IMAGEABLE_IDS]);
 *    у комбо-кнопок ("K*") подписи пользовательские — всегда параметрический
 *    рендер;
 *  - логика нажатий и прозрачность от картинки НЕ зависят: hit-test читает
 *    только спеки (контракт геометрии ниже), альфа картинок движется тем же
 *    слайдером «Видимость» по тем же формулам, что и заливка.
 */
class ControlImagesTest {

    private fun skin(id: String) = ControlSkins.byId(id)

    // ------------------------------------------------------------------
    // Дефолт без картинок
    // ------------------------------------------------------------------

    @Test
    fun `классический скин без картинок — дефолт не изменился`() {
        val c = skin("classic")
        assertNull(c.imgDpad)
        assertTrue(c.imgButtons.isEmpty())
        assertNull(c.imgBlock)
        assertNull(c.buttonImage("A"))
        assertNull(c.buttonImage("K1"))
    }

    // ------------------------------------------------------------------
    // «Гаджет» и «Приставка» — скины с картинками
    // ------------------------------------------------------------------

    @Test
    fun `картинки есть только у гаджета и приставки`() {
        for (s in ControlSkins.ALL.filter { it.id != "gadget" && it.id != "console" }) {
            assertNull("${s.id}: крестовина", s.imgDpad)
            assertTrue("${s.id}: кнопки", s.imgButtons.isEmpty())
            assertNull("${s.id}: подложка", s.imgBlock)
        }
        assertNotNull(skin("gadget").imgDpad)
        assertTrue(skin("gadget").imgButtons.isNotEmpty())
        assertNotNull(skin("console").imgDpad)
        assertTrue(skin("console").imgButtons.isNotEmpty())
    }

    @Test
    fun `приставка — фото-крестовина и круглая кнопка на B A и турбо`() {
        val c = skin("console")
        assertEquals(R.drawable.skin_console_dpad, c.imgDpad)
        // на референсе все круглые кнопки одинаковые — одна вырезка на всех
        for (btn in listOf("A", "B", "TA", "TB")) {
            assertEquals("console: кнопка $btn", R.drawable.skin_console_btn, c.buttonImage(btn))
        }
        // пилюли SELECT/START/SAVE/LOAD и системные — параметрика в цвет фото-кнопок
        for (id in listOf("START", "SELECT", "SAVE", "LOAD", "PAUSE", "MUTE", "EDIT")) {
            assertNull("console: $id без картинки", c.buttonImage(id))
        }
        assertNull("console: без подложки", c.imgBlock)
    }

    @Test
    fun `гаджет — картинки крестовины и всех физических кнопок`() {
        val g = skin("gadget")
        assertEquals(R.drawable.skin_gadget_dpad, g.imgDpad)
        for (btn in ControlSkin.IMAGEABLE_IDS) {
            assertNotNull("gadget: кнопка $btn", g.buttonImage(btn))
        }
        assertNull("gadget: без подложки — тело рисует DeviceShellLayer", g.imgBlock)
    }

    @Test
    fun `гаджет — картинки закреплены за конкретными ресурсами`() {
        val g = skin("gadget")
        assertEquals(R.drawable.skin_gadget_btn_a, g.buttonImage("A"))
        assertEquals(R.drawable.skin_gadget_btn_b, g.buttonImage("B"))
        assertEquals(R.drawable.skin_gadget_btn_ta, g.buttonImage("TA"))
        assertEquals(R.drawable.skin_gadget_btn_tb, g.buttonImage("TB"))
        assertEquals(R.drawable.skin_gadget_pill, g.buttonImage("START"))
        assertEquals(R.drawable.skin_gadget_pill, g.buttonImage("SELECT"))
        // кнопки корпуса — та же пилюля, что SELECT/START
        assertEquals(R.drawable.skin_gadget_pill, g.buttonImage("SAVE"))
        assertEquals(R.drawable.skin_gadget_pill, g.buttonImage("LOAD"))
    }

    @Test
    fun `подложка imgBlock в текущем наборе не используется`() {
        // механизм остался (рендер подложки в ControlsOverlay), но ни один
        // скин набора подложку не задаёт — тело рисует DeviceShellLayer
        for (s in ControlSkins.ALL) assertNull("${s.id}: подложка", s.imgBlock)
    }

    // ------------------------------------------------------------------
    // Прозрачность картинок: тот же слайдер, те же формулы
    // ------------------------------------------------------------------

    @Test
    fun `альфа картинки кнопки следует слайдеру и бусту нажатия как заливка`() {
        for (op in listOf(0.2f, 0.55f, 0.9f)) {
            assertEquals(op, ControlSkin.buttonImageAlpha(op, pressed = false), 1e-6f)
            assertEquals(
                minOf(1f, op + 0.3f),
                ControlSkin.buttonImageAlpha(op, pressed = true),
                1e-6f
            )
        }
        // Нажатая картинка не темнее отпущенной
        assertTrue(
            ControlSkin.buttonImageAlpha(0.55f, pressed = true) >
                ControlSkin.buttonImageAlpha(0.55f, pressed = false)
        )
    }

    @Test
    fun `альфа картинки в пределах от нуля до единицы на всём ходе и за краями`() {
        for (op in listOf(-1f, 0f, 0.2f, 0.5f, 0.9f, 1f, 2f)) {
            assertTrue(
                "op=$op not pressed",
                ControlSkin.buttonImageAlpha(op, pressed = false) in 0f..1f
            )
            assertTrue(
                "op=$op pressed",
                ControlSkin.buttonImageAlpha(op, pressed = true) in 0f..1f
            )
        }
    }

    @Test
    fun `слайдер видимости управляет и картинкой крестовины`() {
        // Image-крестовина рисуется с альфой веса крестовины (dpadStroke):
        // один и тот же слайдер двигает и параметрику, и картинки
        assertEquals(0.55f, ControlAlphas.of(0.55f).dpadStroke, 1e-6f)
        assertEquals(0.2f, ControlAlphas.of(0.2f).dpadStroke, 1e-6f)
        assertEquals(0.9f, ControlAlphas.of(0.9f).dpadStroke, 1e-6f)
    }

    // ------------------------------------------------------------------
    // Зоны нажатий не зависят от скина: контракт геометрии
    // ------------------------------------------------------------------

    @Test
    fun `зоны нажатий не зависят от скина — геометрия спеков прежняя`() {
        // hit-test читает ТОЛЬКО спеки (размер/офсеты) и масштаб группы из
        // LayoutStore; скин участвует исключительно в отрисовке. Контракт:
        // константы спеков неизменны, у ControlSkin нет полей геометрии
        assertEquals(listOf(64f, 64f, 64f, 64f), SPECS_AB.map { it.size })
        assertEquals(listOf(-36f, 36f, -36f, 36f), SPECS_AB.map { it.dx })
        assertEquals(listOf(-72f, -72f, 0f, 0f), SPECS_AB.map { it.dy })
        assertEquals(listOf(64f, 64f), SPECS_AB2.map { it.size })
        assertEquals(listOf(-36f, 36f), SPECS_AB2.map { it.dx })
        assertEquals(listOf(46f, 46f), SPECS_META.map { it.size })
        assertEquals(listOf(-29f, 29f), SPECS_META.map { it.dx })

        val ab = extentsDp(LayoutStore.GroupId.AB, 0f)
        assertEquals(68f, ab.left, 1e-6f)
        assertEquals(104f, ab.top, 1e-6f)
        assertEquals(68f, ab.right, 1e-6f)
        assertEquals(32f, ab.bottom, 1e-6f)
    }
}
