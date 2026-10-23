package com.dendybox.app.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Симуляция touch-событий для [FingerRouter] (логика переназначения
 * экранных кнопок при скольжении пальца). Класс FingerRouter не зависит
 * от Compose/Android — тесты гоняются на чистой JVM.
 *
 * Виртуальная раскладка теста: кнопка A — x ∈ [0..10], кнопка B —
 * x ∈ [20..30] (y любой), между ними «пустая» зона x ∈ (10..20),
 * за пределами — тоже пусто.
 *
 * «Пятно касания» удалено (AXIS_TOUCH_MAJOR недостоверен на устройствах —
 * давал «нажалась и сразу отжалась»): маршрутизация только по центру
 * пальца, ко-нажатие — двумя пальцами (покрывается тестами ядра
 * TouchRouterCoreTest).
 */
class FingerRouterTest {

    private val events = mutableListOf<String>()

    /** Синтезировать роутер с фиксированным hit-test (без пикселей). */
    private fun makeRouter(): FingerRouter = FingerRouter(
        hitTest = { x, _ ->
            when {
                x <= 10f -> "A"
                x in 20f..30f -> "B"
                else -> null
            }
        },
        onPress = { id, down -> events.add(if (down) "$id+" else "$id-") }
    )

    // ------------------------------------------------------------------
    // Базовое: нажал — отпустил
    // ------------------------------------------------------------------

    @Test
    fun `down on A presses and up releases`() {
        val r = makeRouter()
        r.down(1, 5f, 0f)
        assertEquals(listOf("A+"), events)
        assertTrue(r.isPressed("A"))
        r.up(1)
        assertEquals(listOf("A+", "A-"), events)
        assertFalse(r.isPressed("A"))
    }

    @Test
    fun `untracked finger press does nothing`() {
        val r = makeRouter()
        r.down(1, 15f, 0f) // мимо обеих кнопок
        assertTrue(events.isEmpty())
        assertFalse(r.isPressed("A"))
        assertFalse(r.isPressed("B"))
        r.up(1)
        assertTrue(events.isEmpty())
    }

    // ------------------------------------------------------------------
    // ГЛАВНЫЙ СЦЕНАРИЙ ФИКСА: перенос пальца A → B без отрыва
    // ------------------------------------------------------------------

    @Test
    fun `slide from A to B reassigns button`() {
        val r = makeRouter()
        r.down(1, 5f, 0f)   // A+
        r.move(1, 25f, 0f)  // A-, B+
        assertEquals(listOf("A+", "A-", "B+"), events)
        assertFalse(r.isPressed("A"))
        assertTrue(r.isPressed("B"))
        r.up(1)
        assertEquals(listOf("A+", "A-", "B+", "B-"), events)
        assertFalse(r.isPressed("B"))
    }

    @Test
    fun `rapid alternation A-B-A-B without lifting finger`() {
        val r = makeRouter()
        r.down(1, 5f, 0f)
        r.move(1, 25f, 0f)
        r.move(1, 5f, 0f)
        r.move(1, 25f, 0f)
        assertEquals(listOf("A+", "A-", "B+", "B-", "A+", "A-", "B+"), events)
        assertTrue(r.isPressed("B"))
        r.up(1)
        assertEquals(listOf("A+", "A-", "B+", "B-", "A+", "A-", "B+", "B-"), events)
    }

    @Test
    fun `finger in gap between buttons releases all`() {
        val r = makeRouter()
        r.down(1, 5f, 0f)
        r.move(1, 15f, 0f) // пустая зона между A и B
        assertTrue(events.contains("A-"))
        assertFalse(r.isPressed("A"))
        // палец в зазоре всё ещё отслеживается — возврат на A снова нажимает
        r.move(1, 3f, 0f)
        assertEquals(2, events.count { it == "A+" })
    }

    @Test
    fun `finger in gap is tracked and up is handled`() {
        // уход в зазор и отпускание там — корректный разбор состояния
        val r = makeRouter()
        r.down(1, 5f, 0f)   // A+
        r.move(1, 15f, 0f)  // зазор: A-
        r.up(1)
        assertEquals(listOf("A+", "A-"), events)
        assertFalse(r.isPressed("A"))
        // палец забыт: повторный down обрабатывается заново
        r.down(1, 25f, 0f)
        assertEquals(listOf("A+", "A-", "B+"), events)
    }

