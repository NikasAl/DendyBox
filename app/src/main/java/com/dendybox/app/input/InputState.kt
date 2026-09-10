package com.dendybox.app.input

import java.util.concurrent.atomic.AtomicInteger

/**
 * Состояние ввода ДВУХ портов (P1 и P2 — локальный 2P и сетевая игра).
 * Биты соответствуют RETRO_DEVICE_ID_JOYPAD_*, чтобы натив передавал их
 * ядру без перекодировки.
 *
 * Турбо считается по номеру кадра (не по таймеру): инпут — чистая функция
 * от кадра, поэтому в netplay каждая сторона сама считает турбо СВОЕГО
 * порта и шлёт пир уже готовые биты — расхождения невозможны.
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

    private val ports = arrayOf(AtomicInteger(0), AtomicInteger(0))

    private fun idx(port: Int) = if (port == 1) 1 else 0

    fun press(bit: Int, down: Boolean, port: Int = 0) {
        ports[idx(port)].updateAndGet { cur -> if (down) cur or bit else cur and bit.inv() }
    }

    /** Установить направления крестовины (остальные биты не трогает). */
    fun setDirs(bits: Int, port: Int = 0) {
        ports[idx(port)].updateAndGet { cur -> (cur and DIR_MASK.inv()) or bits }
    }

    /** Сырая маска порта (для отладки/статуса). */
    fun raw(port: Int = 0): Int = ports[idx(port)].get()

    fun clearAll() {
        ports[0].set(0)
        ports[1].set(0)
        turboActiveA = false
        turboActiveB = false
    }

    // --- Турбо (только P1: у блока P2 турбо-кнопок нет) ---
    var turboHzA = 14f
    var turboHzB = 14f
    @Volatile var turboActiveA = false
    @Volatile var turboActiveB = false

    /** Итоговая маска порта [port] для кадра [frame]. */
    fun compose(frame: Long, fps: Double, port: Int = 0): Int {
        var m = ports[idx(port)].get()
        if (port == 0) {
            if (turboActiveA) {
                val half = (fps / (2.0 * turboHzA)).toInt().coerceAtLeast(1)
                if (frame / half % 2 == 0L) m = m or A
            }
            if (turboActiveB) {
                val half = (fps / (2.0 * turboHzB)).toInt().coerceAtLeast(1)
                if (frame / half % 2 == 0L) m = m or B
            }
        }
        return m
    }
}
