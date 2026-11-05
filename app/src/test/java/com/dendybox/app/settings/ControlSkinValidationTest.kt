package com.dendybox.app.settings

import com.dendybox.app.ui.controls.ControlSkins
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Валидация сохранённого скина контролов (SettingsStore.normalizeControlSkin).
 * Регрессия 04.11: валидатор в init хардкодил {classic, gadget} и молча
 * сбрасывал добавленный позже «Приставку» (console) на классику при каждом
 * старте приложения. Теперь набор известных id берётся из реестра
 * ControlSkins.ALL — здесь фиксируется контракт: ЛЮБОЙ скин из реестра
 * проходит валидацию без изменений, неизвестный → классика.
 */
class ControlSkinValidationTest {

    private val allIds = ControlSkins.ALL.mapTo(HashSet()) { it.id }

    @Test
    fun `все скины из реестра проходят валидацию без изменений`() {
        ControlSkins.ALL.forEach { skin ->
            assertEquals(skin.id, SettingsStore.normalizeControlSkin(skin.id, allIds))
        }
    }

    @Test
    fun `консольный скин не сбрасывается — регрессия от 04 11`() {
        assertEquals(ControlSkins.CONSOLE_ID, SettingsStore.normalizeControlSkin(ControlSkins.CONSOLE_ID, allIds))
    }

    @Test
    fun `неизвестные id удалённых скинов заменяются классикой`() {
        val removed = listOf("neon", "pixel", "dark", "dendi", "carbon", "arcade", "", "Гаджет")
        removed.forEach { stored ->
            assertEquals(ControlSkins.DEFAULT_ID, SettingsStore.normalizeControlSkin(stored, allIds))
        }
    }

    @Test
    fun `реестр содержит ровно три скина и классика первый`() {
        assertEquals(setOf("classic", "gadget", "console"), allIds)
        assertEquals(ControlSkins.DEFAULT_ID, ControlSkins.ALL.first().id)
    }
}
