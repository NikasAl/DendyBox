package com.dendybox.app

import java.nio.ByteBuffer

/**
 * JNI-мост к нативному фронтенду libretro (nesoid.cpp).
 * Все методы потокобезопасны со стороны натива:
 * setInput/setCheats можно звать из UI-потока, остальные — из потока эмуляции.
 */
object Native {
    init {
        System.loadLibrary("nesoid")
    }

    /** Загрузить ядро (libcore_nes.so) и инициализировать его. */
    external fun loadCore(path: String, sysDir: String, saveDir: String): Boolean

    /** Загрузить ROM (iNES). После успеха доступны avFps/avSampleRate/videoDims. */
    external fun loadRom(rom: ByteArray): Boolean

    /** Эмулировать один кадр (читы применяются перед кадром). */
    external fun runFrame(): Boolean

    /** Битовые маски кнопок (libretro: B=1<<0, A=1<<8, см. InputState). */
    external fun setInput(p1: Int, p2: Int)

    /** Прямой буфер для кадра RGB565 (w*h*2 байт). */
    external fun setPixelBuffer(buf: ByteBuffer)

    /** Забрать накопленные сэмплы (int16, стерео) в буфер. Возвращает число сэмплов. */
    external fun drainAudio(buf: ByteBuffer): Int

    external fun avFps(): Double
    external fun avSampleRate(): Double

    /** [width, height] текущего кадра. */
    external fun videoDims(): IntArray

    external fun stateSize(): Int
    external fun saveState(buf: ByteBuffer): Boolean
    external fun loadState(buf: ByteBuffer): Boolean

    /** Читы тройками [addr, value, cmp]; cmp = -1 — без сравнения. */
    external fun setCheats(data: IntArray)

    /** Коды Game Genie (6/8 букв) — через retro_cheat_set ядра. */
    external fun setGgCheats(codes: Array<String>)

    external fun unload()
}
