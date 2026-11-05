package com.dendybox.app.settings

import com.dendybox.app.ui.controls.ControlSkins
import com.dendybox.app.ui.controls.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Регрессия бага «выбранный скин сбрасывается на Классический после закрытия
 * и открытия приложения». Две причины:
 * 1) сеттеры SettingsStore делали edit().putX(...) без терминальной операции —
 *    Editor отбрасывался, выбор не попадал в prefs вовсе (задевало ВСЕ
 *    настройки: турбо, звук, размер крестовины, прозрачность, скин);
 * 2) валидатор в init знал только classic|gadget и затирал «console».
 * Рестарт эмулируется честно: FakePrefs — «диск», два SettingsCore над ним —
 * два процесса; валидные id — настоящий реестр ControlSkins.IDS.
 */
class SettingsRestartTest {

    private val ids = ControlSkins.IDS // classic | gadget | console

    @Test
    fun `выбранная Приставка сохраняется в prefs и переживает рестарт`() {
        val disk = FakePrefs()
        val first = SettingsCore(disk, ids) // «процесс 1»
        first.setControlSkin("console")
        assertEquals("console", first.controlSkin.value)
        // Без apply() здесь оставался дефолт — выбор жил только в StateFlow
        assertEquals("console", disk.data["controlSkin"])
        val restarted = SettingsCore(disk, ids) // «процесс 2» после закрытия/открытия
        assertEquals("console", restarted.controlSkin.value)
        // И prefs валидатором не перезаписан (console в реестре)
        assertEquals("console", disk.data["controlSkin"])
    }

    @Test
    fun `выбранный Гаджет переживает рестарт`() {
        val disk = FakePrefs()
        SettingsCore(disk, ids).setControlSkin("gadget")
        assertEquals("gadget", SettingsCore(disk, ids).controlSkin.value)
    }

    @Test
    fun `все настройки сохраняются и читаются после рестарта`() {
        val disk = FakePrefs()
        val first = SettingsCore(disk, ids)
        first.setTurboHzA(11f)
        first.setTurboHzB(7f)
        first.setSound(false)
        first.setHaptics(false)
        first.setDpadSize(70f)
        first.setControlsOpacity(0.3f)
        first.setControlSkin("gadget")
        first.setTwoLocal(true)

        val restarted = SettingsCore(disk, ids)
        assertEquals(11f, restarted.turboHzA.value, 0f)
        assertEquals(7f, restarted.turboHzB.value, 0f)
        assertEquals(false, restarted.sound.value)
        assertEquals(false, restarted.haptics.value)
        assertEquals(70f, restarted.dpadSize.value, 0f)
        assertEquals(0.3f, restarted.controlsOpacity.value, 0f)
        assertEquals("gadget", restarted.controlSkin.value)
        assertEquals(true, restarted.twoLocal.value)
    }

    @Test
    fun `id удалённого скина нормализуется в классику с чисткой prefs`() {
        val disk = FakePrefs()
        disk.data["controlSkin"] = "neon" // скин, удалённый из набора
        val core = SettingsCore(disk, ids)
        assertEquals("classic", core.controlSkin.value)
        assertEquals("classic", disk.data["controlSkin"])
    }

    @Test
    fun `мусорный id сохранённого скина нормализуется в классику`() {
        val disk = FakePrefs()
        disk.data["controlSkin"] = "garbage"
        val core = SettingsCore(disk, ids)
        assertEquals("classic", core.controlSkin.value)
        assertEquals("classic", disk.data["controlSkin"])
    }

    @Test
    fun `без набора валидных id сохранённое значение не трогается`() {
        val disk = FakePrefs()
        disk.data["controlSkin"] = "console"
        val core = SettingsCore(disk) // validSkinIds пуст — fail-open
        assertEquals("console", core.controlSkin.value)
        assertEquals("console", disk.data["controlSkin"])
    }

    @Test
    fun `миграция deviceSkin — включённый корпус при классике переносится на Гаджет`() {
        val disk = FakePrefs()
        disk.data["deviceSkin"] = "gadget"
        val core = SettingsCore(disk, ids)
        assertEquals("gadget", core.controlSkin.value)
        assertEquals("gadget", disk.data["controlSkin"])
        assertFalse(disk.data.containsKey("deviceSkin"))
    }

    @Test
    fun `миграция deviceSkin — выключенный корпус выбор не меняет`() {
        val disk = FakePrefs()
        disk.data["deviceSkin"] = "none"
        disk.data["controlSkin"] = "console"
        val core = SettingsCore(disk, ids)
        assertEquals("console", core.controlSkin.value)
        assertFalse(disk.data.containsKey("deviceSkin"))
    }
}
