package com.dendybox.app.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Сетевая игра на двоих: TCP, lockstep («железная» синхронизация покадрово).
 * Оба смартфона в одной Wi-Fi-сети; хост = Игрок 1, гость = Игрок 2.
 *
 * ПРОТОКОЛ (после установки соединения):
 *   гость -> хост : HELLO|<протокол>|<версия приложения>|<sha256 рома>
 *   хост  -> гость: OK|<протокол>   либо   REJECT|<причина>
 *   хост  -> гость: STATE|<размер>  +  <размер> байт сейв-стейта
 *   гость -> хост : READY
 *   хост  -> гость: GO — после этого обе стороны в бинарном покадровом обмене
 *
 * БИНАРНЫЙ ОБМЕН (после GO), на каждый кадр N:
 *   обе стороны шлют 2 байта — 16-битную маску кнопок СВОЕГО джойстика
 *   (libretro-биты, СТАРШИЙ байт первым — так же, как readShort/readInt);
 *   каждый 120-й кадр дополнительно 4 байта — FNV-1a RAM (контроль десинка).
 *   Ввод кадра N — СВОЙ И ПИРА — применяется на ОБЕИХ машинах на кадре
 *   N + DELAY_FRAMES: свой — через локальную очередь задержки (localBuf),
 *   пира — через джиттер-буфер. Оба эмулятора получают байт-в-байт одинаковый
 *   поток ввода; при коротких замираниях сети кадры продолжают идти из буфера.
 *   (Задерживать только пира нельзя: тогда хост исполняет кадр N как
 *   (свой[N], пир[N-3]), а гость как (хост[N-3], свой[N]) — потоки разные
 *   и любая смена кнопок даёт мгновенный десинк.)
 *   CRC пира сверяется в момент чтения из потока против СОБСТВЕННОГО CRC того
 *   же кадра, снятого при отправке (см. localCrcs) — счёт ведётся по абсолютной
 *   позиции в потоке пира, чтобы джиттер-буфер не сдвигал кадры.
 *   Если буфер пуст и данных нет — обе стороны честно ждут (лаг вместо десинка).
 *
 * Детерминизм: одинаковый ROM (сверка SHA-256), одинаковое ядро (та же сборка
 * APK), старт из одного сейв-стейта (STATE+READY+GO), одинаковый ввод
 * (lockstep). Читы/сейвы в сетевой игре недоступны — они разрушили бы синхронизацию.
 */
class DesyncException(val frame: Long) :
    IOException("Рассинхронизация (кадр $frame) — проверьте, что ROM и версия приложения одинаковые")

/**
 * Ввод для исполнения очередного кадра: свои биты и биты пира, оба кадра
 * [frame - DELAY_FRAMES] (задержаны одинаково — см. шапку класса).
 */
class FrameInputs internal constructor(val local: Int, val peer: Int)

