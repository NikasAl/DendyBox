package com.dendybox.app.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Симуляция touch-событий для [FingerRouter] (логика переназначения
 * экранных кнопок при скольжении пальца + ко-нажатие «пятном касания»).
 * Класс FingerRouter не зависит от Compose/Android — тесты гоняются
 * на чистой JVM.
 *
 * Виртуальная раскладка теста: кнопка A — x ∈ [0..10], кнопка B —
 * x ∈ [20..30] (y любой), между ними «пустая» зона x ∈ (10..20),
 * за пределами — тоже пусто.
 */
class FingerRouterTest {

    private val events = mutableListOf<String>()

    /**
     * Синтезировать роутер с фиксированным hit-test (без пикселей).
     * [patch] — необязательный «пятновый» тест: ко-нажатие кнопок,
     * накрытых контактной площадкой пальца.
     */
    private fun makeRouter(
        patch: ((Float, Float, Float) -> Set<String>)? = null
    ): FingerRouter = FingerRouter(
        hitTest = { x, _ ->
            when {
                x <= 10f -> "A"
                x in 20f..30f -> "B"
                else -> null
            }
        },
        patchTest = patch,
        onPress = { id, down -> events.add(if (down) "$id+" else "$id-") }
    )

    private fun FingerRouter.down(pid: Long, x: Float) = down(pid, x, 0f)
    private fun FingerRouter.move(pid: Long, x: Float) = move(pid, x, 0f)

    // ------------------------------------------------------------------
    // Базовое: нажал — отпустил
    // ------------------------------------------------------------------

    @Test
    fun `down on A presses and up releases`() {
        val r = makeRouter()
        r.down(1, 5f)
        assertEquals(listOf("A+"), events)
        assertTrue(r.isPressed("A"))
        r.up(1)
        assertEquals(listOf("A+", "A-"), events)
        assertFalse(r.isPressed("A"))
    }

    @Test
    fun `untracked finger press does nothing`() {
        val r = makeRouter()
        r.down(1, 15f) // мимо обеих кнопок
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
        r.down(1, 5f)   // A+
        r.move(1, 25f)  // A-, B+
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
        r.down(1, 5f)
        r.move(1, 25f)
        r.move(1, 5f)
        r.move(1, 25f)
        assertEquals(listOf("A+", "A-", "B+", "B-", "A+", "A-", "B+"), events)
        assertTrue(r.isPressed("B"))
        r.up(1)
        assertEquals(listOf("A+", "A-", "B+", "B-", "A+", "A-", "B+", "B-"), events)
    }

    @Test
    fun `finger in gap between buttons releases all`() {
        val r = makeRouter()
        r.down(1, 5f)
        r.move(1, 15f) // пустая зона между A и B
        assertTrue(events.contains("A-"))
        assertFalse(r.isPressed("A"))
        // возврат на A снова нажимает
        r.move(1, 3f)
        assertEquals(2, events.count { it == "A+" })
    }

    // ------------------------------------------------------------------
    // Палец, стартовавший мимо кнопок группы (крестовина, пустое место),
    // не должен нажимать кнопки при движении
    // ------------------------------------------------------------------

    @Test
    fun `finger starting outside never presses on move`() {
        val r = makeRouter()
        r.down(1, 15f) // старт в пустоте (например, над крестовиной)
        r.move(1, 25f) // заехал на B — НЕ должно нажаться
        assertTrue(events.isEmpty())
        assertFalse(r.isPressed("B"))
        r.up(1)
        assertTrue(events.isEmpty())
    }

    // ------------------------------------------------------------------
    // Ко-нажатие «пятном касания»: один палец держит ДВЕ кнопки
    // (Android сообщает эллипс контакта — ядро пятна может накрывать
    // сразу обе кнопки, если палец лёг между ними или прижат широко)
    // ------------------------------------------------------------------

