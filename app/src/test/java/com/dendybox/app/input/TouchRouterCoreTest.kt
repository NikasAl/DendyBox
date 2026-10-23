package com.dendybox.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Сценарии сырых касаний для [TouchRouterCore] — ЧИСТАЯ JVM, без
 * Compose/Android. Каждый вызов несёт ЯВНОЕ время события (tMs), поэтому
 * тесты проверяют не только ЧТО нажалось, но и КОГДА: press-событие должно
 * попадать «в очередь эмулятора» синхронно с событием касания — никаких
 * задержек, гашения быстрых серий или коалесцента.
 *
 * Виртуальная раскладка (общий fixture): группа AB с кнопками
 * A — x ∈ [0..10] и B — x ∈ [20..30]; группа META с кнопкой
 * START — x ∈ [40..50]. Между кнопками и за пределами — пусто.
 * Координата y не участвует в hit-test fixture (любая).
 */
class TouchRouterCoreTest {

    /** Запись нажатия «в очередь эмулятора»: время события, кнопка, состояние. */
    private data class Rec(
        val t: Long,
        val group: String,
        val button: String,
        val down: Boolean,
        val port: Int
    )

    private val recs = mutableListOf<Rec>()
    private lateinit var core: TouchRouterCore

    @Before
    fun setUp() {
        core = TouchRouterCore()
        core.onPress { group, button, down, port, t ->
            recs.add(Rec(t, group, button, down, port))
        }
        bindDefault()
    }

    private fun bindDefault() {
        core.bindGroup(
            "AB",
            hitTest = { x, _ ->
                when {
                    x <= 10f -> "A"
                    x in 20f..30f -> "B"
                    else -> null
                }
            },
            patchTest = null,
            port = { 0 }
        )
    }

    // ------------------------------------------------------------------
    // ГЛАВНЫЙ СЦЕНАРИЙ #1: A+B одновременно двумя пальцами
    // ------------------------------------------------------------------

    @Test
    fun `two fingers on A and B press both simultaneously`() {
        core.down(1, 5f, 0f, majorPx = 0f, tMs = 0)
        core.down(2, 25f, 0f, majorPx = 0f, tMs = 100)
        assertEquals(
            listOf(
                Rec(0, "AB", "A", true, 0),
                Rec(100, "AB", "B", true, 0)
            ),
            recs
        )
        // обе кнопки в нажатом состоянии между событиями
        core.move(1, 5f, 0f, 0f, tMs = 200) // «пустое» движение ничего не меняет
        assertEquals(2, recs.size)
    }

    @Test
    fun `combo timing t0 A t100 B t300 both up delivers both presses`() {
        // Дословный сценарий из требований: t=0 A press, t=100ms B press,
        // t=300ms both up — ОБА press-события должны попасть в очередь.
        core.down(1, 5f, 0f, 0f, tMs = 0)      // A+
        core.down(2, 25f, 0f, 0f, tMs = 100)   // B+
        core.up(1, tMs = 300)                  // A-
        core.up(2, tMs = 300)                  // B-
        assertEquals(
            listOf("A+", "B+", "A-", "B-"),
            recs.map { it.button + if (it.down) "+" else "-" }
        )
        assertEquals(listOf(0L, 100L, 300L, 300L), recs.map { it.t })
    }

    @Test
    fun `presses 1ms apart both delivered`() {
        core.down(1, 5f, 0f, 0f, tMs = 1000)
        core.down(2, 25f, 0f, 0f, tMs = 1001)
        assertEquals(listOf(1000L, 1001L), recs.map { it.t })
        assertEquals(listOf("A", "B"), recs.map { it.button })
    }

    // ------------------------------------------------------------------
    // ГЛАВНЫЙ СЦЕНАРИЙ #2: быстрая серия/скольжение A→B без отрыва
    // ------------------------------------------------------------------

    @Test
    fun `fast slide A to B in 50ms delivers every transition instantly`() {
        core.down(1, 5f, 0f, 0f, tMs = 0)     // A+
        core.move(1, 25f, 0f, 0f, tMs = 50)   // A-, B+
        core.up(1, tMs = 120)                 // B-
        assertEquals(
            listOf(
                Rec(0, "AB", "A", true, 0),
                Rec(50, "AB", "A", false, 0),
                Rec(50, "AB", "B", true, 0),
                Rec(120, "AB", "B", false, 0)
            ),
            recs
        )
    }

