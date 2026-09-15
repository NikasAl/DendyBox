package com.dendybox.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dendybox.app.cheats.CheatRepository
import com.dendybox.app.emulator.EmulatorEngine
import com.dendybox.app.input.InputState
import com.dendybox.app.settings.CollectionCatalog
import com.dendybox.app.settings.GameConfig
import com.dendybox.app.settings.SettingsStore
import com.dendybox.app.ui.DendyBoxTheme
import com.dendybox.app.ui.screens.CollectionMenu
import com.dendybox.app.ui.screens.GameScreen
import com.dendybox.app.ui.screens.Screen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Immersive fullscreen: контент на весь экран, шторка с часами и
        // системная навигация скрыты (в игре они перекрывали контролы).
        // Показываются транзитом при свайпе от края и снова прячутся сами.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // В ландшафте разрешаем контенту заходить в зону выреза экрана
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        setContent {
            DendyBoxTheme {
                // Корневой Surface задаёт LocalContentColor всему приложению.
                // Без него тексты без явного цвета получают дефолтный ЧЁРНЫЙ
                // и на тёмном фоне становятся невидимыми (подписи ползунков
                // и переключателей в настройках, заголовки экранов).
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // После диалогов/сворачивания шторка может вернуться — прячем снова
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    SettingsStore.init(context)
    // Конфиг игры (roms/<flavor>.json → assets/game.json): байт урона для
    // вибрации при уроне и видимость сетевого режима. Файл опционален;
    // читается один раз, до старта движка
    remember { GameConfig.load(context) }
    val hasDamageWatch = GameConfig.damageWatch != null
    val netplayEnabled = GameConfig.netplayEnabled

    val engine = remember { EmulatorEngine(context) }
    var started by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf(Screen.GAME) }
    var romBytes by remember { mutableStateOf<ByteArray?>(null) }
    var romMissing by remember { mutableStateOf(false) }
    var pendingSurface by remember { mutableStateOf<SurfaceHolder?>(null) }
    var cheatsRepo by remember { mutableStateOf<CheatRepository?>(null) }
    // Сборник «X игр в 1» (assets/games/): null — обычная игра (rom.nes);
    // selectedGame: -1 — меню сборника, >= 0 — выбранная игра
    val collection = remember { CollectionCatalog.load(context) }
    var selectedGame by remember { mutableStateOf(-1) }

    fun startEngineIfNeeded(h: SurfaceHolder) {
        when {
            started -> engine.attachSurface(h)
            romMissing -> startError =
                "ROM не найден в этой сборке.\n\n" +
                "Соберите приложение под нужную игру:\n" +
                "положите ROM в папку roms/ проекта и выполните\n" +
                "./scripts/build_release.sh <flavor>  (см. roms/README.md)"
            romBytes == null -> pendingSurface = h // ждём завершения чтения assets
            else -> {
                val err = engine.start(romBytes!!)
                if (err != null) {
                    startError = err
                    return
                }
                started = true
                val repo = CheatRepository(context, engine.saves!!.romHash)
                cheatsRepo = repo
                engine.applyCheats(repo.packedActiveRam(), repo.activeGg())
                engine.attachSurface(h)
                pendingSurface = null
            }
        }
    }

    // Настройки ввода/вибрации — один раз за жизнь активности
    LaunchedEffect(Unit) {
        InputState.turboHzA = SettingsStore.turboHzA.value
        InputState.turboHzB = SettingsStore.turboHzB.value
        launch { SettingsStore.turboHzA.collect { InputState.turboHzA = it } }
        launch { SettingsStore.turboHzB.collect { InputState.turboHzB = it } }
        // «Вибрация при получении урона» — единственная вибрация в приложении
        // (если игра задаёт байт урона в конфиге; иначе настройка скрыта и
        // движок всегда выключен). collect выдаёт текущее значение сразу
        launch { SettingsStore.haptics.collect { engine.setDamageWatchEnabled(it) } }
    }

    // ROM: одиночная игра — assets/rom.nes; сборник — выбранная игра из
    // assets/games/. Повторный start после stop безопасен: Native.unload
    // полностью деинициализирует ядро, сейвы привязаны к SHA-1 нового рома
    LaunchedEffect(selectedGame) {
        if (collection != null && selectedGame < 0) {
            romBytes = null // показано меню сборника — ром не нужен
            return@LaunchedEffect
        }
        try {
            romBytes = if (collection == null) {
                context.assets.open("rom.nes").use { it.readBytes() }
            } else {
                context.assets.open(collection.games[selectedGame].assetPath).use { it.readBytes() }
            }
        } catch (_: Exception) {
            romMissing = true
        }
        pendingSurface?.let { startEngineIfNeeded(it) }
    }

    // Пауза на экранах меню (при уходе в паузу пишется автосейв)
    LaunchedEffect(screen, started) {
        if (started) engine.setPaused(screen != Screen.GAME)
    }

    // Пауза при сворачивании приложения
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, started) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (started) engine.setPaused(true)
                Lifecycle.Event.ON_RESUME ->
                    if (started && screen == Screen.GAME) engine.setPaused(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(Unit) {
        onDispose { engine.stop() }
    }

    val error = startError
    if (error != null) {
        // У сборника из ошибки можно вернуться в меню (например, одна из игр
        // битая — остальные запускаются)
        val backToMenu: (() -> Unit)? =
            if (collection != null && selectedGame >= 0) {
                {
                    startError = null
                    romMissing = false
                    started = false
                    cheatsRepo = null
                    romBytes = null
                    selectedGame = -1
                }
            } else null
        ErrorScreen(error, onBack = backToMenu)
        return
    }

    // Меню сборника «X игр в 1»: движок ещё не запущен (started = false),
    // поверхность игры не создаётся, поэтому ядро стартует только после выбора
    if (collection != null && selectedGame < 0) {
        CollectionMenu(
            collectionTitle = collection.title,
            games = collection.games,
            onPick = { idx ->
                romMissing = false
                selectedGame = idx
            },
            onExit = { (context as? Activity)?.finish() }
        )
        return
    }

    GameScreen(
        engine = engine,
        started = started,
        screen = screen,
        onScreen = { screen = it },
        cheatsRepo = cheatsRepo,
        hasDamageWatch = hasDamageWatch,
        netplayEnabled = netplayEnabled,
        exitLabel = if (collection != null) "В меню сборника" else "Выход из игры",
        onSoundChange = { engine.setSound(it) },
        onExit = {
            engine.stop()
            if (collection != null) {
                // Сборник: «Выход из игры» = возврат в меню выбора. Автосейв
                // уже записан при входе в меню паузы; движок перезапустится
                // при следующем выборе игры
                started = false
                cheatsRepo = null
                screen = Screen.GAME
                selectedGame = -1
            } else {
                (context as? Activity)?.finish()
            }
        },
        onSurfaceCreated = { h -> startEngineIfNeeded(h) },
        onSurfaceDestroyed = { engine.attachSurface(null) }
    )
}

@Composable
private fun ErrorScreen(message: String, onBack: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
            if (onBack != null) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.padding(top = 24.dp)
                ) {
                    Text("В меню сборника")
                }
            }
        }
    }
}
