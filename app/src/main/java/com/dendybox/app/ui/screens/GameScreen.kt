package com.dendybox.app.ui.screens

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dendybox.app.cheats.CheatRepository
import com.dendybox.app.emulator.EmulatorEngine
import com.dendybox.app.input.InputState
import com.dendybox.app.saves.SaveManager
import com.dendybox.app.ui.controls.ControlsLayer
import com.dendybox.app.ui.controls.FloatingDPad
import com.dendybox.app.ui.controls.LayoutStore

enum class Screen { GAME, PAUSE, SLOTS, CHEATS, SETTINGS, EDIT }

@Composable
fun GameScreen(
    engine: EmulatorEngine,
    started: Boolean,
    screen: Screen,
    onScreen: (Screen) -> Unit,
    cheatsRepo: CheatRepository?,
    onSoundChange: (Boolean) -> Unit,
    onExit: () -> Unit,
    onSurfaceCreated: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: (SurfaceHolder) -> Unit
) {
    val context = LocalContext.current
    val layoutStore = remember {
        LayoutStore(context.getSharedPreferences("layout", Context.MODE_PRIVATE))
    }
    val editing = screen == Screen.EDIT

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

        if (started && screen == Screen.GAME) {
            // Верхняя панель: пауза слева, квик-сейв/лоад справа
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIcon(Icons.Filled.Pause, "Пауза") { onScreen(Screen.PAUSE) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircleIcon(Icons.Filled.Save, "Квик-сейв") {
                        engine.saveSlot(SaveManager.QUICK) { ok ->
                            toast(if (ok) "Сохранено" else "Ошибка сохранения")
                        }
                    }
                    CircleIcon(Icons.Filled.FolderOpen, "Квик-лоад") {
                        engine.loadSlot(SaveManager.QUICK) { ok ->
                            toast(if (ok) "Загружено" else "Сейв не найден")
                        }
                    }
                }
            }

            FloatingDPad(enabled = true, onBits = { InputState.setDirs(it) })
            ControlsLayer(store = layoutStore, editing = false)
        }

        // Режим редактирования раскладки: игра на паузе, двигается вся группа кнопок
        if (started && editing) {
            ControlsLayer(store = layoutStore, editing = true)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                EditBar(
                    store = layoutStore,
                    onDone = { onScreen(Screen.GAME) }
                )
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
                onSlots = { onScreen(Screen.SLOTS) },
                onCheats = { onScreen(Screen.CHEATS) },
                onEdit = { onScreen(Screen.EDIT) },
                onSettings = { onScreen(Screen.SETTINGS) },
                onExit = onExit
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

            Screen.SETTINGS -> SettingsPanel(
                onBack = { onScreen(Screen.PAUSE) },
                onSoundChange = onSoundChange
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
