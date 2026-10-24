package com.dendybox.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dendybox.app.input.ComboSegment
import com.dendybox.app.input.ComboTimeline
import com.dendybox.app.settings.GameConfig
import com.dendybox.app.ui.controls.ComboButton
import com.dendybox.app.ui.controls.ComboJson
import com.dendybox.app.ui.controls.ComboStep
import com.dendybox.app.ui.controls.ComboStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Конфигуратор программируемых комбо-кнопок: пресеты текущей игры
 * (roms/<flavor>.json — только чтение) и пользовательские кнопки
 * (создание/редактирование/удаление, хранятся глобально — ComboStore).
 *
 * Шаг последовательности: набор кнопок джойстика (можно несколько —
 * нажимаются ОДНОВРЕМЕННО), пауза 0–500 мс (кратно 50) перед началом шага
 * и удержание 50–500 мс (кратно 50). Пауза отсчитывается от отпускания
 * предыдущего шага или — переключатель «Нажатия» — от его нажатия
 * (перекрывающиеся удержания). Хронология видна на шкале времени
 * (полосы «старт + длительность» — в карточках и в редакторе, где края
 * полос тянутся). Открытие из меню паузы; сами кнопки лежат на игровой
 * поверхности, их позиция/масштаб — в редакторе управления (чип
 * «Комбо-кнопки»). Старые конфиги без новых полей играются как раньше.
 */
