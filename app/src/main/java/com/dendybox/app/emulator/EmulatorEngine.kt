package com.dendybox.app.emulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.widget.Toast
import com.dendybox.app.Native
import com.dendybox.app.input.InputState
import com.dendybox.app.saves.SaveManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.floor
import kotlin.math.min

/**
 * Движок эмуляции: поток цикла кадров, звук (AudioTrack), отрисовка (Canvas blit
 * RGB565), сейв-стейты, читы.
 *
 * Такт выстаивается по fps от ядра (~60.0988 для NTSC NES).
 * Все операции со стейтами выполняются в потоке эмуляции через очередь [ops],
 * чтобы не гоняться с ядром за состоянием.
 */
class EmulatorEngine(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val ops = ConcurrentLinkedQueue<Runnable>()

    private var loopThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    @Volatile private var holder: SurfaceHolder? = null

    private var frameBuffer: ByteBuffer? = null
    private var frameBitmap: Bitmap? = null
    private var frameW = 256
    private var frameH = 240

    private var audioTrack: AudioTrack? = null
    private var audioBuf: ByteBuffer? = null
    private var fps = 60.0988
    @Volatile private var frameIndex = 0L

    var saves: SaveManager? = null
        private set

    /** Запуск. null — успех, иначе текст ошибки для показа пользователю. */
    fun start(rom: ByteArray): String? {
        if (running) return null
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val filesDir = context.filesDir.absolutePath
        if (!Native.loadCore("$nativeDir/libcore_nes.so", filesDir, filesDir)) {
            return "Ядро libcore_nes.so не найдено.\n\n" +
                "Запустите scripts/build_core_fceumm.sh или скачайте ядро вручную\n" +
                "(см. README.md, раздел «Шаг 2. Ядро эмулятора»)."
        }
        if (!Native.loadRom(rom)) {
            return "ROM не удалось загрузить — файл повреждён или это не iNES-образ (.nes)."
        }

        saves = SaveManager(context, SaveManager.sha1(rom))
        fps = Native.avFps()
        val dims = Native.videoDims()
        frameW = dims[0]
        frameH = dims[1]

        val fb = ByteBuffer.allocateDirect(frameW * frameH * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        frameBuffer = fb
        Native.setPixelBuffer(fb)
        frameBitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.RGB_565)

        setupAudio()

        // Автопродолжение после сворачивания приложения
        saves?.load(SaveManager.AUTO)?.let { bytes ->
            val bb = ByteBuffer.allocateDirect(bytes.size).put(bytes)
            bb.flip()
            if (Native.loadState(bb)) toast("Продолжено с автосохранения")
        }

        running = true
        paused = false
        loopThread = thread(name = "emu-loop", isDaemon = true) { loop() }
        return null
    }

    fun attachSurface(h: SurfaceHolder?) {
        holder = h
    }

    /** Пауза; при уходе в паузу автоматически пишется автосейв. */
    fun setPaused(p: Boolean) {
        if (p == paused) return
        if (p) runOnLoop { saveInternal(SaveManager.AUTO) }
        paused = p
    }

    fun isPaused(): Boolean = paused

    /** Выполнить код в потоке эмуляции (кадры в этот момент не идут). */
    fun runOnLoop(r: Runnable) {
        ops.add(r)
    }

    fun saveSlot(n: Int, onDone: (Boolean) -> Unit) = runOnLoop {
        val ok = saveInternal(n)
        main.post { onDone(ok) }
    }

    fun loadSlot(n: Int, onDone: (Boolean) -> Unit) = runOnLoop {
        val bytes = saves?.load(n)
        val ok = if (bytes != null) {
            val bb = ByteBuffer.allocateDirect(bytes.size).put(bytes)
            bb.flip()
            Native.loadState(bb)
        } else {
            false
        }
        main.post { onDone(ok) }
    }

    /** Применить активные читы: RAM-патчи + коды Game Genie. */
    fun applyCheats(ramPacked: IntArray, ggCodes: List<String>) {
        Native.setCheats(ramPacked)
        Native.setGgCheats(ggCodes.toTypedArray())
    }

    fun setSound(on: Boolean) {
        // AudioTrack имеет только setVolume(float) без геттера —
        // property-синтаксис «volume = …» в Kotlin недоступен.
        main.post { audioTrack?.setVolume(if (on) 1f else 0f) }
    }

    fun stop() {
        running = false
        loopThread?.let { t ->
            try { t.join(2000) } catch (_: InterruptedException) {}
        }
        loopThread = null
        try { audioTrack?.pause() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioTrack?.release()
        audioTrack = null
        Native.unload()
    }

    // ------------------------------------------------------------------ //

    private fun loop() {
        val paint = Paint().apply { isFilterBitmap = true }
        var nextNs = System.nanoTime()
        while (running) {
            ops.poll()?.run()

            if (paused) {
                sleepQuiet(30)
                nextNs = System.nanoTime()
                continue
            }

            val h = holder
            if (h == null || h.surface == null || !h.surface.isValid) {
                sleepQuiet(30)
                nextNs = System.nanoTime()
                continue
            }

            Native.setInput(InputState.compose(frameIndex, fps), 0)
            Native.runFrame()

            audioBuf?.let { ab ->
                ab.clear()
                val n = Native.drainAudio(ab)
                if (n > 0) {
                    ab.position(0)
                    ab.limit(n * 2)
                    audioTrack?.write(ab, n * 2, AudioTrack.WRITE_BLOCKING)
                }
            }

            frameBitmap?.let { bmp ->
                frameBuffer?.let { fb ->
                    fb.rewind()
                    bmp.copyPixelsFromBuffer(fb)
                }
                drawFrame(h, bmp, paint)
            }
            frameIndex++

            nextNs += (1e9 / fps).toLong()
            val now = System.nanoTime()
            if (nextNs > now) {
                sleepQuiet((nextNs - now) / 1_000_000)
            } else {
                nextNs = now
            }
        }
    }

    private fun drawFrame(h: SurfaceHolder, bmp: Bitmap, paint: Paint) {
        val c = h.lockCanvas() ?: return
        try {
            c.drawColor(Color.BLACK)
            val vw = c.width.toFloat()
            val vh = c.height.toFloat()
            val s0 = min(vw / frameW, vh / frameH)
            val scale = if (s0 >= 1f) floor(s0) else s0 // целое масштабирование без «мыла»
            val w = frameW * scale
            val hh = frameH * scale
            val l = (vw - w) / 2f
            val t = (vh - hh) / 2f
            c.drawBitmap(bmp, null, RectF(l, t, l + w, t + hh), paint)
        } finally {
            try { h.unlockCanvasAndPost(c) } catch (_: Exception) {}
        }
    }

    private fun setupAudio() {
        val rate = Native.avSampleRate().toInt().coerceIn(8000, 96000)
        val ch = AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, rate / 8 * 4) // ~125 мс запаса от потрескивания
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(ch)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufSize)
            .build()
        audioTrack?.play()
        audioBuf = ByteBuffer.allocateDirect(rate / 15 * 4).order(ByteOrder.LITTLE_ENDIAN)
    }

    /** Выполняется только в потоке эмуляции. */
    private fun saveInternal(n: Int): Boolean {
        val sm = saves ?: return false
        val size = Native.stateSize()
        if (size <= 0) return false
        val bb = ByteBuffer.allocateDirect(size)
        if (!Native.saveState(bb)) return false
        bb.rewind()
        val arr = ByteArray(size)
        bb.get(arr)
        val thumb = frameBitmap?.let { it.copy(Bitmap.Config.RGB_565, false) }
        return try {
            sm.save(n, arr, thumb)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun toast(msg: String) =
        main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
