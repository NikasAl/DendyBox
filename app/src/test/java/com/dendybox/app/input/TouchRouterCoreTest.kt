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
 *
 * «Пятно касания» удалено (AXIS_TOUCH_MAJOR недостоверен на устройствах) —
 * маршрутизация только по центру пальца; сценарии ко-нажатия двумя пальцами
 * и быстрой серии — главные регресс-проверки.
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
    private val adopts = mutableListOf<String>()
    private lateinit var core: TouchRouterCore

    @Before
    fun setUp() {
        core = TouchRouterCore(onAdopt = { pid, group, button, t ->
            adopts.add("pid=$pid $group[$button] t=$t")
        })
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
            port = { 0 }
        )
    }

    // ------------------------------------------------------------------
    // ГЛАВНЫЙ СЦЕНАРИЙ #1: A+B одновременно двумя пальцами
    // ------------------------------------------------------------------

    @Test
    fun `two fingers on A and B press both simultaneously`() {
        core.down(1, 5f, 0f, tMs = 0)
        core.down(2, 25f, 0f, tMs = 100)
        assertEquals(
            listOf(
                Rec(0, "AB", "A", true, 0),
                Rec(100, "AB", "B", true, 0)
            ),
            recs
        )
        // обе кнопки в нажатом состоянии между событиями
        core.move(1, 5f, 0f, tMs = 200) // «пустое» движение ничего не меняет
        assertEquals(2, recs.size)
        assertTrue(adopts.isEmpty()) // касания обработаны — усыновлений нет
    }

    @Test
    fun `combo timing t0 A t100 B t300 both up delivers both presses`() {
        // Дословный сценарий из требований: t=0 A press, t=100ms B press,
        // t=300ms both up — ОБА press-события должны попасть в очередь.
        core.down(1, 5f, 0f, tMs = 0)      // A+
        core.down(2, 25f, 0f, tMs = 100)   // B+
        core.up(1, tMs = 300)              // A-
        core.up(2, tMs = 300)              // B-
        assertEquals(
            listOf("A+", "B+", "A-", "B-"),
            recs.map { it.button + if (it.down) "+" else "-" }
        )
        assertEquals(listOf(0L, 100L, 300L, 300L), recs.map { it.t })
    }

    @Test
    fun `combo with B first then A delivers both at event times`() {
        // Сценарий с устройства: первый палец на B, через 120 мс второй на A.
        // Оба нажатия — мгновенно, с временем СВОЕГО события.
        core.down(1, 25f, 0f, tMs = 0)     // B+
        core.down(2, 5f, 0f, tMs = 120)    // A+
        assertEquals(listOf(0L, 120L), recs.map { it.t })
        assertEquals(listOf("B", "A"), recs.map { it.button })
        // удержание обеих — без лишних событий
        core.move(1, 26f, 1f, tMs = 200)
        core.move(2, 4f, 2f, tMs = 250)
        assertEquals(2, recs.size)
    }

    @Test
    fun `presses 1ms apart both delivered`() {
        core.down(1, 5f, 0f, tMs = 1000)
        core.down(2, 25f, 0f, tMs = 1001)
        assertEquals(listOf(1000L, 1001L), recs.map { it.t })
        assertEquals(listOf("A", "B"), recs.map { it.button })
    }

    @Test
    fun `late delivered down carries event time not processing time`() {
        // Событие касания доставлено системе с задержкой: press обязан
        // уйти с ВРЕМЕНЕМ СОБЫТИЯ (t=1000), а не моментом обработки.
        core.down(1, 5f, 0f, tMs = 1000)
        assertEquals(listOf(1000L), recs.map { it.t })
    }

    // ------------------------------------------------------------------
    // ГЛАВНЫЙ СЦЕНАРИЙ #2: быстрая серия/скольжение A→B без отрыва
    // ------------------------------------------------------------------

    @Test
    fun `fast slide A to B in 50ms delivers every transition instantly`() {
        core.down(1, 5f, 0f, tMs = 0)     // A+
        core.move(1, 25f, 0f, tMs = 50)   // A-, B+
        core.up(1, tMs = 120)             // B-
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
        core.down(1, 5f, 0f, tMs = 0)     // A+
        core.move(1, 25f, 0f, tMs = 30)   // A-, B+
        core.move(1, 15f, 0f, tMs = 60)   // B- (зазор — ничего не нажато)
        core.move(1, 5f, 0f, tMs = 90)    // A+
        core.move(1, 25f, 0f, tMs = 120)  // A-, B+
        core.up(1, tMs = 200)             // B-
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
    // Самовосстановление: потерянный DOWN не оставляет кнопку мёртвой
    // ------------------------------------------------------------------

    @Test
    fun `finger whose down was never seen is adopted on first move`() {
        // КАСАНИЕ пальца потерялось (гонка привязки групп, проглоченный
        // системой POINTER_DOWN): в ядро пришло только ДВИЖЕНИЕ — палец
        // берётся в работу на нём, нажатие уходит с временем движения.
        core.move(1, 5f, 0f, tMs = 150)
        assertEquals(
            listOf(Rec(150, "AB", "A", true, 0)),
            recs
        )
        assertEquals(listOf("pid=1 AB[A] t=150"), adopts)
        // дальше палец как обычный: скольжение и отпускание работают
        core.move(1, 25f, 0f, tMs = 180)
        core.up(1, tMs = 220)
        assertEquals(
            listOf("A+", "A-", "B+", "B-"),
            recs.map { it.button + if (it.down) "+" else "-" }
        )
        assertEquals(listOf(150L, 180L, 180L, 220L), recs.map { it.t })
    }

    @Test
    fun `adopted finger does not re-fire on repeated moves`() {
        core.move(1, 5f, 0f, tMs = 100)
        core.move(1, 7f, 0f, tMs = 110)
        core.move(1, 9f, 0f, tMs = 120)
        assertEquals(1, recs.size)
        assertEquals(1, adopts.size)
    }

    @Test
    fun `finger seen at down but missed buttons is never adopted`() {
        // Палец стартовал мимо кнопок (крестовина/пустота) — касание БЫЛО
        // обработано, поэтому движение на кнопку его НЕ усыновляет
        // (семантика прежних зон: не конфликтовать с крестовиной).
        core.down(9, 35f, 0f, tMs = 0)   // мимо кнопок
        core.move(9, 25f, 0f, tMs = 10)  // заехал на B
        core.up(9, tMs = 20)
        assertTrue(recs.isEmpty())
        assertTrue(adopts.isEmpty())
    }

    @Test
    fun `adopt is not reported for normal downs`() {
        core.down(1, 5f, 0f, tMs = 0)
        core.down(2, 25f, 0f, tMs = 10)
        core.up(1, tMs = 20)
        core.up(2, tMs = 30)
        assertTrue(adopts.isEmpty())
    }

    @Test
    fun `liftAll forgets seen fingers so next gesture works normally`() {
        core.down(1, 35f, 0f, tMs = 0)  // мимо кнопок — чужой
        core.liftAll(tMs = 10)
        // новый жест тем же id: обычное нажатие
        core.down(1, 5f, 0f, tMs = 50)
        assertEquals(listOf("A+"), recs.map { it.button + "+" })
        assertTrue(adopts.isEmpty())
    }

    // ------------------------------------------------------------------
    // Чужие пальцы, владение группами, порты
    // ------------------------------------------------------------------

    @Test
    fun `pointer owned by AB group does not press META buttons on slide`() {
        core.bindGroup(
            "META",
            hitTest = { x, _ -> if (x in 40f..50f) "START" else null },
            port = { 0 }
        )
        core.down(1, 5f, 0f, tMs = 0)    // A+ (владеет AB)
        core.move(1, 45f, 0f, tMs = 50)  // съехал с A; START НЕ нажимается
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
            port = { 1 }
        )
        core.down(1, 45f, 0f, tMs = 0)  // START порт 1
        core.down(2, 5f, 0f, tMs = 10)  // A порт 0
        assertEquals(1, recs.first { it.button == "START" }.port)
        assertEquals(0, recs.first { it.button == "A" }.port)
    }

    @Test
    fun `second finger on pressed button does not duplicate`() {
        core.down(1, 5f, 0f, tMs = 0)   // A+
        core.down(2, 7f, 0f, tMs = 10)  // второй палец на A — без события
        assertEquals(1, recs.size)
        core.up(1, tMs = 20)            // первый ушёл — второй держит
        assertEquals(1, recs.size)
        core.up(2, tMs = 30)            // A-
        assertEquals(
            listOf("A+", "A-"),
            recs.map { it.button + if (it.down) "+" else "-" }
        )
        assertEquals(listOf(30L), recs.filter { !it.down }.map { it.t })
    }

    @Test
    fun `cross-swap two fingers between A and B`() {
        core.down(1, 5f, 0f, tMs = 0)    // A+ (f1)
        core.down(2, 25f, 0f, tMs = 10)  // B+ (f2)
        core.move(1, 25f, 0f, tMs = 20)  // f1: A → B (A-; B держит f2)
        core.move(2, 5f, 0f, tMs = 30)   // f2: B → A (A+ заново)
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
        core.down(1, 5f, 0f, tMs = 0)
        core.down(2, 25f, 0f, tMs = 10)
        recs.clear()
        core.liftAll(tMs = 100) // ACTION_UP / ACTION_CANCEL
        assertEquals(listOf("A-", "B-"), recs.map { it.button + "-" })
        assertEquals(100L, recs[0].t)
        // после сброса те же id пальцев обрабатываются заново
        core.down(1, 5f, 0f, tMs = 200)
        assertEquals(
            listOf("A-", "B-", "A+"),
            recs.map { it.button + if (it.down) "+" else "-" }
        )
    }

    @Test
    fun `unbind releases group buttons and stops its routing`() {
        core.down(1, 5f, 0f, tMs = 0)  // A+
        recs.clear()
        assertTrue(core.unbindGroup("AB", tMs = 50))
        assertEquals(listOf("A-"), recs.map { it.button + "-" })
        assertEquals(50L, recs[0].t)
        // палец 1 «осиротел»: события группы больше не приходят
        core.move(1, 25f, 0f, tMs = 60)
        core.up(1, tMs = 70)
        assertEquals(1, recs.size)
        assertFalse(core.unbindGroup("AB")) // повторная отвязка — no-op
    }

    @Test
    fun `rebind resets group state and stale owners of that group`() {
        core.down(1, 5f, 0f, tMs = 0)  // A+ (f1 владеет AB)
        core.bindGroup(                // повторная привязка той же группы
            "AB",
            hitTest = { x, _ -> if (x <= 10f) "A" else if (x in 20f..30f) "B" else null },
            port = { 0 }
        )
        recs.clear()
        // старый владелец забыт: новый палец с тем же id нажимает снова
        core.down(1, 5f, 0f, tMs = 100)
        assertEquals(listOf("A+"), recs.map { it.button + "+" })
    }

    // ------------------------------------------------------------------
    // Проба (для диагностического лога)
    // ------------------------------------------------------------------

    @Test
    fun `probe reports owning group and button without side effects`() {
        val hit = core.probe(5f, 0f)
        assertEquals("AB", hit.group)
        assertEquals("A", hit.button)
        val miss = core.probe(35f, 0f)
        assertNull(miss.group)
        assertNull(miss.button)
        // проба НЕ меняет состояние
        assertTrue(recs.isEmpty())
    }
}