@Composable
fun ComboPanel(onBack: () -> Unit) {
    val context = LocalContext.current
    // null — список; иначе редактируемая кнопка (id существующей или новая)
    var editorFor by remember { mutableStateOf<ComboButton?>(null) }

    val presets = GameConfig.comboPresets
    val mine = ComboStore.buttons

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Column(Modifier.weight(1f)) {
                Text("Комбо-кнопки", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Последовательности нажатий и удержаний",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = {
                editorFor = ComboButton(
                    id = ComboStore.newId(),
                    name = "",
                    steps = listOf(ComboStep("A", 0))
                )
            }) {
                Icon(Icons.Filled.Add, null, Modifier.padding(end = 4.dp))
                Text("Новая")
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (presets.isNotEmpty()) {
                Text(
                    "Пресеты игры (заданы картриджем, менять нельзя)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                presets.forEach { ComboCard(it, preset = true, onEdit = {}, onDelete = null) }
            }

            Text(
                if (presets.isEmpty()) "Мои комбо-кнопки" else "Мои кнопки",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (mine.isEmpty()) {
                Text(
                    "Пока нет своих кнопок. Нажмите «Новая»: задайте имя и шаги — " +
                        "какие кнопки нажать (можно несколько сразу), сколько держать " +
                        "и паузу перед шагом.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            mine.forEach { b ->
                ComboCard(
                    b,
                    preset = false,
                    onEdit = { editorFor = b },
                    onDelete = {
                        ComboStore.remove(b.id)
                        Toast.makeText(context, "«${b.name}» удалена", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            Text(
                "Кнопки лежат внизу по центру игровой поверхности. Передвинуть блок " +
                    "и поменять размер — «Настройки управления» (блок «Комбо-кнопки»). " +
                    "Повторное нажатие кнопки перезапускает комбинацию с начала.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    editorFor?.let { target ->
        ComboEditor(
            initial = target,
            onBack = { editorFor = null },
            onSave = { saved ->
                if (ComboStore.buttons.any { it.id == saved.id }) ComboStore.update(saved)
                else ComboStore.add(saved)
                editorFor = null
                Toast.makeText(context, "«${saved.name}» сохранена", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun ComboCard(
    b: ComboButton,
    preset: Boolean,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Surface(shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(b.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    ComboJson.stepsText(b.steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Хронология: когда какой набор зажат и сколько держится
                ComboTimelineView(
                    steps = b.steps,
                    interactive = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }
            if (!preset && onDelete != null) {
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Изменить") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Удалить") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Пошаговый редактор одной комбо-кнопки
// ---------------------------------------------------------------------------

/** Доступные задержки шага, мс (сетка 50). */
private val DELAYS =
    (0..ComboJson.MAX_DELAY_MS / ComboJson.GRID_MS).map { it * ComboJson.GRID_MS }

/** Доступные удержания шага, мс (сетка 50; 100 — прежний «клик»). */
private val HOLDS =
    (ComboJson.MIN_HOLD_MS / ComboJson.GRID_MS..ComboJson.MAX_HOLD_MS / ComboJson.GRID_MS)
        .map { it * ComboJson.GRID_MS }

/** Подпись набора кнопок шага: «A+B», «↓». */
private fun btnsLabel(s: ComboStep): String =
    (listOf(s.btn) + s.with).joinToString("+") { ComboJson.btnLabel(it) }

@Composable
private fun ComboEditor(
    initial: ComboButton,
    onBack: () -> Unit,
    onSave: (ComboButton) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    val steps = remember { mutableStateListOf<ComboStep>().apply { addAll(initial.steps) } }
    // какой шаг добавляет кнопку (null — не выбран; -1 — новый шаг в конце)
    var pickStep by remember { mutableStateOf<Int?>(null) }

    val valid = name.trim().isNotEmpty() && ComboJson.validSteps(steps)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Кнопка комбо", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = {
                    if (valid) onSave(ComboButton(initial.id, name.trim(), steps.toList()))
                    else Toast.makeText(
                        context, "Нужно имя и хотя бы один шаг", Toast.LENGTH_SHORT
                    ).show()
                }
            ) { Text("Сохранить") }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 16) name = it },
                label = { Text("Название на кнопке") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Хронология: полосы шагов на шкале времени — старт и удержание
            // каждого набора видны с первого взгляда; края полос тянутся
            Text(
                "Хронология",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ComboTimelineView(
                steps = steps.toList(),
                interactive = true,
                modifier = Modifier.fillMaxWidth(),
                onEdit = { i, d, h ->
                    val s = steps[i]
                    steps[i] = s.copy(delayMs = d ?: s.delayMs, holdMs = h ?: s.holdMs)
                }
            )
            Text(
                "Тяните края полосы: левый край — пауза до шага, правый — удержание. " +
                    "Точно подстроить — чипами в шагах ниже.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            steps.forEachIndexed { i, s ->
                Surface(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Шаг ${i + 1}: ${btnsLabel(s)}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium
                            )
                            IconButton(
                                onClick = { if (i > 0) steps.move(i, i - 1) },
                                enabled = i > 0
                            ) { Icon(Icons.Filled.ArrowUpward, "Выше") }
                            IconButton(
                                onClick = { if (i < steps.lastIndex) steps.move(i, i + 1) },
                                enabled = i < steps.lastIndex
                            ) { Icon(Icons.Filled.ArrowDownward, "Ниже") }
                            IconButton(
                                onClick = { steps.removeAt(i) },
                                enabled = steps.size > 1
                            ) { Icon(Icons.Filled.Close, "Убрать шаг") }
                        }
                        // Кнопки шага: мультивыбор — нажимаются одновременно
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            ComboJson.BTN_KEYS.forEach { key ->
                                val selected = key == s.btn || key in s.with
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val cur = LinkedHashSet(listOf(s.btn) + s.with)
                                        if (selected) {
                                            if (cur.size > 1) { // хотя бы одна остаётся
                                                cur.remove(key)
                                                steps[i] = s.copy(btn = cur.first(), with = cur.drop(1).toList())
                                            }
                                        } else {
                                            cur.add(key)
                                            steps[i] = s.copy(btn = cur.first(), with = cur.drop(1).toList())
                                        }
                                    },
                                    label = { Text(ComboJson.btnLabel(key)) },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        }
                        Text(
                            "Выбранные кнопки нажимаются и отпускаются одновременно",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Пауза перед началом шага
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                if (i == 0) "Задержка, мс" else "Пауза, мс",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(92.dp)
                            )
                            DELAYS.forEach { d ->
                                FilterChip(
                                    selected = s.delayMs == d,
                                    onClick = { steps[i] = s.copy(delayMs = d) },
                                    label = { Text("$d") },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        }
                        // Удержание: сколько мс набор держится нажатым
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                "Держать, мс",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(92.dp)
                            )
                            HOLDS.forEach { h ->
                                FilterChip(
                                    selected = s.holdMs == h,
                                    onClick = { steps[i] = s.copy(holdMs = h) },
                                    label = { Text("$h") },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        }
                        // Отсчёт паузы: от отпускания предыдущего шага (как раньше)
                        // или от его нажатия — перекрывающиеся удержания
                        if (i > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                Text(
                                    "Пауза от",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(92.dp)
                                )
                                FilterChip(
                                    selected = !s.fromPress,
                                    onClick = { steps[i] = s.copy(fromPress = false) },
                                    label = { Text("Отпускания") },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                FilterChip(
                                    selected = s.fromPress,
                                    onClick = { steps[i] = s.copy(fromPress = true) },
                                    label = { Text("Нажатия") },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (steps.size < ComboJson.MAX_STEPS) {
                if (pickStep == null) {
                    OutlinedButton(onClick = { pickStep = steps.size }) {
                        Icon(Icons.Filled.Add, null, Modifier.padding(end = 4.dp))
                        Text("Добавить шаг")
                    }
                } else {
                    // Выбор кнопок нового шага (можно несколько — нажмутся вместе)
                    val picked = remember { mutableStateListOf<String>() }
                    Surface(shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Какие кнопки?",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                IconButton(onClick = { pickStep = null }) {
                                    Icon(Icons.Filled.Close, "Отмена")
                                }
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ComboJson.BTN_KEYS.forEach { key ->
                                    FilterChip(
                                        selected = key in picked,
                                        onClick = {
                                            if (key in picked) picked.remove(key) else picked.add(key)
                                        },
                                        label = { Text(ComboJson.btnLabel(key)) }
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        steps.add(ComboStep(picked.first(), 0, with = picked.drop(1)))
                                        pickStep = null
                                    },
                                    enabled = picked.isNotEmpty()
                                ) { Text("Добавить шаг") }
                                Text(
                                    "несколько — нажмутся вместе",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    "Максимум ${ComboJson.MAX_STEPS} шагов",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(1.dp))
            Text(
                "Как играется: кнопки шага нажимаются одновременно и держатся " +
                    "указанное время (по умолчанию 100 мс, сетка 50 мс, максимум 500 мс). " +
                    "Пауза шага отсчитывается от отпускания предыдущего или — переключатель " +
                    "«Нажатия» — от его нажатия: так задаются удержания с перекрытием. " +
                    "Пример: A держится 500 мс; B начинается через 50 мс после нажатия A " +
                    "и держится ещё 500 мс.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Перестановка элементов списка шагов (в stdlib нет list.move). */
private fun <T> androidx.compose.runtime.snapshots.SnapshotStateList<T>.move(from: Int, to: Int) {
    val v = removeAt(from)
    add(to, v)
}

// ---------------------------------------------------------------------------
// Наглядная хронология: полосы шагов на шкале времени
// ---------------------------------------------------------------------------

/** Палитра полос хронологии — различимые цвета (цикл по числу шагов). */
private val TIMELINE_COLORS = listOf(
    0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFF00897B, 0xFFD81B60,
    0xFF6D4C41, 0xFF3949AB, 0xFF00ACC1, 0xFFC0CA33, 0xFFF4511E, 0xFF546E7A
).map { Color(it) }

private enum class TimelineDragZone { NONE, START, END }

/** Захваченное состояние перетаскивания края полосы. */
private class TimelineDrag(
    val zone: TimelineDragZone,
    val startX: Float,
    val holdMs: Long,
    val anchorMs: Long
)

/** Округление до сетки времени (полшага — в большую сторону). */
private fun snapGrid(v: Long): Long =
    ((v + ComboJson.GRID_MS / 2) / ComboJson.GRID_MS) * ComboJson.GRID_MS

/**
 * Шкала времени комбо: по полосе на каждый шаг — левый край = момент нажатия
 * набора, длина = удержание; перекрытия шагов видны с первого взгляда.
 * Слева подписи наборов кнопок, сверху — шкала с адаптивным шагом сетки.
 *
 * В редакторе ([interactive]) края полосы тянутся: левый меняет паузу шага,
 * правый — удержание (снап по сетке, границы ComboJson); шкала на время
 * перетаскивания заморожена, чтобы полоса не прыгала под пальцем.
 * В карточках ([interactive] = false) — компактный просмотр без ручек.
 */
@Composable
private fun ComboTimelineView(
    steps: List<ComboStep>,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    onEdit: ((index: Int, delayMs: Int?, holdMs: Int?) -> Unit)? = null
) {
    val segs = ComboTimeline.segments(steps)
    if (segs.isEmpty()) return

    val compact = !interactive
    val headerH = if (compact) 13.dp else 16.dp
    val rowH = if (compact) 18.dp else 26.dp
    val labelW = if (compact) 34.dp else 46.dp

    // Шкала: до конца последнего сегмента, но не короче 1 с, кратно 500 вверх.
    // Пока тянется край полосы — заморожена (см. onDragActive), иначе
    // растущая полоса меняла бы масштаб прямо под пальцем
    val liveAxis = maxOf(1000L, (segs.maxOf { it.endMs } + 499) / 500 * 500)
    var axisOverride by remember { mutableStateOf<Long?>(null) }
    var lastLiveAxis by remember { mutableStateOf(1000L) }
    SideEffect { if (axisOverride == null) lastLiveAxis = liveAxis }
    val axisMs = axisOverride ?: lastLiveAxis

    Row(modifier) {
        // Подписи наборов кнопок по строкам
        Column(Modifier.width(labelW)) {
            Spacer(Modifier.height(headerH))
            segs.forEach { seg ->
                Box(Modifier.height(rowH), contentAlignment = Alignment.CenterStart) {
                    Text(
                        seg.buttons.joinToString("+") { ComboJson.btnLabel(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(headerH + rowH * segs.size)
                .clipToBounds()
        ) {
            val axisPx = with(LocalDensity.current) { maxWidth.toPx() }
            val textMeasurer = rememberTextMeasurer()
            val tickStyle = TextStyle(
                fontSize = if (compact) 7.sp else 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
            // Сетка времени: вертикальные линии + подписи мс
            Canvas(Modifier.matchParentSize()) {
                val ppm = size.width / axisMs
                // шаг сетки: не плотнее ~34dp на клетку
                val step = listOf(50L, 100L, 250L, 500L, 1000L, 2000L)
                    .first { axisMs * 34f <= it * size.width }
                var t = 0L
                while (t <= axisMs) {
                    val x = t * ppm
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
                    // в компактном режиме подписи реже — через одну
                    if (!compact || t % (step * 2) == 0L) {
                        val lt = textMeasurer.measure("$t", tickStyle)
                        var lx = x + 2.dp.toPx()
                        if (lx + lt.size.width > size.width) {
                            lx = x - lt.size.width - 2.dp.toPx()
                        }
                        drawText(lt, topLeft = Offset(lx, 1.dp.toPx()))
                    }
                    t += step
                }
            }
            // Полосы шагов
            Column(Modifier.padding(top = headerH)) {
                segs.forEach { seg ->
                    key(seg.index) {
                        val anchor = if (seg.index == 0) 0L
                        else if (steps[seg.index].fromPress) segs[seg.index - 1].startMs
                        else segs[seg.index - 1].endMs
                        TimelineRow(
                            seg = seg,
                            axisMs = axisMs,
                            axisPx = axisPx,
                            interactive = interactive,
                            anchorMs = anchor,
                            onDragActive = { active ->
                                axisOverride = if (active) lastLiveAxis else null
                            },
                            onEdit = onEdit
                        )
                    }
                }
            }
        }
    }
}

/** Полоса одного шага: прямоугольник от старта до конца удержания. */
@Composable
private fun TimelineRow(
    seg: ComboSegment,
    axisMs: Long,
    axisPx: Float,
    interactive: Boolean,
    anchorMs: Long,
    onDragActive: (Boolean) -> Unit,
    onEdit: ((index: Int, delayMs: Int?, holdMs: Int?) -> Unit)?
) {
    val color = TIMELINE_COLORS[seg.index % TIMELINE_COLORS.size]
    val textMeasurer = rememberTextMeasurer()
    val rowH = if (interactive) 26.dp else 18.dp
    val barH = if (interactive) 18.dp else 12.dp
    val ppm = if (axisMs > 0) axisPx / axisMs else 0f
    // Свежие значения для жестов: pointerInput не перезапускается на каждый
    // рекомпозиции — читаем последние значения через rememberUpdatedState
    val latestSeg by rememberUpdatedState(seg)
    val latestPpm by rememberUpdatedState(ppm)
    val latestAnchor by rememberUpdatedState(anchorMs)
    val latestOnEdit by rememberUpdatedState(onEdit)
    val latestOnDragActive by rememberUpdatedState(onDragActive)
    var drag by remember { mutableStateOf<TimelineDrag?>(null) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(rowH)
            .then(
                if (interactive && onEdit != null) Modifier.pointerInput(seg.index) {
                    detectHorizontalDragGestures(
                        onDragStart = { off ->
                            val s = latestSeg
                            val p = latestPpm
                            val zone = when {
                                abs(off.x - s.startMs * p) <= 22.dp.toPx() ->
                                    TimelineDragZone.START
                                abs(off.x - s.endMs * p) <= 22.dp.toPx() ->
                                    TimelineDragZone.END
                                else -> TimelineDragZone.NONE
                            }
                            if (zone != TimelineDragZone.NONE) {
                                drag = TimelineDrag(
                                    zone, off.x,
                                    holdMs = s.endMs - s.startMs,
                                    anchorMs = latestAnchor
                                )
                                latestOnDragActive(true)
                            }
                        },
                        onHorizontalDrag = { ch, _ ->
                            val g = drag ?: return@detectHorizontalDragGestures
                            ch.consume()
                            val dxMs = if (latestPpm > 0f) {
                                ((ch.position.x - g.startX) / latestPpm).toLong()
                            } else 0L
                            when (g.zone) {
                                TimelineDragZone.START -> latestOnEdit?.invoke(
                                    seg.index,
                                    snapGrid(g.anchorMs + dxMs)
                                        .coerceIn(0L, ComboJson.MAX_DELAY_MS.toLong()).toInt(),
                                    null
                                )
                                TimelineDragZone.END -> latestOnEdit?.invoke(
                                    seg.index,
                                    null,
                                    snapGrid(g.holdMs + dxMs)
                                        .coerceIn(
                                            ComboJson.MIN_HOLD_MS.toLong(),
                                            ComboJson.MAX_HOLD_MS.toLong()
                                        ).toInt()
                                )
                                else -> {}
                            }
                        },
                        onDragEnd = {
                            if (drag?.zone != TimelineDragZone.NONE) latestOnDragActive(false)
                            drag = null
                        },
                        onDragCancel = {
                            if (drag?.zone != TimelineDragZone.NONE) latestOnDragActive(false)
                            drag = null
                        }
                    )
                } else Modifier
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y0 = (size.height - barH.toPx()) / 2f
            val x0 = seg.startMs * ppm
            val x1 = (seg.endMs * ppm).coerceAtMost(size.width)
            val w = max(x1 - x0, 3.dp.toPx())
            val corner = min(5.dp.toPx(), w / 2f)
            drawRoundRect(color, Offset(x0, y0), Size(w, barH.toPx()), CornerRadius(corner))
            // длительность удержания внутри полосы, если влезает
            val lt = textMeasurer.measure(
                "${seg.endMs - seg.startMs}",
                TextStyle(fontSize = if (interactive) 9.sp else 7.sp, color = Color.White)
            )
            if (w > lt.size.width + 10.dp.toPx()) {
                drawText(
                    lt,
                    topLeft = Offset(
                        x0 + (w - lt.size.width) / 2f,
                        y0 + (barH.toPx() - lt.size.height) / 2f
                    )
                )
            }
            if (interactive) {
                // ручки краёв: белая точка в цветном кружке
                val r = 4.dp.toPx()
                val cy = y0 + barH.toPx() / 2f
                val hx0 = x0.coerceAtLeast(r)
                val hx1 = (x0 + w).coerceAtMost(size.width - r)
                drawCircle(Color.White, radius = r, center = Offset(hx0, cy))
                drawCircle(Color.White, radius = r, center = Offset(hx1, cy))
                drawCircle(color, radius = r - 1.dp.toPx(), center = Offset(hx0, cy))
                drawCircle(color, radius = r - 1.dp.toPx(), center = Offset(hx1, cy))
            }
        }
    }
}
