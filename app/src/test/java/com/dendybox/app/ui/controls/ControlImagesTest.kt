package com.dendybox.app.ui.controls

import com.dendybox.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Image-скины — картинки из ресурсов приложения (res/drawable-nodpi) вместо
 * параметрической формы/цветов. Ключевые инварианты:
 *  - «Классический» (дефолт) и остальные параметрические скины картинок НЕ
 *    имеют — их вид не изменился;
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
    // Дефолт и параметрические скины без картинок
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

    @Test
    fun `параметрические скины тоже без картинок`() {
        for (id in listOf("classic", "neon", "pixel", "dark")) {
            val s = skin(id)
            assertNull("$id: крестовина", s.imgDpad)
            assertTrue("$id: кнопки", s.imgButtons.isEmpty())
            assertNull("$id: подложка", s.imgBlock)
        }
    }

    // ------------------------------------------------------------------
    // Состав image-скинов
    // ------------------------------------------------------------------

    @Test
    fun `три image-скина с картинками на крестовину и все физические кнопки`() {
        for (id in listOf("dendy", "carbon", "arcade")) {
            val s = skin(id)
            assertNotNull("$id: крестовина", s.imgDpad)
            for (btn in ControlSkin.IMAGEABLE_IDS) {
                assertNotNull("$id: кнопка $btn", s.buttonImage(btn))
            }
        }
    }

    @Test
    fun `картинки закреплены за конкретными ресурсами`() {
        assertEquals(R.drawable.skin_dendy_dpad, skin("dendy").imgDpad)
        assertEquals(R.drawable.skin_dendy_btn, skin("dendy").buttonImage("A"))
        assertEquals(R.drawable.skin_dendy_btn, skin("dendy").buttonImage("TB"))
        assertEquals(R.drawable.skin_dendy_pill, skin("dendy").buttonImage("SELECT"))
        assertEquals(R.drawable.skin_carbon_dpad, skin("carbon").imgDpad)
        assertEquals(R.drawable.skin_carbon_btn, skin("carbon").buttonImage("B"))
        assertEquals(R.drawable.skin_arcade_dpad, skin("arcade").imgDpad)
        assertEquals(R.drawable.skin_arcade_btn_red, skin("arcade").buttonImage("A"))
        assertEquals(R.drawable.skin_arcade_btn_red, skin("arcade").buttonImage("TA"))
        assertEquals(R.drawable.skin_arcade_btn_yellow, skin("arcade").buttonImage("B"))
        assertEquals(R.drawable.skin_arcade_pill, skin("arcade").buttonImage("START"))
    }

    @Test
    fun `комбо и неизвестные кнопки картинок не получают`() {
        for (s in ControlSkins.ALL) {
            assertNull("${s.id}: комбо", s.buttonImage("K1"))
            assertNull("${s.id}: пустой id", s.buttonImage(""))
            assertNull("${s.id}: неизвестный id", s.buttonImage("XX"))
        }
    }

    @Test
    fun `подложка целым блоком только у карбона`() {
        assertNotNull(skin("carbon").imgBlock)
        for (s in ControlSkins.ALL.filter { it.id != "carbon" }) {
            assertNull("${s.id}: подложка", s.imgBlock)
        }
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
    fun `альфа картинки в пределах от 0 до 1 на всём ходе и за краями`() {
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