    @Test
    fun `finger between buttons with wide patch presses both`() {
        // пятно радиуса >= 8 накрывает A и B, где бы ни был центр
        val r = makeRouter(patch = { _, _, rr -> if (rr >= 8f) setOf("A", "B") else emptySet() })
        r.down(1, x = 15f, y = 0f, patchR = 10f) // центр в пустоте между кнопками
        assertEquals(listOf("A+", "B+"), events)
        assertTrue(r.isPressed("A"))
        assertTrue(r.isPressed("B"))
    }

    @Test
    fun `patch release when contact shrinks`() {
        // пятно достаёт до B только когда центр+радиус >= 20
        val r = makeRouter(patch = { x, _, rr -> if (x + rr >= 20f) setOf("B") else emptySet() })
        r.down(1, x = 5f, y = 0f, patchR = 16f) // центр на A, пятно дотянулось до B
        assertEquals(listOf("A+", "B+"), events)
        r.move(1, x = 5f, y = 0f, patchR = 4f)  // контакт сжался — B отжимается
        assertEquals(listOf("A+", "B+", "B-"), events)
        assertTrue(r.isPressed("A"))
        assertFalse(r.isPressed("B"))
        r.up(1)
        assertEquals(listOf("A+", "B+", "B-", "A-"), events)
    }

    @Test
    fun `zero patch radius disables co-press`() {
        // устройство не сообщает размер касания — поведение «только центр»
        val r = makeRouter(patch = { _, _, _ -> setOf("A", "B") })
        r.down(1, x = 5f, y = 0f, patchR = 0f)
        assertEquals(listOf("A+"), events)
        assertFalse(r.isPressed("B"))
    }

    @Test
    fun `patch button released on finger up`() {
        val r = makeRouter(patch = { _, _, rr -> if (rr >= 8f) setOf("A", "B") else emptySet() })
        r.down(1, x = 15f, y = 0f, patchR = 10f)
        events.clear()
        r.up(1)
        assertEquals(listOf("A-", "B-"), events)
        assertFalse(r.isPressed("A"))
        assertFalse(r.isPressed("B"))
    }

    @Test
    fun `patch move from AB pair to B only`() {
        // палец лёг между кнопками (обе нажаты), затем уехал на B — A отжалась
        val r = makeRouter(patch = { x, _, rr ->
            if (x + rr >= 20f && x - rr <= 10f) setOf("A", "B") else emptySet()
        })
        r.down(1, x = 15f, y = 0f, patchR = 10f) // 5..25 — накрывает обе
        assertEquals(listOf("A+", "B+"), events)
        r.move(1, x = 25f, y = 0f, patchR = 10f) // 15..35 — только B
        assertEquals(listOf("A+", "B+", "A-"), events)
        assertFalse(r.isPressed("A"))
        assertTrue(r.isPressed("B"))
    }

    // ------------------------------------------------------------------
    // Многопальцевость (в т.ч. со «пятном»)
    // ------------------------------------------------------------------

    @Test
    fun `two fingers on two buttons work independently`() {
        val r = makeRouter()
        r.down(1, 5f)   // A+
        r.down(2, 25f)  // B+
        assertEquals(listOf("A+", "B+"), events)
        // первый палец переезжает с A на B — B остаётся нажатой вторым пальцем
        r.move(1, 25f)
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
        r.down(1, 5f)
        r.down(2, 7f)
        assertEquals(listOf("A+"), events) // второй палец не дублирует нажатие
        r.up(1)
        assertTrue(r.isPressed("A")) // второй палец держит
        assertFalse(events.contains("A-"))
        r.up(2)
        assertEquals(listOf("A+", "A-"), events)
        assertFalse(r.isPressed("A"))
    }

