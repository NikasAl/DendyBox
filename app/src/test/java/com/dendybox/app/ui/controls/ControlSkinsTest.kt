package com.dendybox.app.ui.controls

import androidx.compose.ui.graphics.Color
import com.dendybox.app.ui.AccentRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скины кнопок управления ([ControlSkin]/[ControlSkins]) — чистый визуальный
 * конфиг. Ключевые инварианты:
 *  - набор СОКРАЩЁН до двух: «Классический» (дефолт, параметрика) и
 *    «Гаджет» (пакет-скин по фото-референсам, единственный с корпусом);
 *  - «Классический» повторяет прежний захардкоженный вид: белые круги/пилюли,
 *    красная подсветка турбо и активных стрелок, стрелочная крестовина;
 *  - классика — дефолт и запасной скин для неизвестного id (в том числе для
 *    id удалённых скинов — неон/пиксель/тёмный/денди/карбон/аркада);
 *  - скины визуально различаются (иначе выбор бессмыслен);
 *  - все базовые цвета полностью непрозрачны: прозрачность принадлежит
 *    [ControlAlphas] (слайдер «Видимость»), скин только красит.
 *
 * Image-механика (картинки из ресурсов) — в ControlImagesTest.
 */
class ControlSkinsTest {

    private val classic = ControlSkins.byId(ControlSkins.DEFAULT_ID)

    // ------------------------------------------------------------------
    // Состав набора: ровно два скина
    // ------------------------------------------------------------------

    @Test
    fun `в наборе ровно два скина классический и гаджет`() {
        assertEquals(2, ControlSkins.ALL.size)
        assertEquals(listOf("classic", "gadget"), ControlSkins.ALL.map { it.id })
        assertEquals(
            listOf("Классический", "Гаджет"),
            ControlSkins.ALL.map { it.title }
        )
        // уникальность id и названий
        assertEquals(2, ControlSkins.ALL.map { it.id }.toSet().size)
        assertEquals(2, ControlSkins.ALL.map { it.title }.toSet().size)
        // удалённые скины больше не резолвятся
        for (removed in listOf("neon", "pixel", "dark", "dendy", "carbon", "arcade")) {
            assertSame("$removed: должен резолвиться в классику", classic, ControlSkins.byId(removed))
        }
    }

    @Test
    fun `классический идёт первым и является запасным для неизвестного идентификатора`() {
        assertSame(ControlSkins.ALL.first(), classic)
        assertSame(classic, ControlSkins.byId("неизвестный"))
        assertSame(classic, ControlSkins.byId(""))
        assertSame(classic, ControlSkins.DEFAULT)
    }

    // ------------------------------------------------------------------
    // Классика = прежний вид (без изменений дефолта)
    // ------------------------------------------------------------------

    @Test
    fun `классический скин повторяет прежний вид кнопок`() {
        assertEquals(SkinShape.CIRCLE, classic.shapeMain)
        assertEquals(SkinShape.PILL, classic.shapeMeta)
        assertEquals(Color.White, classic.fill)
        assertEquals(Color.White, classic.border)
        assertEquals(AccentRed, classic.turboBorder)
        assertEquals(AccentRed, classic.comboBorder)
        assertEquals(Color.White, classic.label)
        assertFalse(classic.monospace)
    }

    @Test
    fun `классическая крестовина — белый круг со стрелками и красной подсветкой`() {
        assertEquals(DpadVisual.TRIANGLES, classic.dpadVisual)
        assertEquals(Color.White, classic.dpadFill)
        assertEquals(Color.White, classic.dpadStroke)
        assertEquals(Color.White, classic.dpadArrow)
        assertEquals(AccentRed, classic.dpadArrowActive)
        assertEquals(Color.White, classic.dpadDot)
    }

    @Test
    fun `турбо и комбо рамки классики отличаются от обычной рамки`() {
        assertNotEquals(classic.border, classic.turboBorder)
        assertNotEquals(classic.border, classic.comboBorder)
    }

    // ------------------------------------------------------------------
    // «Гаджет» — пакет-скин с каптионами (подписи НАД кнопками)
    // ------------------------------------------------------------------

