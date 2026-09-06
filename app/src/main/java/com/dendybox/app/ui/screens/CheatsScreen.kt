package com.dendybox.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.dendybox.app.cheats.CheatRepository

@Composable
fun CheatsScreen(
    repo: CheatRepository,
    onApply: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var importInfo by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun apply() = onApply()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val (parsed, skipped) = repo.importText(text)
                importInfo = parsed to skipped
                apply()
            } catch (_: Exception) {
                Toast.makeText(context, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)
                    ?.bufferedWriter()?.use { it.write(repo.exportText()) }
                Toast.makeText(context, "Экспортировано", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Не удалось записать файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Читы", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (repo.cheats.isNotEmpty()) {
                IconButton(onClick = { exportLauncher.launch("cheats.cht") }) {
                    Icon(Icons.Filled.Download, "Экспорт .cht")
                }
            }
            IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.Upload, "Импорт .cht")
            }
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "Добавить") }
        }

        if (repo.cheats.isEmpty()) {
            Text(
                "Читов нет.\n\n" +
                    "Найдите читы в FCEUX на компьютере (Cheats → Cheat Search, затем добавьте их в список) и экспортируйте файл:\n" +
                    "Cheats → выбрать читы → Export → файл .cht.\n\n" +
                    "Затем здесь нажмите «Импорт» и выберите файл. Поддерживается также ручной ввод вида 0738:09 и коды Game Genie (SXIOPO).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(repo.cheats) { index, cheat ->
                Surface(shape = MaterialTheme.shapes.medium) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = cheat.title.ifBlank { "Чит" },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = describe(cheat),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cheat.enabled,
                            onCheckedChange = { repo.setEnabled(index, it); apply() }
                        )
                        IconButton(onClick = { repo.remove(index); apply() }) {
                            Icon(Icons.Filled.Delete, "Удалить")
                        }
                    }
                }
            }
        }
    }

    // Диалог добавления
    if (showAdd) {
        var code by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Добавить чит") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("0738:09 или SXIOPO") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название (необязательно)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (repo.addFromText(code, title)) {
                        apply()
                        showAdd = false
                    } else {
                        Toast.makeText(context, "Не похоже на чит: попробуйте 0738:09 или код из 6 букв", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Отмена") }
            }
        )
    }

    // Результат импорта
    importInfo?.let { (parsed, skipped) ->
        AlertDialog(
            onDismissRequest = { importInfo = null },
            title = { Text(if (parsed >= 0) "Импорт завершён" else "Ошибка") },
            text = {
                Text(
                    if (parsed >= 0) "Распознано читов: $parsed\nПропущено нераспознанных строк: $skipped"
                    else "Не удалось прочитать файл"
                )
            },
            confirmButton = {
                TextButton(onClick = { importInfo = null }) { Text("OK") }
            }
        )
    }
}

private fun describe(cheat: com.dendybox.app.cheats.Cheat): String {
    return if (cheat.ggCode != null) {
        val decoded = CheatRepository.decodeGG(cheat.ggCode!!)
        val ggInfo = decoded?.let { (a, v) -> " → адрес %04X, значение %02X".format(a, v) } ?: ""
        "Game Genie: ${cheat.ggCode}$ggInfo"
    } else {
        val cmp = if (cheat.cmp >= 0) " (если = %02X)".format(cheat.cmp) else ""
        "0x%04X = 0x%02X%s".format(cheat.addr, cheat.value, cmp)
    }
}
