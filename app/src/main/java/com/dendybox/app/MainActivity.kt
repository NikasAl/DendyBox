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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.dendybox.app.settings.SettingsStore
import com.dendybox.app.ui.DendyBoxTheme
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
        setContent { DendyBoxTheme { AppRoot() } }
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

    val engine = remember { EmulatorEngine(context) }
    var started by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf(Screen.GAME) }
    var romBytes by remember { mutableStateOf<ByteArray?>(null) }
    var romMissing by remember { mutableStateOf(false) }
    var pendingSurface by remember { mutableStateOf<SurfaceHolder?>(null) }
    var cheatsRepo by remember { mutableStateOf<CheatRepository?>(null) }

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

    // ROM из assets + связка настроек с вводом
    LaunchedEffect(Unit) {
        try {
            romBytes = context.assets.open("rom.nes").use { it.readBytes() }
        } catch (_: Exception) {
            romMissing = true
        }
        pendingSurface?.let { startEngineIfNeeded(it) }
        InputState.turboHzA = SettingsStore.turboHzA.value
        InputState.turboHzB = SettingsStore.turboHzB.value
        launch { SettingsStore.turboHzA.collect { InputState.turboHzA = it } }
        launch { SettingsStore.turboHzB.collect { InputState.turboHzB = it } }
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
        ErrorScreen(error)
        return
    }

    GameScreen(
        engine = engine,
        started = started,
        screen = screen,
        onScreen = { screen = it },
        cheatsRepo = cheatsRepo,
        onSoundChange = { engine.setSound(it) },
        onExit = {
            engine.stop()
            (context as? Activity)?.finish()
        },
        onSurfaceCreated = { h -> startEngineIfNeeded(h) },
        onSurfaceDestroyed = { engine.attachSurface(null) }
    )
}

@Composable
private fun ErrorScreen(message: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(32.dp)) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
        }
    }
}
