package com.dendybox.app.ui.screens

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dendybox.app.cheats.CheatRepository
import com.dendybox.app.emulator.EmulatorEngine
import com.dendybox.app.input.ComboRunner
import com.dendybox.app.input.GamepadInput
import com.dendybox.app.input.InputState
import com.dendybox.app.saves.SaveManager
import com.dendybox.app.settings.SettingsStore
import com.dendybox.app.ui.controls.BlockRef
import com.dendybox.app.ui.controls.ComboStore
import com.dendybox.app.ui.controls.ControlsLayer
import com.dendybox.app.ui.controls.DeviceShellLayer
import com.dendybox.app.ui.controls.DeviceSkins
import com.dendybox.app.ui.controls.DpadLayer
import com.dendybox.app.ui.controls.EditorSurface
import com.dendybox.app.ui.controls.LayoutStore

enum class Screen { GAME, PAUSE, SLOTS, CHEATS, COMBOS, SETTINGS, EDIT, NET }

@Composable
fun GameScreen(
    engine: EmulatorEngine,
    started: Boolean,
    screen: Screen,
    onScreen: (Screen) -> Unit,
    cheatsRepo: CheatRepository?,
    hasDamageWatch: Boolean = false,
    netplayEnabled: Boolean = true,
    exitLabel: String = "Выход из игры",
    onSoundChange: (Boolean) -> Unit,
    onExit: () -> Unit,
    onSurfaceCreated: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: (SurfaceHolder) -> Unit
) {
    val context = LocalContext.current
    val layoutStore = remember {
        LayoutStore(context.getSharedPreferences("layout", Context.MODE_PRIVATE))
    }
    // Отключённые комбо-кнопки (ключи) живут в раскладке: ComboStore.effective()
    // читает их через провайдер — на поверхности показываются/нажимаются только
    // включённые кнопки, при переключении в конфигураторе набор обновляется сам
    ComboStore.disabledProvider = { layoutStore.combosOff }
    val editing = screen == Screen.EDIT

    // Какой блок редактируется (выделяется касанием, масштаб — слайдером/щипком).
    // BlockRef: обычная группа ИЛИ комбо-группа №i — комбо-кнопок теперь несколько
    var editTarget by remember { mutableStateOf(BlockRef(LayoutStore.GroupId.DPAD)) }

    // Локальный режим «2 игрока» — второй джойстик на том же экране
    val twoLocal by SettingsStore.twoLocal.collectAsState()
    // Реактивное состояние звука для кнопки Mute (иконка меняется на ходу,
    // в т.ч. если звук выключили через «Настройки»)
    val soundOn by SettingsStore.sound.collectAsState()
    // Скин «корпуса» (гаджет-дизайн): живой — корпус появляется/исчезает
    // без перезапуска игры; при первом включении «Гаджета» один раз
    // применяется его пресет раскладки (флаг в prefs — повторные
    // включения и перемещения блоков не перекрываются)
    val deviceSkinId by SettingsStore.deviceSkin.collectAsState()
    val gadgetSkinActive = DeviceSkins.byId(deviceSkinId).enabled
    LaunchedEffect(gadgetSkinActive) {
        if (gadgetSkinActive) layoutStore.applyGadgetPresetOnce()
    }
    LaunchedEffect(twoLocal) {
        // при первом включении — один раз расставить P1 слева / P2 справа
        if (twoLocal) layoutStore.applyTwoPresetOnce()
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Игровая поверхность (кадры рисует EmulatorEngine)
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = onSurfaceCreated(h)
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) =
                            onSurfaceCreated(h)
                        override fun surfaceDestroyed(h: SurfaceHolder) = onSurfaceDestroyed(h)
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Слой корпуса (гаджет-скин): рисуется ВЫШЕ игровой поверхности
        // (закрывает только чёрные поля letterbox — «окно» вырезается ровно
        // по кадру) и НИЖЕ меню/крестовины/кнопок. Без pointerInput —
        // касания проходят сквозь слой: роутинг, зоны нажатий и перемещаемость
        // групп не меняются. Дефолт (skin "none") слой не рисует вовсе.
        if (started && screen in setOf(Screen.GAME, Screen.EDIT, Screen.PAUSE)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val frame = engine.frameRect(constraints.maxWidth, constraints.maxHeight)
                DeviceShellLayer(frame, DeviceSkins.byId(deviceSkinId))
            }
        }

        if (started && screen == Screen.GAME) {
            val netLocked = engine.isNetActive()
            // Порт СВОЕЙ стороны в сетевой игре читаем В МОМЕНТ СОБЫТИЯ, а не
            // при композиции: pointerInput-корутины не пересоздаются при
            // рекомпозиции и держат захваченные «на старте» лямбды. Порт,
            // захваченный значением, приклеивался навсегда — у гостя он мог
            // оказаться 0 вместо 1 (гонка с attachNet), и нажатия
            // «игнорировались». Лямбда ниже всегда читает актуальное состояние.
            val myPort = { if (engine.isNetActive()) engine.netLocalPort() else 0 }
            // Верхняя панель: слева пауза и Mute, справа квик-сейв/лоад и настройки управления
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIcon(Icons.Filled.Pause, "Пауза") { onScreen(Screen.PAUSE) }
                    CircleIcon(
                        if (soundOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        if (soundOn) "Выключить звук" else "Включить звук"
                    ) {
                        val v = !soundOn
                        SettingsStore.setSound(v)
                        onSoundChange(v)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircleIcon(Icons.Filled.Tune, "Настройки управления") { onScreen(Screen.EDIT) }
                    CircleIcon(Icons.Filled.Save, "Квик-сейв") {
                        if (netLocked) toast("В сетевой игре сейвы недоступны")
                        else engine.saveSlot(SaveManager.QUICK) { ok ->
                            toast(if (ok) "Сохранено" else "Ошибка сохранения")
                        }
                    }
                    CircleIcon(Icons.Filled.FolderOpen, "Квик-лоад") {
                        if (netLocked) toast("В сетевой игре сейвы недоступны")
                        else engine.loadSlot(SaveManager.QUICK) { ok ->
                            toast(if (ok) "Загружено" else "Сейв не найден")
                        }
                    }
                }
            }

            // Джойстик P1 (в сетевой игре — джойстик своей стороны)
            DpadLayer(
                store = layoutStore,
                editing = false,
                enabled = true,
                onBits = { InputState.setDirs(it, myPort()) }
            )
            ControlsLayer(store = layoutStore, editing = false, inputPort = myPort())

            // Исполнитель программируемых комбо-кнопок: нажатие комбо-кнопки
            // (маршрутизация та же, что у A/B — GamepadInput) запускает
            // проигрывание последовательности. Scope привязан к игровому
            // режиму: пауза отменяет проигрывание, отмена ГАРАНТИРОВАННО
            // отжимает нажатые биты (см. ComboRunner.play)
            val comboScope = rememberCoroutineScope()
            val comboRunner = remember { ComboRunner(comboScope) { ComboStore.effective() } }
            DisposableEffect(Unit) {
                GamepadInput.onComboPress = { id, down, port ->
                    if (down) comboRunner.start(id, port)
                }
                onDispose {
                    GamepadInput.onComboPress = null
                    comboRunner.cancelAll()
                }
            }

            // Кнопки корпуса гаджета (SAVE/LOAD в группе META): квик-сейв/лоад
            // тем же слотом, что и кнопки верхней панели. Сетевая игра — сейвы
            // недоступны (как в меню). Состояние читаем НА МОМЕНТ НАЖАТИЯ
            DisposableEffect(Unit) {
                GamepadInput.onSystemPress = { id, down ->
                    if (down) {
                        if (engine.isNetActive()) {
                            toast("В сетевой игре сейвы недоступны")
                        } else if (id == "SAVE") {
                            engine.saveSlot(SaveManager.QUICK) { ok ->
                                toast(if (ok) "Сохранено" else "Ошибка сохранения")
                            }
                        } else {
                            engine.loadSlot(SaveManager.QUICK) { ok ->
                                toast(if (ok) "Загружено" else "Сейв не найден")
                            }
                        }
                    }
                }
                onDispose { GamepadInput.onSystemPress = null }
            }

            // Второй джойстик для локальной игры на двоих (по сети он не нужен —
            // второй игрок играет со своего телефона)
            if (twoLocal && !netLocked) {
                DpadLayer(
                    store = layoutStore,
                    editing = false,
                    enabled = true,
                    onBits = { InputState.setDirs(it, 1) },
                    p2Style = true
                )
                ControlsLayer(store = layoutStore, editing = false, inputPort = 1, p2Style = true)
            }
        }

        // Редактор управления: игра на паузе; тяните блок или пустое место —
        // двигается выделенный блок; щипок/слайдер — масштаб.
        // Панель редактора лежит ровно на области игрового изображения, поэтому
        // блоки по краям (крестовина, A/B) и центр-низ (Select/Start) не
        // перекрываются ею.
        if (started && editing) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                // Прямоугольник игрового кадра — та же математика, что в движке
                val frame = engine.frameRect(constraints.maxWidth, constraints.maxHeight)
                EditorSurface(store = layoutStore, selected = editTarget)
                DpadLayer(
                    store = layoutStore,
                    editing = true,
                    selected = editTarget.gid,
                    onSelect = { editTarget = BlockRef(LayoutStore.GroupId.DPAD) },
                    enabled = false,
                    onBits = {}
                )
                ControlsLayer(
                    store = layoutStore,
                    editing = true,
                    selected = editTarget,
                    onSelect = { editTarget = it }
                )
                if (twoLocal) {
                    DpadLayer(
                        store = layoutStore,
                        editing = true,
                        selected = editTarget.gid,
                        onSelect = { editTarget = BlockRef(LayoutStore.GroupId.DPAD2) },
                        enabled = false,
                        onBits = {},
                        p2Style = true
                    )
                    ControlsLayer(
                        store = layoutStore,
                        editing = true,
                        selected = editTarget,
                        onSelect = { editTarget = it },
                        p2Style = true
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(frame.left.toInt(), frame.top.toInt()) }
                        .width(with(density) { frame.width().toDp() })
                ) {
                    EditBar(
                        store = layoutStore,
                        selected = editTarget,
                        onSelect = { editTarget = it },
                        onDone = { onScreen(Screen.GAME) },
                        showP2 = twoLocal
                    )
                }
            }
        }

        when (screen) {
            Screen.PAUSE -> PauseOverlay(
                onResume = { onScreen(Screen.GAME) },
                onQuickSave = {
                    engine.saveSlot(SaveManager.QUICK) { ok ->
                        toast(if (ok) "Сохранено" else "Ошибка сохранения")
                    }
                    onScreen(Screen.GAME)
                },
                onQuickLoad = {
                    engine.loadSlot(SaveManager.QUICK) { ok ->
                        toast(if (ok) "Загружено" else "Сейв не найден")
                    }
                    onScreen(Screen.GAME)
                },
                onReset = {
                    engine.resetGame { ok ->
                        toast(if (ok) "Игра начата заново" else "Сброс не удался")
                    }
                    onScreen(Screen.GAME)
                },
                onSlots = { onScreen(Screen.SLOTS) },
                onCheats = { onScreen(Screen.CHEATS) },
                onCombos = { onScreen(Screen.COMBOS) },
                onEdit = { onScreen(Screen.EDIT) },
                onSettings = { onScreen(Screen.SETTINGS) },
                onNet = {
                    InputState.clearAll()
                    if (engine.isNetActive()) {
                        // Повторный вход в меню сети во время сетевой игры =
                        // отключение: продолжаем соло, пир станет на паузу
                        engine.disconnectNet()
                        onScreen(Screen.GAME)
                        engine.setPaused(false)
                        toast("Сетевая игра завершена")
                    } else {
                        engine.setPaused(true)
                        onScreen(Screen.NET)
                    }
                },
                onExit = onExit,
                exitLabel = exitLabel,
                // Сетевой режим скрывается для одиночных игр (флаг netplay
                // в конфиге игры); сам netLocked тогда невозможен
                netEnabled = netplayEnabled,
                netLocked = engine.isNetActive(),
                onNetBlocked = { toast("В сетевой игре это недоступно") }
            )

            Screen.NET -> NetPanel(
                engine = engine,
                onBack = { onScreen(Screen.PAUSE) },
                onStarted = {
                    onScreen(Screen.GAME)
                    engine.setPaused(false)
                },
                onDisconnect = { msg ->
                    toast(msg)
                    engine.detachNet()
                    engine.setPaused(true)
                    onScreen(Screen.PAUSE)
                }
            )

            Screen.SLOTS -> SlotsPanel(
                saves = engine.saves,
                onSave = { n ->
                    engine.saveSlot(n) { ok -> toast(if (ok) "Сохранено в слот $n" else "Ошибка") }
                },
                onLoad = { n ->
                    engine.loadSlot(n) { ok ->
                        toast(if (ok) "Загружен слот $n" else "Ошибка")
                        if (ok) onScreen(Screen.GAME)
                    }
                },
                onDelete = { n -> engine.saves?.delete(n) },
                onBack = { onScreen(Screen.PAUSE) }
            )

            Screen.CHEATS -> cheatsRepo?.let { repo ->
                CheatsScreen(
                    repo = repo,
                    onApply = { engine.applyCheats(repo.packedActiveRam(), repo.activeGg()) },
                    onBack = { onScreen(Screen.PAUSE) }
                )
            }

            Screen.COMBOS -> ComboPanel(
                onBack = { onScreen(Screen.PAUSE) },
                layout = layoutStore
            )

            Screen.SETTINGS -> SettingsPanel(
                onBack = { onScreen(Screen.PAUSE) },
                onSoundChange = onSoundChange,
                hasDamageWatch = hasDamageWatch
            )

            else -> {}
        }
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .size(44.dp)
    ) {
        Icon(icon, desc, tint = Color.White.copy(alpha = 0.85f))
    }
}