    @Test
    fun `rapid alternation through gap delivers each step with event time`() {
        core.down(1, 5f, 0f, 0f, tMs = 0)     // A+
        core.move(1, 25f, 0f, 0f, tMs = 30)   // A-, B+
        core.move(1, 15f, 0f, 0f, tMs = 60)   // B- (зазор — ничего не нажато)
        core.move(1, 5f, 0f, 0f, tMs = 90)    // A+
        core.move(1, 25f, 0f, 0f, tMs = 120)  // A-, B+
        core.up(1, tMs = 200)                 // B-
        assertEquals(
            listOf(0L, 30L, 30L, 60L, 90L, 120L, 120L, 200L),
            recs.map { it.t }
        )
        assertEquals(
            listOf("A+", "A-", "B+", "B-", "A+", "A-", "B+", "B-"),
            recs.map { it.button + if (it.down) "+" else "-" }
        )
    }

    // ------------------------------------------------------------------
    // Ко-нажатие «пятном касания» (один палец держит две кнопки)
    // ------------------------------------------------------------------

    /** Пятновый hit-test fixture: пятно накрывает A/B, если дотягивается. */
    private fun bindAbWithPatch() {
        core.bindGroup(
            "AB",
            hitTest = { x, _ ->
                when {
                    x <= 10f -> "A"
                    x in 20f..30f -> "B"
                    else -> null
                }
            },
            patchTest = { x, _, r ->
                if (x - r <= 10f && x + r >= 20f) setOf("A", "B") else emptySet()
            },
            port = { 0 }
        )
    }

    @Test
    fun `single finger between buttons with wide contact presses both`() {
        bindAbWithPatch()
        // major=40px, ядро = 40*0.25 = 10px; центр x=15: 5..25 накрывает обе
        core.down(1, 15f, 0f, majorPx = 40f, tMs = 0)
        assertEquals(
            listOf(
                Rec(0, "AB", "A", true, 0),
                Rec(0, "AB", "B", true, 0)
            ),
            recs
        )
    }

    @Test
    fun `contact shrink releases only patched buttons`() {
        bindAbWithPatch()
        core.down(1, 15f, 0f, majorPx = 40f, tMs = 0) // A+B+ пятном
        recs.clear()
        core.move(1, 15f, 0f, majorPx = 8f, tMs = 100) // ядро 2px: 13..17 — мимо обеих
        assertEquals(listOf("A", "B"), recs.map { it.button })
        assertTrue(recs.none { it.down })
        assertEquals(100L, recs[0].t)
    }

    @Test
    fun `major 0 disables patch co-press`() {
        bindAbWithPatch()
        // центр между кнопками, без данных о размере контакта — нажатий нет
        core.down(1, 15f, 0f, majorPx = 0f, tMs = 0)
        assertTrue(recs.isEmpty())
        // центр на A — только A
        core.down(2, 5f, 0f, majorPx = 0f, tMs = 10)
        assertEquals(listOf("A+"), recs.map { it.button + "+" })
    }

    // ------------------------------------------------------------------
    // Чужие пальцы, владение группами, порты
    // ------------------------------------------------------------------

    @Test
    fun `finger starting on empty space never presses on move`() {
        core.down(9, 35f, 0f, 0f, tMs = 0)   // мимо кнопок (крестовина/пустота)
        core.move(9, 25f, 0f, 0f, tMs = 10)  // заехал на B
        core.up(9, tMs = 20)
        assertTrue(recs.isEmpty())
    }

    @Test
    fun `pointer owned by AB group does not press META buttons on slide`() {
        core.bindGroup(
            "META",
            hitTest = { x, _ -> if (x in 40f..50f) "START" else null },
            patchTest = null,
            port = { 0 }
        )
        core.down(1, 5f, 0f, 0f, tMs = 0)    // A+ (владеет AB)
        core.move(1, 45f, 0f, 0f, tMs = 50)  // съехал с A; START НЕ нажимается
        assertEquals(
            listOf("A+", "A-"),
            recs.map { it.button + if (it.down) "+" else "-" }
        )
        assertEquals(listOf("A"), recs.filter { !it.down }.map { it.button })
    }

    @Test
    fun `each group delivers its own port`() {
        core.bindGroup(
            "META",
            hitTest = { x, _ -> if (x in 40f..50f) "START" else null },
            patchTest = null,
            port = { 1 }
        )
        core.down(1, 45f, 0f, 0f, tMs = 0)  // START порт 1
        core.down(2, 5f, 0f, 0f, tMs = 10)  // A порт 0
        assertEquals(1, recs.first { it.button == "START" }.port)
        assertEquals(0, recs.first { it.button == "A" }.port)
    }

