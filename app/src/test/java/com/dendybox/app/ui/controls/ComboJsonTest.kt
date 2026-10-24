package com.dendybox.app.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON-модель комбо-кнопок (хранение пользователя + пресеты из
 * roms/<flavor>.json) и раскладка блока на экране (comboSpecs) — чистые
 * функции, JVM.
 */
class ComboJsonTest {

    private val superHit = ComboButton(
        id = "u1",
        name = "Суперудар",
        steps = listOf(ComboStep("A", 0), ComboStep("B", 100))
    )

    // ------------------------------------------------------------------
    // Круговорот JSON
    // ------------------------------------------------------------------

    @Test
    fun `круговорот списка через json сохраняет данные`() {
        val src = listOf(
            superHit,
            ComboButton("u2", "Ультра", listOf(ComboStep("DOWN", 0), ComboStep("RIGHT", 100), ComboStep("B", 100)))
        )
        val back = ComboJson.listFromJson(ComboJson.listToJson(src))
        assertEquals(src, back)
    }

    @Test
    fun `битые записи пропускаются молча`() {
        val json = """
            [
              {"id":"ok","name":"Хорошая","steps":[{"b":"A","d":0}]},
              {"id":"bad-btn","name":"Нет такой кнопки","steps":[{"b":"TURBO","d":0}]},
              {"id":"bad-delay","name":"Не кратна 50","steps":[{"b":"A","d":155}]},
              {"id":"neg","name":"Отрицательная","steps":[{"b":"A","d":-100}]},
              {"id":"no-steps","name":"Пустые шаги","steps":[]},
              {"name":"Без id","steps":[{"b":"A","d":0}]},
              {"id":"no-name","steps":[{"b":"A","d":0}]},
              "вообще не объект",
              {"id":"empty-name","name":"   ","steps":[{"b":"A","d":0}]}
            ]
        """.trimIndent()
        val back = ComboJson.listFromJson(json)
        assertEquals(listOf("ok"), back.map { it.id })
    }

    @Test
    fun `кривой json целиком — пустой список, не исключение`() {
        assertTrue(ComboJson.listFromJson("не json").isEmpty())
        assertTrue(ComboJson.listFromJson("").isEmpty())
    }

    // ------------------------------------------------------------------
    // Границы валидации
    // ------------------------------------------------------------------

    @Test
    fun `границы числа шагов и задержек`() {
        val ok = (1..12).map { ComboStep("A", 0) }
        assertTrue(ComboJson.validSteps(ok))
        assertFalse(ComboJson.validSteps(ok + ComboStep("B", 0))) // 13 — много
        assertTrue(ComboJson.validSteps(listOf(ComboStep("B", 500))))
        assertFalse(ComboJson.validSteps(listOf(ComboStep("B", 600))))
        assertFalse(ComboJson.validSteps(emptyList()))
    }

    // ------------------------------------------------------------------
    // Удержания и одновременность (новая модель шага)
    // ------------------------------------------------------------------

    @Test
    fun `новые поля шага проходят круговорот json`() {
        val src = listOf(
            ComboButton(
                "u1", "Сценарий",
                listOf(
                    ComboStep("A", 0, holdMs = 500),
                    ComboStep("B", 50, with = listOf("RIGHT"), holdMs = 350, fromPress = true)
                )
            )
        )
        assertEquals(src, ComboJson.listFromJson(ComboJson.listToJson(src)))
    }

