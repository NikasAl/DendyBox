package com.dendybox.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.dendybox.app.settings.CollectionEntry

/**
 * Меню сборника «X игр в 1» — стилизовано под экран выбора игры пиратских
 * мультикартов Денди: чёрный экран, двойная красно-жёлтая рамка, шахматные
 * полосы, моноширинный список «01. ИГРА».
 *
 * Показывается вместо игрового экрана, пока игра не выбрана; тап по строке
 * запускает игру (onPick), «Выход» закрывает приложение.
 */

// Палитра в духе NES: красный, жёлтый, белый, серый
private val NesRed = Color(0xFFD82800)
private val NesDarkRed = Color(0xFFA81000)
private val NesYellow = Color(0xFFF8B800)
private val NesWhite = Color(0xFFFCFCFC)
private val NesGray = Color(0xFF9A9A9A)

/** «2 ИГРЫ В 1», «5 ИГР В 1», «1 ИГРА В 1» — с правильным склонением. */
private fun gamesInOne(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val word = when {
        m10 == 1 && m100 != 11 -> "ИГРА"
        m10 in 2..4 && (m100 < 12 || m100 > 14) -> "ИГРЫ"
        else -> "ИГР"
    }
    return "$n $word В 1"
}

@Composable
fun CollectionMenu(
    collectionTitle: String?,
    games: List<CollectionEntry>,
    onPick: (Int) -> Unit,
    onExit: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            CheckerStripe()
            // Двойная рамка: внешняя красная, внутренняя тёмно-красная — как на
            // пиратских мультикартах
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 6.dp)
                    .border(3.dp, NesRed, RoundedCornerShape(6.dp))
                    .padding(3.dp)
                    .border(1.dp, NesDarkRed, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = gamesInOne(games.size),
                        color = NesYellow,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (!collectionTitle.isNullOrBlank()) {
                        Text(
                            text = collectionTitle,
                            color = NesWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                        )
                    } else {
                        Box(Modifier.height(6.dp))
                    }

                    // Список игр; у коротких списков строки занимают больше
                    // места (weight), у длинных — включается прокрутка
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        itemsIndexed(games) { _, game ->
                            GameRow(game = game, onClick = { onPick(game.index) })
                        }
                    }
                }
            }
            CheckerStripe()

            Text(
                text = "Нажмите на игру, чтобы начать",
                color = NesGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            TextButton(
                onClick = onExit,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    "Выход",
                    color = NesGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun GameRow(game: CollectionEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NesDarkRed.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "%02d.".format(game.index + 1),
            color = NesYellow,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Text(
            text = game.title,
            color = NesWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f)
        )
        // «курсор» справа — намёк, что строка нажимается
        Text(
            text = "\u25B6",
            color = NesYellow,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp
        )
    }
}

/** Декоративная шахматная полоса (красные/жёлтые квадраты) — «шапка» мультикарта. */
@Composable
private fun CheckerStripe() {
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val cells = 32
        val cellW = size.width / cells
        for (i in 0 until cells) {
            drawRect(
                color = if (i % 2 == 0) NesRed else NesYellow,
                topLeft = Offset(i * cellW, 0f),
                size = Size(cellW, size.height)
            )
        }
    }
}
