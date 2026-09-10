package com.dendybox.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dendybox.app.emulator.EmulatorEngine
import com.dendybox.app.net.NetEvent
import com.dendybox.app.net.NetServer
import com.dendybox.app.net.NetSession
import kotlin.concurrent.thread

/**
 * Экран «Игра по сети»: хост создаёт игру (Игрок 1), гость вводит его IP
 * (Игрок 2). После подключения хост жмёт «Начать» — обе стороны синхронизируются
 * сейв-стейтом и уходят в lockstep-игру.
 *
 * [onDisconnect] вызывается при обрыве/рассинхроне В ЛЮБОЙ момент (в том числе
 * во время самой игры, когда этой панели уже нет на экране — поэтому лямбда
 * приходит из GameScreen и переживает эту композицию).
 */
@Composable
fun NetPanel(
    engine: EmulatorEngine,
    onBack: () -> Unit,
    onStarted: (Int) -> Unit,
    onDisconnect: (String) -> Unit
) {
    val context = LocalContext.current
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    // choose | hosting | joining | peer (хост: гость подключился) | sync | error
    var phase by remember { mutableStateOf("choose") }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var ip by remember { mutableStateOf("") }
    var server by remember { mutableStateOf<NetServer?>(null) }
    var session by remember { mutableStateOf<NetSession?>(null) }
    // после передачи управления в игру (onStarted) события уводят в onDisconnect
    val handedOver = remember { mutableStateOf(false) }

    val ips = remember { NetSession.localAddresses() }

    // Активные до старта читы (RAM-патчи/GG) на двух телефонах почти наверняка
    // разные — они бы разрушили покадровую синхронизацию. Отключаем на обеих
    // сторонах при старте сетевой игры (сохранённые списки читов не трогаем).
    fun clearCheatsForNet() {
        engine.applyCheats(IntArray(0), emptyList())
    }

    fun fail(msg: String) {
        handedOver.value = false
        session?.requestClose(null)
        session = null
        server?.close()
        server = null
        phase = "error"
        error = msg
    }

    fun sessionEvents(e: NetEvent) {
        when (e) {
            is NetEvent.Disconnected ->
                if (handedOver.value) onDisconnect(e.reason) else fail(e.reason)
            is NetEvent.Rejected -> fail(e.reason)
            is NetEvent.PeerJoined -> {} // подключение приходит через onSession
        }
    }

    fun cancel() {
        handedOver.value = true // без уведомления — отменяем сами
        session?.requestClose(null)
        session = null
        server?.close()
        server = null
        handedOver.value = false
        phase = "choose"
        status = ""
        error = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(
                Modifier
                    .padding(24.dp)
                    .width(360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Игра по сети", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Оба телефона — в одной Wi-Fi-сети, одна и та же игра. " +
                        "Хост — Игрок 1, гость — Игрок 2.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (phase) {
                    "choose" -> {
                        Button(
                            onClick = {
                                phase = "hosting"
                                status = "Ожидание Игрока 2…"
                                server = NetSession.host(
                                    NetSession.PORT, engine.romSha256, appVersion,
                                    onSession = { ns ->
                                        session = ns
                                        ns.onEvent = ::sessionEvents
                                        phase = "peer"
                                        status = "Игрок 2 подключился!"
                                    },
                                    onEvent = ::sessionEvents
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Создать игру (Игрок 1)") }

                        OutlinedTextField(
                            value = ip,
                            onValueChange = { ip = it.trim() },
                            label = { Text("IP хоста") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                if (ip.isBlank()) {
                                    status = "Введите IP хоста (он показан у создателя игры)"
                                    return@OutlinedButton
                                }
                                phase = "joining"
                                status = "Подключение к $ip…"
                                clearCheatsForNet()
                                NetSession.join(
                                    ip, NetSession.PORT, engine.romSha256, appVersion,
                                    onSession = { ns ->
                                        session = ns
                                        ns.onEvent = ::sessionEvents
                                        phase = "sync"
                                        status = "Синхронизация…"
                                        thread(name = "net-sync") {
                                            try {
                                                val bytes = ns.guestReceiveState()
                                                engine.applyState(bytes) { ok ->
                                                    if (!ok) {
                                                        ns.fail("Не удалось применить состояние игры")
                                                        return@applyState
                                                    }
                                                    thread(name = "net-ready") {
                                                        try {
                                                            ns.guestConfirmAndGo()
                                                            ns.enterLockstep()
                                                            engine.attachNet(ns, 1)
                                                            handedOver.value = true
                                                            onStarted(1)
                                                        } catch (e: Exception) {
                                                            ns.fail(e.message ?: "Ошибка старта")
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                ns.fail(e.message ?: "Ошибка синхронизации")
                                            }
                                        }
                                    },
                                    onEvent = ::sessionEvents
                                )
                            },
                            enabled = ip.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Подключиться (Игрок 2)") }

                        if (status.isNotBlank()) {
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(
                            onClick = {
                                // остановить прослушивание/подключение, если они запущены
                                cancel()
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Назад")
                        }
                    }

                    "hosting" -> {
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        if (ips.isEmpty()) {
                            Text(
                                "Не удалось определить IP — проверьте Wi-Fi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                "Пусть Игрок 2 введёт на своём телефоне адрес:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            ips.forEach { a ->
                                Text(
                                    a,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        OutlinedButton(onClick = { cancel() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Отмена")
                        }
                    }

                    "peer" -> {
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Позиция в игре: курсор меню на экране хоста. " +
                                "После старта управление делится: вы — Джойстик 1.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                val ns = session
                                if (ns == null) {
                                    fail("Гость отключился")
                                } else {
                                    phase = "sync"
                                    status = "Синхронизация…"
                                    clearCheatsForNet()
                                    engine.captureState { bytes ->
                                        if (bytes == null) {
                                            ns.fail("Не удалось снять состояние игры")
                                            return@captureState
                                        }
                                        thread(name = "net-sync") {
                                            try {
                                                ns.hostStart(bytes)
                                                ns.enterLockstep()
                                                engine.attachNet(ns, 0)
                                                handedOver.value = true
                                                onStarted(0)
                                            } catch (e: Exception) {
                                                ns.fail(e.message ?: "Ошибка синхронизации")
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Начать игру") }
                        OutlinedButton(onClick = { cancel() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Отмена")
                        }
                    }

                    "joining", "sync" -> {
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { cancel() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Отмена")
                        }
                    }

                    else -> { // error
                        Text(
                            error ?: "Ошибка сети",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { cancel() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Понятно")
                        }
                    }
                }

                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