class NetSession internal constructor(
    private val sock: Socket,
    val isHost: Boolean,
    val peerVersion: String
) {
    private val main = Handler(Looper.getMainLooper())
    private val din = BufferedInputStream(sock.getInputStream(), 16384)
    private val dout = BufferedOutputStream(sock.getOutputStream(), 16384)
    private val closed = AtomicBoolean(false)

    /** События сессии — всегда в главном потоке. */
    var onEvent: (NetEvent) -> Unit = {}

    // джиттер-буфер ввода пира: значения для кадров [head..head+size)
    private val buf = ArrayDeque<Int>()
    // локальная очередь задержки СВОЕГО ввода — зеркало джиттер-буфера:
    // применение своего ввода на тех же кадрах, что и ввода пира
    private val localBuf = ArrayDeque<Int>()
    private var head = 0L
    // сколько шортов пира уже прочитано из потока (абсолютный счётчик);
    // шорт №k несёт ввод пира для кадра k, за контрольным (k % 120 == 119)
    // в потоке сразу следует 4-байтовый CRC RAM пира
    private var peerRead = 0L
    // свои CRC контрольных кадров (кадр -> CRC), снятые при отправке;
    // peer CRC(k) приходит из потока на 0..3 кадров позже (джиттер-буфер),
    // поэтому сверяем его с сохранённым значением того же кадра
    private val localCrcs = HashMap<Long, Int>()

    // ------------------------------------------------------------------ //
    // Синхронизация перед игрой (фоновые потоки панели)
    // ------------------------------------------------------------------ //

    /** Хост: отправить стейт, дождаться READY, дать GO. Блокирует. */
    fun hostStart(bytes: ByteArray) {
        writeLine("STATE|${bytes.size}")
        dout.write(bytes)
        dout.flush()
        val ready = readLine()
        if (ready != "READY") throw IOException("Гость ответил неожиданно: $ready")
        writeLine("GO")
        dout.flush()
    }

    /** Гость: принять стейт (блокирует, фоновый поток). */
    fun guestReceiveState(): ByteArray {
        val line = readLine()
        if (!line.startsWith("STATE|")) throw IOException("Хост не прислал состояние: $line")
        val size = line.substringAfter('|').toIntOrNull()
            ?: throw IOException("Плохой размер состояния: $line")
        val bytes = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = din.read(bytes, off, size - off)
            if (n < 0) throw EOFException("Соединение закрыто при передаче состояния")
            off += n
        }
        return bytes
    }

    /** Гость: подтвердить применение стейта (READY) и дождаться GO. Блокирует. */
    fun guestConfirmAndGo() {
        writeLine("READY")
        dout.flush()
        val go = readLine()
        if (go != "GO") throw IOException("Хост не дал старт: $go")
    }

    // ------------------------------------------------------------------ //
    // Lockstep-обмен (ТОЛЬКО из потока эмуляции)
    // ------------------------------------------------------------------ //

    /**
     * Войти в игровой обмен: префильтр из нулей = задержка ввода DELAY_FRAMES
     * кадров (джиттер-буфер). Вызывается один раз перед первым кадром.
     */
    fun enterLockstep() {
        buf.clear()
        localBuf.clear()
        head = 0
        peerRead = 0
        localCrcs.clear()
        repeat(DELAY_FRAMES) {
            buf.addLast(0)       // ввод пира первых кадров — нули (задержка)
            localBuf.addLast(0)  // свой ввод первых кадров — тоже нули
        }
    }

    /**
     * Обмен вводом кадра [frame]: отправить свои биты [localBits], вернуть ввод
     * для исполнения этого кадра — свои биты и биты пира кадра [frame - DELAY_FRAMES]
     * (блокирует, если пир отстаёт). [ramCrc] — контрольная сумма локальной RAM.
     * Бросает IOException/DesyncException при обрыве/десинке.
     */
    fun exchangeWait(frame: Long, localBits: Int, ramCrc: () -> Int): FrameInputs {
        val isCheck = frame % CHECK_EVERY == CHECK_EVERY - 1L

        // 1. Свои данные этого кадра — пиру (без ожидания). Маска — старшим
        //    байтом первым (readShort у пира читает именно так). На контрольном
        //    кадре — свой CRC RAM (состояние после frame-1 кадра, т.е. ровно
        //    frame исполненных кадров) и запоминаем его для сверки
        dout.write((localBits shr 8) and 0xFF)
        dout.write(localBits and 0xFF)
        if (isCheck) {
            val c = ramCrc()
            localCrcs[frame] = c
            localCrcs.keys.removeAll { it < frame - CHECK_EVERY }
            dout.write((c shr 24) and 0xFF)
            dout.write((c shr 16) and 0xFF)
            dout.write((c shr 8) and 0xFF)
            dout.write(c and 0xFF)
        }
        dout.flush()

        // 2. Свой ввод уходит в очередь задержки; исполнить нужно свой ввод
        //    кадра frame - DELAY_FRAMES (пир применит то же самое на своём
        //    кадре frame — потоки ввода обеих машин совпадают байт-в-байт)
        val own = localBuf.pollFirst() ?: localBits
        localBuf.addLast(localBits)

        // 3. Если джиттер-буфер пересох (сеть отстаёт) — догоняем до текущего кадра
        while (head + buf.size <= frame) {
            buf.addLast(readShort())
            consumePeerCrc(ramCrc)
        }
        val cur = buf.pollFirst() ?: throw EOFException("буфер ввода пуст")
        head++

        // 4. Добираем джиттер-буфер вперёд (обычно ровно один шорт за кадр)
        while (buf.size < DELAY_FRAMES) {
            buf.addLast(readShort())
            consumePeerCrc(ramCrc)
        }
        return FrameInputs(own, cur)
    }

    /**
     * Вызывается после каждого прочитанного из потока шорта пира. Если это был
     * контрольный шорт пира (№k, k % CHECK_EVERY == 119) — следующие 4 байта
     * потока это его CRC RAM (состояние пира после k кадров). Сверяем с СОБСТВЕННЫМ
     * CRC того же кадра k: обычный путь читает шорт №k в кадре k — моменты
     * совпадают; при догоне (пир отстал) — против сохранённого localCrcs[k].
     */
    private fun consumePeerCrc(ramCrc: () -> Int) {
        val k = peerRead++
        if (k % CHECK_EVERY == CHECK_EVERY - 1L) {
            val peerCrc = readInt()
            val mine = localCrcs.remove(k)
                ?: throw IOException("Нет локального CRC для кадра $k")
            if (peerCrc != mine) throw DesyncException(k)
        }
    }

    // ------------------------------------------------------------------ //
    // Низкоуровневый ввод/вывод
    // ------------------------------------------------------------------ //

    private fun writeLine(s: String) {
        dout.write(s.toByteArray(Charsets.US_ASCII))
        dout.write('\n'.code)
    }

    private fun readLine(): String {
        val sb = StringBuilder(64)
        while (true) {
            val b = din.read()
            if (b < 0) throw EOFException("Соединение закрыто")
            if (b == '\n'.code) break
            if (sb.length < 512) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readShort(): Int {
        val hi = din.read()
        val lo = din.read()
        if (lo < 0) throw EOFException("Соединение закрыто")
        return ((hi shl 8) or lo) and 0xFFFF
    }

    private fun readInt(): Int {
        var v = 0
        repeat(4) {
            val b = din.read()
            if (b < 0) throw EOFException("Соединение закрыто")
            v = (v shl 8) or b
        }
        return v
    }

    // ------------------------------------------------------------------ //
    // Закрытие
    // ------------------------------------------------------------------ //

    /** Закрыть сессию; [reason] != null — уведомить обработчик событий. */
    fun requestClose(reason: String?) {
        if (closed.compareAndSet(false, true)) {
            try { sock.close() } catch (_: Exception) {}
            if (reason != null) main.post { onEvent(NetEvent.Disconnected(reason)) }
        }
    }

    /** Аварийное закрытие из потока эмуляции (обрыв/десинк). Идемпотентно. */
    fun fail(reason: String) = requestClose(reason)

    companion object {
        const val PORT = 45901
        const val PROTOCOL = 3
        private const val DELAY_FRAMES = 3      // задержка ввода/джиттер-буфер, кадров
        private const val CHECK_EVERY = 120L    // контроль RAM каждые ~2 секунды

        /** Локальные IPv4-адреса для показа на экране хоста. */
        fun localAddresses(): List<String> = try {
            val out = mutableListOf<String>()
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val addrs = nis.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is Inet4Address) {
                        a.hostAddress?.let { out.add(it) }
                    }
                }
            }
            out.distinct()
        } catch (_: Exception) {
            emptyList()
        }

        /**
         * Хост: слушать порт [port] в фоне. Валидный гость (тот же ROM) —
         * через [onSession] (главный поток), события — через [onEvent].
         * Невалидные подключения отклоняются, прослушивание продолжается.
         * Останов: [NetServer.close].
         */
        fun host(
            port: Int,
            romSha: String,
            appVersion: String,
            onSession: (NetSession) -> Unit,
            onEvent: (NetEvent) -> Unit
        ): NetServer = NetServer(port, romSha, appVersion, onSession, onEvent)

        /** Гость: подключиться к [ip]:[port] в фоне; результат через [onSession]/[onEvent]. */
        fun join(
            ip: String,
            port: Int,
            romSha: String,
            appVersion: String,
            onSession: (NetSession) -> Unit,
            onEvent: (NetEvent) -> Unit
        ) {
            thread(name = "net-join") {
                val main = Handler(Looper.getMainLooper())
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(ip, port), 5000)
                    s.tcpNoDelay = true
                    val ns = NetSession(s, isHost = false, peerVersion = "")
                    ns.writeLine("HELLO|$PROTOCOL|$appVersion|$romSha")
                    ns.dout.flush()
                    val resp = ns.readLine()
                    if (resp.startsWith("REJECT")) {
                        val why = resp.substringAfter('|', "отклонено")
                        ns.requestClose(null)
                        main.post { onEvent(NetEvent.Rejected(why)) }
                        return@thread
                    }
                    if (resp != "OK|$PROTOCOL") throw IOException("Хост ответил неожиданно: $resp")
                    ns.onEvent = onEvent
                    main.post { onSession(ns) }
                } catch (e: Exception) {
                    Log.w("DendyBox", "net join failed", e)
                    main.post {
                        onEvent(
                            NetEvent.Rejected(
                                if (e is SocketTimeoutException) "Хост не отвечает"
                                else "Не удалось подключиться: ${e.message ?: "ошибка сети"}"
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Принимающая сторона хоста: слушает порт, проходит хендшейк с каждым
 * входящим подключением (невалидные отклоняет и продолжает слушать).
 */
class NetServer internal constructor(
    port: Int,
    private val romSha: String,
    private val appVersion: String,
    private val onSession: (NetSession) -> Unit,
    private val onEvent: (NetEvent) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var server: ServerSocket? = null

    @Volatile var session: NetSession? = null
        private set

    init {
        thread(name = "net-host") {
            try {
                val ss = ServerSocket(port, 2, InetAddress.getByName("0.0.0.0"))
                server = ss
                while (!closed.get()) {
                    val s = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (!closed.get()) {
                            main.post { onEvent(NetEvent.Disconnected("Сервер остановлен: ${e.message}")) }
                        }
                        return@thread
                    }
                    s.tcpNoDelay = true
                    try {
                        val hello = readLineOf(s.getInputStream())
                        val parts = hello.split('|')
                        val peerProto = parts.getOrElse(1) { "" }.toIntOrNull()
                        val peerVer = parts.getOrElse(2) { "" }
                        val peerSha = parts.getOrElse(3) { "" }
                        val out = s.getOutputStream()
                        if (peerProto != NetSession.PROTOCOL) {
                            writeLineOf(out, "REJECT|Другая версия протокола — обновите приложение")
                            s.close()
                            continue
                        }
                        if (peerSha != romSha) {
                            writeLineOf(out, "REJECT|Разные ROM — на обоих телефонах должна быть та же игра")
                            s.close()
                            continue
                        }
                        writeLineOf(out, "OK|${NetSession.PROTOCOL}")
                        val ns = NetSession(s, isHost = true, peerVersion = peerVer)
                        ns.onEvent = onEvent
                        session = ns
                        main.post { onSession(ns) }
                        return@thread // один игрок — достаточно
                    } catch (e: Exception) {
                        Log.w("DendyBox", "handshake failed", e)
                        try { s.close() } catch (_: Exception) {}
                        // продолжаем слушать следующих
                    }
                }
            } catch (e: Exception) {
                if (!closed.get()) {
                    Log.w("DendyBox", "host server failed", e)
                    main.post { onEvent(NetEvent.Disconnected("Не удалось открыть порт: ${e.message}")) }
                }
            }
        }
    }

    private fun writeLineOf(out: java.io.OutputStream, s: String) {
        out.write((s + "\n").toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun readLineOf(input: InputStream): String {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Гость отключился")
            if (b == '\n'.code) break
            if (sb.length < 512) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** Остановить прослушивание и (если есть) активную сессию. */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            try { server?.close() } catch (_: Exception) {}
            session?.requestClose(null)
        }
    }
}

/** События сетевой сессии (доставляются в главный поток). */
sealed class NetEvent {
    /** Гость подключился и прошёл проверку ROM (у хоста). */
    object PeerJoined : NetEvent()

    /** Подключение отклонено (другой ROM/протокол/таймаут). */
    data class Rejected(val reason: String) : NetEvent()

    /** Связь потеряна или рассинхрон; сессия закрыта. */
    data class Disconnected(val reason: String) : NetEvent()
}
