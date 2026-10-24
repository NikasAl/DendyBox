package com.dendybox.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dendybox.app.settings.GameConfig
import com.dendybox.app.ui.controls.ComboButton
import com.dendybox.app.ui.controls.ComboJson
import com.dendybox.app.ui.controls.ComboStep
import com.dendybox.app.ui.controls.ComboStore

/**
 * Конфигуратор программируемых комбо-кнопок: пресеты текущей игры
 * (roms/<flavor>.json — только чтение) и пользовательские кнопки
 * (создание/редактирование/удаление, хранятся глобально — ComboStore).
 *
 * Шаг последовательности: набор кнопок джойстика (можно несколько —
 * нажимаются ОДНОВРЕМЕННО), пауза 0–500 мс (кратно 100) перед началом шага
 * и удержание 100–2000 мс (кратно 100). Пауза отсчитывается от отпускания
 * предыдущего шага или — переключатель «Нажатия» — от его нажатия
 * (перекрывающиеся удержания). Открытие из меню паузы; сами кнопки лежат
 * на игровой поверхности, их позиция/масштаб — в редакторе управления
 * (чип «Комбо-кнопки»). Старые конфиги без новых полей играются как раньше.
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

/** Доступные задержки шага, мс (шаг сетки 100). */
private val DELAYS = listOf(0, 100, 200, 300, 400, 500)

/** Доступные удержания шага, мс (шаг сетки 100; 100 — прежний «клик»). */
private val HOLDS = (ComboJson.MIN_HOLD_MS / 100..ComboJson.MAX_HOLD_MS / 100).map { it * 100 }

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
                    "указанное время (по умолчанию 100 мс). Пауза шага отсчитывается " +
                    "от отпускания предыдущего или — переключатель «Нажатия» — от его " +
                    "нажатия: так задаются удержания с перекрытием. Пример: A держится " +
                    "1000 мс; B начинается через 100 мс после нажатия A и держится ещё " +
                    "1000 мс.",
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