    @Test
    fun `second finger on pressed button does not duplicate`() {
        core.down(1, 5f, 0f, 0f, tMs = 0)   // A+
        core.down(2, 7f, 0f, 0f, tMs = 10)  // второй палец на A — без события
        assertEquals(1, recs.size)
        core.up(1, tMs = 20)                // первый ушёл — второй держит
        assertEquals(1, recs.size)
        core.up(2, tMs = 30)                // A-
        assertEquals(
            listOf("A+", "A-"),
            recs.map { it.button + if (it.down) "+" else "-" }
        )
        assertEquals(listOf(30L), recs.filter { !it.down }.map { it.t })
    }

    @Test
    fun `cross-swap two fingers between A and B`() {
        core.down(1, 5f, 0f, 0f, tMs = 0)    // A+ (f1)
        core.down(2, 25f, 0f, 0f, tMs = 10)  // B+ (f2)
        core.move(1, 25f, 0f, 0f, tMs = 20)  // f1: A → B (A-; B держит f2)
        core.move(2, 5f, 0f, 0f, tMs = 30)   // f2: B → A (A+ заново)
        val seq = recs.map { it.button + if (it.down) "+" else "-" }
        assertEquals(listOf("A+", "B+", "A-", "A+"), seq)
        core.up(1, tMs = 40)
        core.up(2, tMs = 50)
        val fin = recs.map { it.button + if (it.down) "+" else "-" }
        assertEquals(listOf("A+", "B+", "A-", "A+", "B-", "A-"), fin)
    }

    // ------------------------------------------------------------------
    // Жизненный цикл: сброс, отвязка, повторная привязка
    // ------------------------------------------------------------------

    @Test
    fun `liftAll releases everything and core accepts new fingers`() {
        core.down(1, 5f, 0f, 0f, tMs = 0)
        core.down(2, 25f, 0f, 0f, tMs = 10)
        recs.clear()
        core.liftAll(tMs = 100) // ACTION_UP / ACTION_CANCEL
        assertEquals(listOf("A-", "B-"), recs.map { it.button + "-" })
        assertEquals(100L, recs[0].t)
        // после сброса те же id пальцев обрабатываются заново
        core.down(1, 5f, 0f, 0f, tMs = 200)
        assertEquals(
            listOf("A-", "B-", "A+"),
            recs.map { it.button + if (it.down) "+" else "-" }
        )
    }

    @Test
    fun `unbind releases group buttons and stops its routing`() {
        core.down(1, 5f, 0f, 0f, tMs = 0)  // A+
        recs.clear()
        assertTrue(core.unbindGroup("AB", tMs = 50))
        assertEquals(listOf("A-"), recs.map { it.button + "-" })
        assertEquals(50L, recs[0].t)
        // палец 1 «осиротел»: события группы больше не приходят
        core.move(1, 25f, 0f, 0f, tMs = 60)
        core.up(1, tMs = 70)
        assertEquals(1, recs.size)
        assertFalse(core.unbindGroup("AB")) // повторная отвязка — no-op
    }

    @Test
    fun `rebind resets group state and stale owners of that group`() {
        core.down(1, 5f, 0f, 0f, tMs = 0)  // A+ (f1 владеет AB)
        core.bindGroup(                    // повторная привязка той же группы
            "AB",
            hitTest = { x, _ -> if (x <= 10f) "A" else if (x in 20f..30f) "B" else null },
            patchTest = null,
            port = { 0 }
        )
        recs.clear()
        // старый владелец забыт: новый палец с тем же id нажимает снова
        core.down(1, 5f, 0f, 0f, tMs = 100)
        assertEquals(listOf("A+"), recs.map { it.button + "+" })
    }

    // ------------------------------------------------------------------
    // Проба (для диагностического лога)
    // ------------------------------------------------------------------

    @Test
    fun `probe reports owning group and covered buttons without side effects`() {
        val hit = core.probe(5f, 0f, majorPx = 0f)
        assertEquals("AB", hit.group)
        assertEquals(setOf("A"), hit.buttons)
        val miss = core.probe(35f, 0f, majorPx = 0f)
        assertNull(miss.group)
        assertTrue(miss.buttons.isEmpty())
        // проба НЕ меняет состояние
        assertTrue(recs.isEmpty())
    }

    @Test
    fun `probe computes patch radius from major axis`() {
        bindAbWithPatch()
        val hit = core.probe(15f, 0f, majorPx = 40f)
        assertEquals("AB", hit.group)
        assertEquals(setOf("A", "B"), hit.buttons)
        assertEquals(10f, hit.patchR, 0.001f) // 40 * 0.25
    }
}