    @Test
    fun `старый json без новых полей даёт прежние значения и сериализуется без новых ключей`() {
        val b = ComboJson.buttonFromJson(
            org.json.JSONObject(
                """{"id":"x","name":"Старая","steps":[{"b":"A","d":0},{"b":"B","d":100}]}"""
            )
        )!!
        // Значения по умолчанию — прежнее поведение (клик 100 мс, без сдвигов)
        assertEquals(listOf(ComboStep("A", 0), ComboStep("B", 100)), b.steps)
        // Обратная сериализация не добавляет новых ключей — формат хранилища стабилен
        val steps = ComboJson.buttonToJson(b).getJSONArray("steps")
        assertEquals(2, steps.length())
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            assertEquals(2, s.length()) // только b и d
            assertFalse(s.has("w")); assertFalse(s.has("h")); assertFalse(s.has("anchor"))
        }
    }

    @Test
    fun `границы удержания шага — сетка 50, кап 500`() {
        assertTrue(ComboJson.validSteps(listOf(ComboStep("A", 0, holdMs = 50))))
        assertTrue(ComboJson.validSteps(listOf(ComboStep("A", 0, holdMs = 100))))
        assertTrue(ComboJson.validSteps(listOf(ComboStep("A", 0, holdMs = 500))))
        assertFalse(ComboJson.validSteps(listOf(ComboStep("A", 0, holdMs = 0))))
        assertFalse(ComboJson.validSteps(listOf(ComboStep("A", 0, holdMs = 25)))) // не кратно 50
        assertFalse(ComboJson.validSteps(listOf(ComboStep("A", 0, holdMs = 550)))) // выше капа
        // Задержки тоже на сетке 50: старые сотни остаются валидными
        assertTrue(ComboJson.validSteps(listOf(ComboStep("A", 50))))
        assertTrue(ComboJson.validSteps(listOf(ComboStep("A", 150))))
        assertFalse(ComboJson.validSteps(listOf(ComboStep("A", 25))))
        assertFalse(ComboJson.validSteps(listOf(ComboStep("A", 550))))
    }

    @Test
    fun `валидация набора одновременных кнопок`() {
        assertTrue(ComboJson.validSteps(listOf(ComboStep("A", 0, with = listOf("B", "RIGHT")))))
        assertFalse(ComboJson.validSteps(listOf(ComboStep("A", 0, with = listOf("TURBO")))))
        assertFalse(ComboJson.validSteps(listOf(ComboStep("A", 0, with = listOf("A"))))) // дубликат главной
        assertFalse(ComboJson.validSteps(listOf(ComboStep("A", 0, with = listOf("B", "B"))))) // повтор
    }

    @Test
    fun `битый hold роняет только свою запись`() {
        // 175 — в диапазоне, но не кратен 50; 550 клэмпится к 500 (см. тест бэксосебисности)
        val json = """[{"id":"bad-hold","name":"Х","steps":[{"b":"A","d":0,"h":175}]}]"""
        assertTrue(ComboJson.listFromJson(json).isEmpty())
    }

    @Test
    fun `старые удержания выше 500 сжимаются до максимума, запись жива`() {
        // Бэксосебисность: конфиги с h=1000..2000 читаются, но удержание сжато
        assertEquals(
            500,
            ComboJson.stepFromJson(org.json.JSONObject("""{"b":"B","h":1000}"""))?.holdMs
        )
        assertEquals(
            500,
            ComboJson.stepFromJson(org.json.JSONObject("""{"b":"B","h":2000}"""))?.holdMs
        )
        // Запись с большим старым удержанием не выбрасывается из списка
        val back = ComboJson.listFromJson(
            """[{"id":"old","name":"Зарядка","steps":[{"b":"B","d":0,"h":1000}]}]"""
        )
        assertEquals(listOf("old"), back.map { it.id })
        assertEquals(500, back.single().steps.single().holdMs)
    }

    @Test
    fun `опущенные d и неизвестный anchor читаются мягко`() {
        val s = ComboJson.stepFromJson(
            org.json.JSONObject("""{"b":"B","h":300,"anchor":"какой-то мусор"}""")
        )!!
        assertEquals(0, s.delayMs)
        assertEquals(300, s.holdMs)
        assertFalse(s.fromPress) // неизвестный anchor — прежний отсчёт от отпускания
    }

    @Test
    fun `сводка показывает удержания и одновременность`() {
        // Без новых полей сводка не изменилась
        assertEquals("A · B +100", ComboJson.stepsText(superHit.steps))
        assertEquals(
            "A ×500 · B +50 ×500",
            ComboJson.stepsText(
                listOf(
                    ComboStep("A", 0, holdMs = 500),
                    ComboStep("B", 50, holdMs = 500, fromPress = true)
                )
            )
        )
        assertEquals(
            "A+B ×500",
            ComboJson.stepsText(listOf(ComboStep("A", 0, with = listOf("B"), holdMs = 500)))
        )
    }

    @Test
    fun `заглавные буквы кнопок приводятся к канону`() {
        val b = ComboJson.buttonFromJson(
            org.json.JSONObject("""{"id":"x","name":"Т","steps":[{"b":"a","d":0}]}""")
        )
        assertEquals("A", b?.steps?.single()?.btn)
    }

    @Test
    fun `пресет из gamejson с подставленным id читается`() {
        // GameConfig.parseCombos вливает id в объект перед разбором
        val raw = org.json.JSONObject(
            """{"name":"Ультра","steps":[{"b":"DOWN","d":0},{"b":"RIGHT","d":100},{"b":"B","d":100}]}"""
        ).put("id", "preset")
        val b = ComboJson.buttonFromJson(raw)
        assertEquals("Ультра", b?.name)
        assertEquals(3, b?.steps?.size)
        assertNull(ComboJson.buttonFromJson(org.json.JSONObject("""{"name":"Без шагов"}""")))
    }

    // ------------------------------------------------------------------
    // Сводка и раскладка блока
    // ------------------------------------------------------------------

    @Test
    fun `сводка шагов человекочитаема`() {
        // Пауза шага показывается у кнопки, ПЕРЕД которой она держится
        assertEquals("A · B +100", ComboJson.stepsText(superHit.steps))
        assertEquals("↓ · → +100 · B +100", ComboJson.stepsText(listOf(
            ComboStep("DOWN", 0), ComboStep("RIGHT", 100), ComboStep("B", 100)
        )))
    }

    @Test
    fun `сетка комбо-кнопок — размеры и колонки`() {
        assertEquals(1, comboCols(1))
        assertEquals(2, comboCols(2))
        assertEquals(2, comboCols(4))
        assertEquals(3, comboCols(5))
    }

    @Test
    fun `спеки блока — префикс K, размер 56, сетка по центру`() {
        val specs = comboSpecs(
            listOf(
                ComboButton("p0", "Суперудар", listOf(ComboStep("A", 0))),
                ComboButton("p1", "Ультра", listOf(ComboStep("A", 0))),
                ComboButton("p2", "Разбег-пинок", listOf(ComboStep("A", 0))),
                ComboButton("p3", "Разбег-локоть", listOf(ComboStep("A", 0)))
            )
        )
        assertEquals(listOf("Kp0", "Kp1", "Kp2", "Kp3"), specs.map { it.id })
        assertTrue(specs.all { it.size == COMBO_SIZE })
        // 2 колонки × 2 ряда: центры ±32dp по обеим осям
        assertTrue(specs.all { it.dx == -32f || it.dx == 32f })
        assertTrue(specs.all { it.dy == -32f || it.dy == 32f })
        assertEquals(4, specs.map { it.dx to it.dy }.toSet().size) // без совпадений
        // подпись — имя кнопки (рисуется на кнопке)
        assertEquals("Суперудар", specs.first().label)
    }

    @Test
    fun `одна кнопка в центре, пустой блок без спеков`() {
        val one = comboSpecs(listOf(ComboButton("u1", "Суперудар", listOf(ComboStep("A", 0)))))
        assertEquals(0f, one.single().dx, 1e-6f)
        assertEquals(0f, one.single().dy, 1e-6f)
        assertTrue(comboSpecs(emptyList()).isEmpty())
    }
}