    // ------------------------------------------------------------------
    // Палец, стартовавший мимо кнопок группы (крестовина, пустое место),
    // не должен нажимать кнопки при движении
    // ------------------------------------------------------------------

    @Test
    fun `finger starting outside never presses on move`() {
        val r = makeRouter()
        r.down(1, 15f, 0f) // старт в пустоте (например, над крестовиной)
        r.move(1, 25f, 0f) // заехал на B — НЕ должно нажаться
        assertTrue(events.isEmpty())
        assertFalse(r.isPressed("B"))
        r.up(1)
        assertTrue(events.isEmpty())
    }

    // ------------------------------------------------------------------
    // Многопальцевость
    // ------------------------------------------------------------------

    @Test
    fun `two fingers on two buttons work independently`() {
        val r = makeRouter()
        r.down(1, 5f, 0f)   // A+
        r.down(2, 25f, 0f)  // B+
        assertEquals(listOf("A+", "B+"), events)
        // первый палец переезжает с A на B — B остаётся нажатой вторым пальцем
        r.move(1, 25f, 0f)
        assertEquals(listOf("A+", "B+", "A-"), events) // B+ не повторяется — палец 2 уже держит
        assertFalse(r.isPressed("A"))
        assertTrue(r.isPressed("B"))
        r.up(2)
        assertTrue(r.isPressed("B")) // палец 1 всё ещё на B
        assertFalse(events.contains("B-"))
        r.up(1)
        assertTrue(events.contains("B-"))
        assertFalse(r.isPressed("B"))
    }

    @Test
    fun `two fingers on same button release only when both gone`() {
        val r = makeRouter()
        r.down(1, 5f, 0f)
        r.down(2, 7f, 0f)
        assertEquals(listOf("A+"), events) // второй палец не дублирует нажатие
        r.up(1)
        assertTrue(r.isPressed("A")) // второй палец держит
        assertFalse(events.contains("A-"))
        r.up(2)
        assertEquals(listOf("A+", "A-"), events)
        assertFalse(r.isPressed("A"))
    }

    @Test
    fun `cross-swap two fingers between A and B`() {
        val r = makeRouter()
        r.down(1, 5f, 0f)   // A+
        r.down(2, 25f, 0f)  // B+
        r.move(1, 25f, 0f)  // f1: A → B (A-, B остаётся: палец 2)
        r.move(2, 5f, 0f)   // f2: B → A (B остаётся: палец 1, A нажимается заново)
        assertEquals(listOf("A+", "A-", "A+"), events.filter { it == "A+" || it == "A-" })
        assertEquals(listOf("B+"), events.filter { it == "B+" || it == "B-" })
        r.up(1)
        r.up(2)
        assertEquals(listOf("A+", "A-", "A+", "A-"), events.filter { it.startsWith("A") })
        assertEquals(listOf("B+", "B-"), events.filter { it.startsWith("B") })
    }

    // ------------------------------------------------------------------
    // Отмена жеста
    // ------------------------------------------------------------------

    @Test
    fun `releaseAll unpresses everything and resets router`() {
        val r = makeRouter()
        r.down(1, 5f, 0f)
        r.down(2, 25f, 0f)
        events.clear()
        r.releaseAll()
        assertEquals(listOf("A-", "B-"), events)
        assertFalse(r.isPressed("A"))
        assertFalse(r.isPressed("B"))
        // после сброса маршрутизатор принимает новые пальцы
        r.down(3, 5f, 0f)
        assertEquals(listOf("A-", "B-", "A+"), events)
        assertTrue(r.isPressed("A"))
    }

    @Test
    fun `move and up of unknown fingers are ignored`() {
        val r = makeRouter()
        r.move(9, 5f, 0f)
        r.up(9)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `double down on same finger does not double-press`() {
        val r = makeRouter()
        r.down(1, 5f, 0f)
        r.down(1, 5f, 0f)
        assertEquals(listOf("A+"), events)
        r.up(1)
        assertEquals(listOf("A+", "A-"), events)
    }
}
