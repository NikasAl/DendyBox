package com.dendybox.app.ui.controls

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вид кнопки ([buttonLook]/[ButtonLook]) — чистая функция «скин + слайдер
 * "Видимость" + нажатие». Главный контракт (задача «стилизовать комбо-кнопки
 * в гармонии с остальным скином»):
 *  - у комбо-кнопок ("K*") вид следует за активным ControlSkin ТОЧНО как у
 *    обычных кнопок: форма [ControlSkin.shapeMain], заливка [ControlSkin.fill],
 *    рамка [ControlSkin.comboBorder], подпись [ControlSkin.label]; картинок
 *    у комбо нет, каптионы не включаются никогда (подпись всегда внутри);
 *  - слайдер «Видимость» двигает комбо-кнопки теми же формулами, что все:
 *    заливка = opacity, рамка/подпись = веса ControlAlphas, нажатие
 *    подсвечивает бустом +0.3 к альфе заливки (с капом 1);
 *  - обычные кнопки не задеты: A/B — рамка skin.border 2dp, турбо —
 *    turboBorder 1dp с приглушением, системные — пилюли shapeMeta.
 */
class ButtonLookTest {

    private fun look(
        skinId: String,
        specId: String,
        opacity: Float = 0.55f,
        pressed: Boolean = false,
        label: String = specId
    ) = buttonLook(ControlSkins.byId(skinId), specId, label, opacity, pressed)

    private fun alphas(opacity: Float) = ControlAlphas.of(opacity)

    // ------------------------------------------------------------------
    // Комбо-кнопки следуют скину
    // ------------------------------------------------------------------

    @Test
    fun `комбо-кнопка у каждого скина красится его формой и цветами`() {
        for (skin in ControlSkins.ALL) {
            val l = look(skin.id, "Ku1")
            assertEquals("${skin.id}: форма = shapeMain", skin.shapeMain, l.shape)
            assertEquals(
                "${skin.id}: заливка = fill с альфой слайдера",
                skin.fill.copy(alpha = 0.55f),
                l.fill
            )
            assertEquals(
                "${skin.id}: рамка = comboBorder с весом borderMain",
                skin.comboBorder.copy(alpha = alphas(0.55f).borderMain),
                l.borderColor
            )
            assertEquals("${skin.id}: рамка 1.5dp", 1.5f, l.borderWidthDp, 1e-6f)
            assertEquals(
                "${skin.id}: подпись = label с весом label",
                skin.label.copy(alpha = alphas(0.55f).label),
                l.labelColor
            )
            assertNull("${skin.id}: комбо без картинки", l.imageRes)
            // подпись комбо ВСЕГДА внутри — каптионный режим не включается
            assertFalse("${skin.id}: комбо без каптиона", l.captionMode)
            assertEquals("${skin.id}: пустой каптион", "", l.captionText)
        }
    }

    @Test
    fun `смена скина меняет вид комбо-кнопки`() {
        val neon = look("neon", "Kx")
        val pixel = look("pixel", "Kx")
        assertTrue(neon.shape != pixel.shape || neon.fill != pixel.fill)
        assertTrue(neon.borderColor != pixel.borderColor)
        // у пикселя угловатая форма
        assertEquals(SkinShape.SQUARE, pixel.shape)
        assertEquals(SkinShape.CIRCLE, neon.shape)
    }

    // ------------------------------------------------------------------
    // Слайдер «Видимость» влияет на комбо-кнопки
    // ------------------------------------------------------------------

    @Test
    fun `слайдер видимости двигает заливку рамку и подпись комбо`() {
        // альфа Color хранится 8-битной (1/255 ≈ 0.004) — допуск на округление
        val d = 1f / 255f + 1e-6f
        for (op in listOf(0.2f, 0.55f, 0.9f)) {
            val l = look("classic", "Ku1", opacity = op)
            assertEquals("заливка = $op", op, l.fill.alpha, d)
            assertEquals(
                "рамка с весом borderMain при $op",
                ControlSkins.byId("classic").comboBorder.copy(alpha = alphas(op).borderMain),
                l.borderColor
            )
            assertEquals(
                "подпись с весом label при $op",
                ControlSkins.byId("classic").label.copy(alpha = alphas(op).label),
                l.labelColor
            )
        }
        // прозрачнее при меньшем слайдере
        assertTrue(
            look("classic", "Ku1", opacity = 0.2f).fill.alpha <
                look("classic", "Ku1", opacity = 0.9f).fill.alpha
        )
    }