    @Test
    fun `patch counts with second finger on patched button`() {
        // f1: центр на A, пятном накрывает ещё и B; f2: центр на B.
        // B держат ДВА пальца — уход второго не отжимает её.
        val r = makeRouter(patch = { _, _, rr -> if (rr >= 8f) setOf("B") else emptySet() })
        r.down(1, x = 5f, y = 0f, patchR = 10f) // A+ (центр), B+ (пятно)
        assertEquals(listOf("A+", "B+"), events)
        r.down(2, 25f) // центр на B — B+ НЕ повторяется
        assertEquals(listOf("A+", "B+"), events)
        r.up(2)
        assertTrue(r.isPressed("B")) // пятно первого пальца держит
        assertFalse(events.contains("B-"))
        r.up(1) // отпускает СВОИ кнопки: A (центр) и B (пятно) — в порядке набора
        assertEquals(listOf("A+", "B+", "A-", "B-"), events)
        assertFalse(r.isPressed("A"))
        assertFalse(r.isPressed("B"))
    }

    @Test
    fun `cross-swap two fingers between A and B`() {
        val r = makeRouter()
        r.down(1, 5f)   // A+
        r.down(2, 25f)  // B+
        r.move(1, 25f)  // f1: A → B (A-, B остаётся: палец 2)
        r.move(2, 5f)   // f2: B → A (B остаётся: палец 1, A нажимается заново)
        assertEquals(listOf("A+", "A-", "A+"), events.filter { it == "A+" || it == "A-" })
        assertEquals(listOf("B+"), events.filter { it == "B+" || it == "B-" })
        r.up(1)
        r.up(2)
        assertEquals(listOf("A+", "A-", "A+", "A-"), events.filter { it.startsWith("A") })
        assertEquals(listOf("B+", "B-"), events.filter { it.startsWith("B") })
    }

    @Test
    fun `foreign finger starting on d-pad does not press on patch`() {
        // Палец 1 на A, палец 2 стартовал на крестовине (x=100, далеко):
        // даже если событие придёт в зону группы (поток общий), чужой палец
        // с центром мимо кнопок и пятном, не дотягивающимся до них, не должен
        // ничего нажать. Пятно геометрически честное: накрывает B (x∈[20..30]),
        // только если [x-rr, x+rr] пересекает этот диапазон.
        val r = makeRouter(patch = { x, _, rr ->
            if (x - rr <= 30f && x + rr >= 20f) setOf("B") else emptySet()
        })
        r.down(1, 5f)                            // A+
        r.down(2, x = 100f, y = 0f, patchR = 3f) // крестовина: 97..103 — далеко
        assertEquals(listOf("A+"), events)
        r.move(2, x = 100f, y = 0f, patchR = 3f)
        assertEquals(listOf("A+"), events)
        r.up(2)
        assertEquals(listOf("A+"), events)
    }

    // ------------------------------------------------------------------
    // Отмена жеста
    // ------------------------------------------------------------------

    @Test
    fun `releaseAll unpresses everything and resets router`() {
        val r = makeRouter()
        r.down(1, 5f)
        r.down(2, 25f)
        events.clear()
        r.releaseAll()
        assertEquals(listOf("A-", "B-"), events)
        assertFalse(r.isPressed("A"))
        assertFalse(r.isPressed("B"))
        // после сброса маршрутизатор принимает новые пальцы
        r.down(3, 5f)
        assertEquals(listOf("A-", "B-", "A+"), events)
        assertTrue(r.isPressed("A"))
    }

    @Test
    fun `releaseAll clears patch co-presses too`() {
        val r = makeRouter(patch = { _, _, rr -> if (rr >= 8f) setOf("A", "B") else emptySet() })
        r.down(1, x = 15f, y = 0f, patchR = 10f)
        events.clear()
        r.releaseAll()
        assertEquals(listOf("A-", "B-"), events)
        // после сброса палец 1 считается чужим: повторный down с тем же id
        // обрабатывается заново
        r.down(1, x = 5f, y = 0f, patchR = 0f)
        assertEquals(listOf("A-", "B-", "A+"), events)
    }

    @Test
    fun `move and up of unknown fingers are ignored`() {
        val r = makeRouter()
        r.move(9, 5f)
        r.up(9)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `double down on same finger does not double-press`() {
        val r = makeRouter()
        r.down(1, 5f)
        r.down(1, 5f)
        assertEquals(listOf("A+"), events)
        r.up(1)
        assertEquals(listOf("A+", "A-"), events)
    }
}
