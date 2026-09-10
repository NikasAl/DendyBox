package com.dendybox.app.ui.screens

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dendybox.app.saves.SaveManager
import com.dendybox.app.settings.SettingsStore
import com.dendybox.app.ui.controls.LayoutStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

@Composable
private fun MenuButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(text)
    }
}

// ---------------------------------------------------------------------------
// Меню паузы
// ---------------------------------------------------------------------------

@Composable
fun PauseOverlay(
    onResume: () -> Unit,
    onQuickSave: () -> Unit,
    onQuickLoad: () -> Unit,
    onSlots: () -> Unit,
    onCheats: () -> Unit,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
    onNet: () -> Unit,
    onExit: () -> Unit,
    netLocked: Boolean = false,
    onNetBlocked: () -> Unit = {}
) {
    // В сетевой игре сейвы и читы разрушили бы синхронизацию — недоступны
    fun guarded(action: () -> Unit): () -> Unit =
        if (netLocked) onNetBlocked else action
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
                    .width(300.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Пауза", style = MaterialTheme.typography.headlineSmall)
                MenuButton("Продолжить", onResume)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MenuButton("Квик-сейв", guarded(onQuickSave), Modifier.weight(1f))
                    MenuButton("Квик-лоад", guarded(onQuickLoad), Modifier.weight(1f))
                }
                MenuButton("Слоты сохранений", guarded(onSlots))
                MenuButton("Читы", guarded(onCheats))
                MenuButton("Настройки управления", onEdit)
                MenuButton("Настройки", onSettings)
                MenuButton(
                    if (netLocked) "Отключить сетевую игру" else "Игра по сети (2 игрока)",
                    onNet
                )
                OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                    Text("Выход из игры")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Слоты сохранений
// ---------------------------------------------------------------------------

@Composable
fun SlotsPanel(
    saves: SaveManager?,
    onSave: (Int) -> Unit,
    onLoad: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onBack: () -> Unit
) {
    var tick by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
            }
            Text("Слоты сохранений", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SlotRow(
                    n = SaveManager.QUICK,
                    title = "Квик-слот",
                    saves = saves,
                    tick = tick,
                    onSave = { onSave(SaveManager.QUICK); tick++ },
                    onLoad = { onLoad(SaveManager.QUICK) },
                    onDelete = { onDelete(SaveManager.QUICK); tick++ }
                )
            }
            items((SaveManager.FIRST_SLOT..SaveManager.LAST_SLOT).toList()) { n ->
                SlotRow(
                    n = n,
                    title = "Слот $n",
                    saves = saves,
                    tick = tick,
                    onSave = { onSave(n); tick++ },
                    onLoad = { onLoad(n) },
                    onDelete = { onDelete(n); tick++ }
                )
            }
        }
    }
}

@Composable
private fun SlotRow(
    n: Int,
    title: String,
    saves: SaveManager?,
    tick: Int,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    val exists = saves?.exists(n) == true
    val thumb = remember(n, tick, saves) { saves?.thumb(n) }

    Surface(shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(width = 64.dp, height = 60.dp),
                    contentScale = ContentScale.FillBounds
                )
            } else {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 60.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.VideogameAsset,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (exists) dateFmt.format(Date(saves!!.modifiedAt(n))) else "пусто",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onSave) { Icon(Icons.Filled.Save, "Сохранить") }
            IconButton(onClick = onLoad, enabled = exists) { Icon(Icons.Filled.PlayArrow, "Загрузить") }
            IconButton(onClick = onDelete, enabled = exists) { Icon(Icons.Filled.Delete, "Удалить") }
        }
    }
}

// ---------------------------------------------------------------------------
// Настройки
// ---------------------------------------------------------------------------