    @Test
    fun `нажатие подсвечивает комбо тем же бустом что обычные кнопки`() {
        val d = 1f / 255f + 1e-6f
        for (op in listOf(0.2f, 0.55f, 0.9f)) {
            assertEquals(
                minOf(1f, op + 0.3f),
                look("classic", "Ku1", opacity = op, pressed = true).fill.alpha,
                d
            )
        }
        // нажатая не темнее отпущенной
        assertTrue(
            look("classic", "Ku1", pressed = true).fill.alpha >
                look("classic", "Ku1", pressed = false).fill.alpha
        )
    }

    // ------------------------------------------------------------------
    // Обычные кнопки не задеты (регрессия)
    // ------------------------------------------------------------------

    @Test
    fun `обычная кнопка — рамка skin border 2dp и форма shapeMain`() {
        val l = look("classic", "A")
        assertEquals(SkinShape.CIRCLE, l.shape)
        assertEquals(Color.White.copy(alpha = 0.55f), l.fill)
        assertEquals(
            ControlSkins.byId("classic").border.copy(alpha = alphas(0.55f).borderMain),
            l.borderColor
        )
        assertEquals(2f, l.borderWidthDp, 1e-6f)
        assertNull(l.imageRes)
    }

    @Test
    fun `турбо — рамка turboBorder 1dp и приглушенная заливка`() {
        val l = look("classic", "TA", opacity = 0.55f)
        assertEquals(1f, l.borderWidthDp, 1e-6f)
        assertEquals(
            ControlSkins.byId("classic").turboBorder.copy(alpha = alphas(0.55f).borderTurbo),
            l.borderColor
        )
        assertEquals(0.55f * 0.75f, l.fill.alpha, 1f / 255f + 1e-6f)
    }

    @Test
    fun `системные пилюли — форма shapeMeta`() {
        for (id in listOf("SELECT", "START", "PAUSE", "MUTE", "EDIT", "SAVE", "LOAD")) {
            assertEquals("$id: форма пилюли", SkinShape.PILL, look("classic", id).shape)
        }
        // в пиксельном скине пилюли квадратные — скин задаёт форму
        assertEquals(SkinShape.SQUARE, look("pixel", "SELECT").shape)
    }

    // ------------------------------------------------------------------
    // Каптионы: только физические кнопки каптионных скинов
    // ------------------------------------------------------------------

    @Test
    fun `каптионы у гаджета — над физическими кнопками, у классики — нет`() {
        val save = look("gadget", "SAVE", label = "SAVE")
        assertTrue(save.captionMode)
        assertEquals("SAVE", save.captionText)
        // пустой каптион (A/B с запечёнными буквами) не рисуется, но режим включён —
        // текст ВНУТРИ кнопки не появляется
        val a = look("gadget", "A", label = "A")
        assertTrue(a.captionMode)
        assertEquals("", a.captionText)
        // у классики каптионов нет — подпись внутри
        val classic = look("classic", "A", label = "A")
        assertFalse(classic.captionMode)
        assertEquals("", classic.captionText)
        // системные кнопки гаджета — тоже каптионы (имя над пилюлей)
        for (id in listOf("PAUSE", "MUTE", "EDIT", "LOAD")) {
            assertTrue("$id: каптион в гаджете", look("gadget", id, label = id).captionMode)
        }
    }

    @Test
    fun `у гаджета физические кнопки с картинками, у классики параметрика`() {
        for (id in listOf("A", "B", "TA", "TB", "START", "SELECT", "SAVE", "LOAD", "PAUSE", "MUTE", "EDIT")) {
            assertTrue("gadget: $id с картинкой", look("gadget", id).imageRes != null)
            assertNull("classic: $id без картинки", look("classic", id).imageRes)
        }
    }
}
