package com.dendybox.app.input

import java.util.concurrent.atomic.AtomicInteger

/**
 * Состояние ввода P1. Биты соответствуют RETRO_DEVICE_ID_JOYPAD_*,
 * чтобы натив передавал их ядру без перекодировки.
 *
 * Турбо считается по номеру кадра (не по таймеру): инпут — чистая функция
 * от кадра, это пригодится при добавлении netplay (этап 5).
 */
object InputState {
    const val B = 1 shl 0      // RETRO_DEVICE_ID_JOYPAD_B  -> кнопка B NES
    const val Y = 1 shl 1      // не используется на NES
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val UP = 1 shl 4
    const val DOWN = 1 shl 5
    const val LEFT = 1 shl 6
    const val RIGHT = 1 shl 7
    const val A = 1 shl 8      // RETRO_DEVICE_ID_JOYPAD_A  -> кнопка A NES
    const val X = 1 shl 9      // не используется на NES

    const val DIR_MASK = UP or DOWN or LEFT or RIGHT

    private val p1 = AtomicInteger(0)

    fun press(bit: Int, down: Boolean) {
        p1.updateAndGet { cur -> if (down) cur or bit else cur and bit.inv() }
    }

    /** Установить направления крестовины (остальные биты не трогает). */
    fun setDirs(bits: Int) {
        p1.updateAndGet { cur -> (cur and DIR_MASK.inv()) or bits }
    }

    fun clearAll() {
        p1.set(0)
        turboActiveA = false
        turboActiveB = false
    }

    // --- Турбо ---
    var turboHzA = 14f
    var turboHzB = 14f
    @Volatile var turboActiveA = false
    @Volatile var turboActiveB = false

    /** Итоговая маска для кадра [frame]. */
    fun compose(frame: Long, fps: Double): Int {
        var m = p1.get()
        if (turboActiveA) {
            val half = (fps / (2.0 * turboHzA)).toInt().coerceAtLeast(1)
            if (frame / half % 2 == 0L) m = m or A
        }
        if (turboActiveB) {
            val half = (fps / (2.0 * turboHzB)).toInt().coerceAtLeast(1)
            if (frame / half % 2 == 0L) m = m or B
        }
        return m
    }
}