@Composable
fun SettingsPanel(onBack: () -> Unit, onSoundChange: (Boolean) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Настройки", style = MaterialTheme.typography.titleLarge)
        }

        val turboA by SettingsStore.turboHzA.collectAsState()
        val turboB by SettingsStore.turboHzB.collectAsState()
        val sound by SettingsStore.sound.collectAsState()
        val haptics by SettingsStore.haptics.collectAsState()
        val dpadSize by SettingsStore.dpadSize.collectAsState()
        val opacity by SettingsStore.controlsOpacity.collectAsState()
        val twoLocal by SettingsStore.twoLocal.collectAsState()

        SettingSlider(
            "Турбо-кнопка A′ — частота автоповтора: %d Гц".format(turboA.toInt()),
            turboA, 5f, 30f
        ) { SettingsStore.setTurboHzA(it) }
        SettingSlider(
            "Турбо-кнопка B′ — частота автоповтора: %d Гц".format(turboB.toInt()),
            turboB, 5f, 30f
        ) { SettingsStore.setTurboHzB(it) }
        SettingSlider(
            "Размер крестовины: %d dp".format(dpadSize.toInt()),
            dpadSize, 44f, 80f
        ) { SettingsStore.setDpadSize(it) }
        SettingSlider(
            "Прозрачность кнопок: %d%%".format((opacity * 100).toInt()),
            opacity, 0.2f, 0.9f
        ) { SettingsStore.setControlsOpacity(it) }

        SettingSwitch("Звук", sound) { SettingsStore.setSound(it); onSoundChange(it) }
        SettingSwitch("Вибро-отклик крестовины", haptics) { SettingsStore.setHaptics(it) }
        SettingSwitch(
            "2 игрока на одном экране",
            twoLocal
        ) { SettingsStore.setTwoLocal(it) }
        Text(
            "Режим «2 игрока»: на экране появляется второй джойстик (P2 справа). " +
                "Позиции подстроятся один раз автоматически, дальше их можно " +
                "поменять в редакторе управления. Для игры по сети этот режим не нужен.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    from: Float,
    to: Float,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, valueRange = from..to, onValueChange = onChange)
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------------
// Компактная панель редактора управления: лежит ровно на области игрового
// изображения (позицию/ширину задаёт GameScreen), три плотных строки —
// заголовок с кнопками, выбор блока, масштаб. Кнопки управления на экране
// остаются видимыми.
// ---------------------------------------------------------------------------

@Composable
fun EditBar(
    store: LayoutStore,
    selected: LayoutStore.GroupId,
    onSelectGroup: (LayoutStore.GroupId) -> Unit,
    onDone: () -> Unit,
    showP2: Boolean = false
) {
    val context = LocalContext.current
    val g = store.group(selected)

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // Экспорт/импорт раскладки в JSON (SAF): перенести настройку между сборками
    // или превратить её в заводской дефолт (scripts/layout_to_defaults.py)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(store.toJson().toByteArray(Charsets.UTF_8))
            } ?: error("нет потока")
            toast("Раскладка сохранена")
        } catch (_: Exception) {
            toast("Не удалось сохранить файл")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { inp ->
                inp.readBytes().toString(Charsets.UTF_8)
            }
            if (text != null && store.applyJson(text)) {
                toast("Раскладка применена")
            } else {
                toast("Это не файл раскладки DendyBox")
            }
        } catch (_: Exception) {
            toast("Не удалось прочитать файл")
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Настройки управления",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { exportLauncher.launch("dendybox_layout.json") }) {
                    Text("Экспорт")
                }
                TextButton(onClick = {
                    importLauncher.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream")
                    )
                }) {
                    Text("Импорт")
                }
                TextButton(onClick = { store.resetAll() }) { Text("Сбросить") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onDone) { Text("Готово") }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                val chipIds = if (showP2) LayoutStore.GroupId.entries else listOf(
                    LayoutStore.GroupId.DPAD,
                    LayoutStore.GroupId.AB,
                    LayoutStore.GroupId.META
                )
                chipIds.forEach { gid ->
                    FilterChip(
                        selected = gid == selected,
                        onClick = { onSelectGroup(gid) },
                        label = {
                            Text(LayoutStore.title(gid), style = MaterialTheme.typography.labelLarge)
                        }
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "«%s» %d%%".format(LayoutStore.title(selected), (g.scale * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = g.scale,
                    valueRange = LayoutStore.MIN_SCALE..LayoutStore.MAX_SCALE,
                    onValueChange = { store.updateGroup(selected, g.copy(scale = it)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