    @Test
    fun `гаджет — единственный скин с каптионами и переопределением турбо`() {
        val gadget = ControlSkins.byId("gadget")
        // константа id совпадает со скином (корпус выбирается по ней)
        assertEquals("gadget", ControlSkins.GADGET_ID)
        assertSame(gadget, ControlSkins.byId(ControlSkins.GADGET_ID))
        assertTrue(gadget.captions)
        assertEquals("TURBO", gadget.captionFor("TA", "A′"))
        assertEquals("TURBO", gadget.captionFor("TB", "B′"))
        // буквы B/A запечены в фото-кнопки — каптионы над ними ПУСТЫЕ
        // (пустой каптион не рисуется, текст внутри кнопки тоже)
        assertEquals("", gadget.captionFor("A", "A"))
        assertEquals("", gadget.captionFor("B", "B"))
        // у спека SELECT подпись «SEL» — каптион даёт полное имя
        assertEquals("SELECT", gadget.captionFor("SELECT", "SEL"))
        assertEquals("START", gadget.captionFor("START", "START"))
        assertEquals("SAVE", gadget.captionFor("SAVE", "SAVE"))
        assertEquals("LOAD", gadget.captionFor("LOAD", "LOAD"))
        // остальные скины каптионов не включают — дефолтный вид не затронут
        for (s in ControlSkins.ALL.filter { it.id != "gadget" }) {
            assertFalse("${s.id}: каптионы должны быть выключены", s.captions)
            assertTrue("${s.id}: карта каптионов пуста", s.captionLabels.isEmpty())
        }
    }

    @Test
    fun `каптион без переопределения возвращается к подписи спека у всех скинов`() {
        // у гаджета A/SELECT переопределены («» и «SELECT») — только фолбэк
        // для id вне карты и все остальные скины
        for (s in ControlSkins.ALL) {
            assertEquals("X", s.captionFor("неизвестный", "X"))
            assertEquals("START", s.captionFor("START", "START"))
        }
        for (s in ControlSkins.ALL.filter { it.id != "gadget" }) {
            assertEquals("A", s.captionFor("A", "A"))
            assertEquals("SEL", s.captionFor("SELECT", "SEL"))
        }
    }

    // ------------------------------------------------------------------
    // Скины различаются между собой
    // ------------------------------------------------------------------

    @Test
    fun `скины попарно различаются формой или цветами`() {
        val looks = ControlSkins.ALL.map { Triple(it.fill, it.border, it.shapeMain) }.toSet()
        assertEquals(ControlSkins.ALL.size, looks.size)
    }

    @Test
    fun `гаджет отличается от классики каптионами рамкой и подписями`() {
        val gadget = ControlSkins.byId("gadget")
        assertTrue(gadget.captions && !classic.captions)
        assertNotEquals(classic.border, gadget.border)
        assertNotEquals(classic.label, gadget.label)
        // крестовина у обоих стрелочная, моношрифт нигде не включён
        for (s in ControlSkins.ALL) {
            assertEquals(DpadVisual.TRIANGLES, s.dpadVisual)
            assertFalse("${s.id}: моношрифт", s.monospace)
        }
    }

    // ------------------------------------------------------------------
    // Инвариант прозрачности: скин красит, альфу задаёт ControlAlphas
    // ------------------------------------------------------------------

    @Test
    fun `базовые цвета всех скинов полностью непрозрачны`() {
        for (skin in ControlSkins.ALL) {
            for ((i, c) in skin.colors().withIndex()) {
                assertEquals("скин ${skin.id}, цвет #$i", 1f, c.alpha, 1e-6f)
            }
        }
    }

    @Test
    fun `набор цветов скина покрывает все элементы отрисовки`() {
        // fill, border, turbo, combo, label + 5 цветов крестовины
        for (skin in ControlSkins.ALL) assertEquals(10, skin.colors().size)
    }

    // ------------------------------------------------------------------
    // Комбо-кнопки и неизвестные спеки: картинок нет ни у одного скина
    // ------------------------------------------------------------------

    @Test
    fun `комбо и неизвестные кнопки картинок не получают`() {
        for (s in ControlSkins.ALL) {
            assertNull("${s.id}: комбо", s.buttonImage("K1"))
            assertNull("${s.id}: пустой id", s.buttonImage(""))
            assertNull("${s.id}: неизвестный id", s.buttonImage("XX"))
        }
    }
}
