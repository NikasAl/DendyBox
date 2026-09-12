package com.dendybox.app.emulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import com.dendybox.app.Native
import com.dendybox.app.input.InputState
import com.dendybox.app.net.NetSession
import com.dendybox.app.saves.SaveManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
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

    @Volatile private var audioTrack: AudioTrack? = null
    private var audioBuf: ByteBuffer? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    @Volatile private var underruns = 0L
    // Полоса уровня кольца (в стерео-сэмплах int16): ниже — гоним кадры без сна,
    // выше — притормаживаем; внутри — такт по таймеру с дрейф-коррекцией
    @Volatile private var ringLow = 0
    @Volatile private var ringHigh = 0

    // --- Производительность отрисовки/диагностика ---
    // Липкий флаг: lockHardwareCanvas не сработал (не умеет/бросил) — откат
    // на программный канвас до конца сессии, без попыток каждый кадр
    @Volatile private var hwCanvasBroken = false
    private var rendererLogged = false
    private var perfAccumNs = 0L
    private var perfFrames = 0

    private var fps = 60.0988
    @Volatile private var frameIndex = 0L

    var saves: SaveManager? = null
        private set

    /** SHA-256 загруженного ROM — сверяется при подключении в сетевой игре. */
    var romSha256: String = ""
        private set

    // --- Сетевая игра (lockstep) ---
    @Volatile private var netSession: NetSession? = null
    @Volatile private var netLocalPort = 0
    // Штатный локальный разрыв связи: обрыв сокета не считается ошибкой —
    // игра продолжается соло без паузы (см. обработчик catch в loop())
    @Volatile private var netGracefulDetach = false

    fun isNetActive(): Boolean = netSession != null

    /** Порт локального игрока в сетевой игре (хост — 0, гость — 1). */
    fun netLocalPort(): Int = netLocalPort

    /** Включить сетевой режим (вызывается из потока эмуляции, кадры с нуля). */
    fun attachNet(session: NetSession, localPort: Int) = runOnLoop {
        netSession = session
        netLocalPort = localPort
        frameIndex = 0
    }

    /** Выключить сетевой режим (после обрыва/выхода). */
    fun detachNet() = runOnLoop { netSession = null }

    /**
     * Штатное отключение по инициативе локального игрока (кнопка «Отключить
     * сетевую игру»): закрываем сессию и продолжаем игру соло — без паузы и
     * без уведомления об ошибке. Пир при этом получит обрыв и станет на паузу.
     */
    fun disconnectNet() {
        netGracefulDetach = true
        // 1) закрыть сокет — будит поток кадра, если он ждёт ввода пира;
        // 2) очистить состояние в потоке эмуляции (и снять флаг там же,
        //    чтобы не «проглотить» будущий настоящий обрыв связи)
        try { netSession?.requestClose(null) } catch (_: Exception) {}
        runOnLoop {
            netSession = null
            netGracefulDetach = false
        }
    }

    /**
     * Снять сейв-стейт в потоке эмуляции и вернуть его в [cb] (главный поток).
     * Используется для синхронизации перед началом сетевой игры.
     */
    fun captureState(cb: (ByteArray?) -> Unit) = runOnLoop {
        val size = Native.stateSize()
        if (size <= 0) {
            main.post { cb(null) }
            return@runOnLoop
        }
        val bb = ByteBuffer.allocateDirect(size)
        if (!Native.saveState(bb)) {
            main.post { cb(null) }
            return@runOnLoop
        }
        bb.rewind()
        val arr = ByteArray(size)
        bb.get(arr)
        main.post { cb(arr) }
    }

    /** Загрузить сейв-стейт в потоке эмуляции; результат — в [cb] (главный поток). */
    fun applyState(bytes: ByteArray, cb: (Boolean) -> Unit) = runOnLoop {
        val bb = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        bb.flip()
        main.post { cb(Native.loadState(bb)) }
    }

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
            return "ROM не удалось загрузить.\n\n" +
                "Проверьте файл: ./scripts/check_rom.sh roms/<имя_рома>.nes\n" +
                "Подробная причина — в logcat по тегу «DendyBox» (./scripts/run.sh log)."
        }
        romSha256 = MessageDigest.getInstance("SHA-256")
            .digest(rom)
            .joinToString("") { "%02x".format(it) }

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
        paused = p
        if (p) runOnLoop { saveInternal(SaveManager.AUTO) }
        // Паузу/флаш звуковой дорожки выполняет сам аудиопоток (см. audioLoop) —
        // он же единственный писатель, так что состояние AudioTrack не гонится
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

    /**
     * Сброс картриджа (кнопка RESET): ядро перезапускает игру с нуля.
     * Выполняется в потоке эмуляции, когда кадры не идут. Автосейв сразу
     * перезаписывается стартовым состоянием — иначе после перезапуска
     * приложения игра «продолжилась бы с автосохранения» до-сбросовым
     * прогрессом, и сброс выглядел бы не сработавшим.
     */
    fun resetGame(onDone: (Boolean) -> Unit) = runOnLoop {
        val ok = Native.reset()
        if (ok) {
            Native.clearAudio() // в кольце могло остаться устаревшее звучание
            saveInternal(SaveManager.AUTO)
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
        audioRunning = false
        // Сетевая сессия может держать поток кадра в blocking-чтении —
        // закрываем сокет, чтобы join не повис
        try { netSession?.requestClose(null) } catch (_: Exception) {}
        // Если аудиопоток застрял в blocking write — возобновляем дорожку,
        // чтобы join не повис: играющая дорожка потребляет буфер и write завершится
        try { audioTrack?.play() } catch (_: Exception) {}
        loopThread?.let { t -> try { t.join(2000) } catch (_: InterruptedException) {} }
        loopThread = null
        audioThread?.let { t -> try { t.join(2000) } catch (_: InterruptedException) {} }
        audioThread = null
        try { audioTrack?.pause() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        Native.unload()
    }

    // ------------------------------------------------------------------ //

    private fun loop() {
        // Чуть выше обычного: такт кадров важнее фоновой работы, но ниже
        // приоритетов UI/рендер-потока системы
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (_: Exception) {}
        val paint = Paint().apply { isFilterBitmap = true }
        val frameNs = (1e9 / fps).toLong()
        var nextNs = System.nanoTime()
        var emptyRing = 0
        while (running) {
            ops.poll()?.run()

            if (paused) {
                sleepQuiet(20)
                continue
            }

            val h = holder
            if (h == null || h.surface == null || !h.surface.isValid) {
                sleepQuiet(20)
                continue
            }

            // --- ВВОД ---
            val frameStartNs = System.nanoTime()
            val ns = netSession
            if (ns != null) {
                // Lockstep: кадр N нельзя эмулировать, пока не пришёл ввод пира.
                // Свои биты шлём вперёд, но применяем — как и биты пира — ввод
                // кадра N - DELAY_FRAMES: NetSession задерживает СВОЙ ввод
                // локальной очередью так же, как пира джиттер-буфером. Только
                // так обе машины исполняют одинаковый поток ввода (иначе любая
                // смена кнопок — мгновенный рассинхрон).
                val local = InputState.compose(frameIndex, fps, netLocalPort)
                val inp = try {
                    ns.exchangeWait(frameIndex, local) { Native.ramCrc() }
                } catch (e: Exception) {
                    // Штатный локальный разрыв — продолжаем соло без паузы;
                    // иначе связь потеряна/рассинхрон: пауза + уведомление
                    // пользователя через колбэк сессии (главный поток)
                    netSession = null
                    if (netGracefulDetach) {
                        netGracefulDetach = false
                    } else {
                        paused = true
                        ns.fail(if (e.message.isNullOrBlank()) "Соединение потеряно" else e.message!!)
                    }
                    continue
                }
                if (netLocalPort == 0) Native.setInput(inp.local, inp.peer)
                else Native.setInput(inp.peer, inp.local)
            } else {
                Native.setInput(InputState.compose(frameIndex, fps, 0), 0)
            }
            Native.runFrame()

            frameBitmap?.let { bmp ->
                frameBuffer?.let { fb ->
                    fb.rewind()
                    bmp.copyPixelsFromBuffer(fb)
                }
                drawFrame(h, bmp, paint)
            }
            frameIndex++

            // Диагностика: средняя стоимость кадра (ввод+ядро+отрисовка, без
            // такта). Если близко к бюджету 16,6 мс — устройство не тянет 60 fps
            // (слышно как недогруз звука). В netplay сюда входит и ожидание
            // пира — там показатель не о загруженности CPU
            perfFrames++
            perfAccumNs += System.nanoTime() - frameStartNs
            if (perfFrames >= 300) {
                Log.i("DendyBox", "perf: средний кадр %.1f мс из бюджета 16,6 мс"
                    .format(perfAccumNs / perfFrames / 1e6))
                perfFrames = 0
                perfAccumNs = 0
            }

            // --- ТАКТ КАДРОВ ---
            // Звук генерируется ядром покадрово и уходит в аудиопоток; уровень
            // кольца — точная мера расхождения наших часов и часов ЦАП.
            // Держим кольцо в полосе [ringLow..ringHigh]: ниже — не спим (догоняем),
            // выше — притормаживаем. Это гасит и дрейф, и любое «забегание» вперёд.
            nextNs += frameNs
            val now = System.nanoTime()
            if (nextNs < now) nextNs = now // не копим долг
            val ring = Native.audioLevel()
            emptyRing = if (ring == 0) emptyRing + 1 else 0
            when {
                audioTrack == null || emptyRing > 120 ->
                    // Звука нет вовсе — запасной такт по таймеру
                    if (nextNs > now) sleepQuiet((nextNs - now) / 1_000_000)
                ring < ringLow -> {} // отстаём от звуковых часов — не спим
                ring > ringHigh -> sleepQuiet(3) // забегаем — чуть притормозим
                nextNs > now -> sleepQuiet((nextNs - now) / 1_000_000)
                else -> {}
            }
        }
    }

    /**
     * Прямоугольник игрового кадра на поверхности [vw]×[vh] px — letterbox
     * с целым масштабированием (та же математика, что в drawFrame).
     * Используется UI (редактор управления), чтобы привязываться к картинке.
     */
    fun frameRect(vw: Int, vh: Int): RectF {
        val s0 = min(vw / frameW.toFloat(), vh / frameH.toFloat())
        val scale = if (s0 >= 1f) floor(s0) else s0
        val w = frameW * scale
        val hh = frameH * scale
        val l = (vw - w) / 2f
        val t = (vh - hh) / 2f
        return RectF(l, t, l + w, t + hh)
    }

    /**
     * Кадр на поверхность. Аппаратный канвас (API 26+) отдаёт масштабирование
     * GPU: программный билинейный blit 256x240 -> во весь экран на бюджетных
     * Cortex-A53 стоил дороже, чем работа самого ядра эмуляции (главная
     * причина «тормозов» на слабых телефонах). Если устройство не умеет —
     * липкий откат на программный канвас.
     */
    private fun drawFrame(h: SurfaceHolder, bmp: Bitmap, paint: Paint) {
        val c: Canvas = if (hwCanvasBroken) {
            h.lockCanvas() ?: return
        } else {
            val hw = try { h.lockHardwareCanvas() } catch (_: Exception) { null }
            if (hw != null) hw else {
                hwCanvasBroken = true
                h.lockCanvas() ?: return
            }
        }
        if (!rendererLogged) {
            rendererLogged = true
            Log.i("DendyBox", "render: ${if (hwCanvasBroken) "CPU-канвас (GPU недоступен)" else "GPU-канвас"}")
        }
        try {
            c.drawColor(Color.BLACK)
            val r = frameRect(c.width, c.height)
            // При целом масштабе рисуем без фильтра: чётче картинка и дешевле отрисовка
            paint.isFilterBitmap = (r.width() / frameW) != floor(r.width() / frameW)
            c.drawBitmap(bmp, null, r, paint)
        } finally {
            try { h.unlockCanvasAndPost(c) } catch (_: Exception) {}
        }
    }

    private fun setupAudio() {
        val rate = Native.avSampleRate().toInt().coerceIn(8000, 96000)
        val ch = AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT)
        // Буфер устройства ~83 мс: основной запас живёт в кольце перед аудиопотоком
        val bufSize = maxOf(minBuf, rate / 12 * 4)
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
        // Полоса уровня кольца: 30..150 мс (в стерео-сэмплах int16)
        ringLow = rate * 60 / 1000
        ringHigh = rate * 300 / 1000
        audioTrack?.play()
        // Буфер слива: до 40 мс за одну запись
        audioBuf = ByteBuffer.allocateDirect(rate / 25 * 4).order(ByteOrder.LITTLE_ENDIAN)
        audioRunning = true
        audioThread = thread(name = "emu-audio", isDaemon = true) { audioLoop() }
    }

    /**
     * Отдельный аудиопоток — единственный писатель в AudioTrack.
     * Кольцо между ядром и дорожкой держит запас ~100 мс, поэтому микрофризы
     * эмуляции и отрисовки больше не голодают дорожку. Если кольцо пусто —
     * подкладываем короткую тишину: устройство продолжает потреблять данные
     * и НЕ зацикливает старое содержимое своего буфера (тот самый «эффект эха»,
     * когда AudioTrack при недогрузке многократно проигрывает остаток буфера).
     */
    private fun audioLoop() {
        // Аудиопоток чувствительнее всех к задержкам: недогруз дорожки слышен
        // как щелчки. Даём ему максимальный приоритет в приложении
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val sr = audioTrack?.sampleRate ?: 48000
        val silence = ShortArray(sr / 200 * 2) // ~5 мс тишины
        val ab = audioBuf
        var lastLogNs = System.nanoTime()
        try {
            while (audioRunning) {
                val t = audioTrack ?: break
                if (paused) {
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        try { t.pause(); t.flush() } catch (_: Exception) {}
                        Native.clearAudio()
                    }
                    sleepQuiet(15)
                    continue
                }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try { t.play() } catch (_: Exception) {}
                }
                var n = 0
                if (ab != null) {
                    ab.clear()
                    n = Native.drainAudio(ab)
                    if (n > 0) {
                        ab.position(0)
                        ab.limit(n * 2)
                        t.write(ab, n * 2, AudioTrack.WRITE_BLOCKING)
                    }
                }
                if (n == 0) {
                    underruns++
                    t.write(silence, 0, silence.size)
                    sleepQuiet(2)
                }
                val nowNs = System.nanoTime()
                if (nowNs - lastLogNs >= 15_000_000_000L) {
                    lastLogNs = nowNs
                    Log.i("DendyBox", "audio: ring=${Native.audioLevel()} underruns=$underruns")
                }
            }
        } catch (_: Throwable) {
            // дорожка освобождена при stop() — тихо выходим
        }
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
