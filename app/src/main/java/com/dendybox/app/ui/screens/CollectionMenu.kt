package com.dendybox.app.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dendybox.app.settings.CollectionEntry

/**
 * Меню сборника «X игр в 1» — страница выбора игры поверх постера сборника.
 *
 * Фон: metadata/<flavor>/background.png|jpg|webp пережимается сборкой в
 * assets/games/background.webp (имя файла приходит в манифесте games.json)
 * и рисуется на весь экран. Постера нет — остаётся чёрный фон.
 *
 * Поверх постера: шапка («N ИГР В 1» + название), тонкая линия, компактные
 * «плашки» игр (тёмное затенение под надписями — читабельно на любом арте),
 * снова линия, внизу подсказка и «Выход». Секции стянуты в один блок по ширине
 * самого широкого элемента (IntrinsicSize.Max): линии-разделители и всё
 * содержимое имеют общую ширину, а не растягиваются на весь экран.
 *
 * Выравнивание всех блоков по горизонтали — menuAlign из манифеста
 * (left/center/right; в games.json сборника, по умолчанию center): под
 * постер, у которого композиция смещена к краю.
 */

private val NesYellow = Color(0xFFF8B800)

/** Строковое выравнивание из манифеста → горизонтальное выравнивание Compose. */
private fun hAlign(menuAlign: String?): Alignment.Horizontal = when (menuAlign) {
    "left" -> Alignment.Start
    "right" -> Alignment.End
    else -> Alignment.CenterHorizontally
}

private fun tAlign(h: Alignment.Horizontal): TextAlign = when (h) {
    Alignment.Start -> TextAlign.Start
    Alignment.End -> TextAlign.End
    else -> TextAlign.Center
}

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
    background: String?, // assets-путь постера (null/битый — чёрный фон)
    menuAlign: String?,  // left | center | right (из манифеста сборника)
    games: List<CollectionEntry>,
    onPick: (Int) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var poster by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(background) {
        poster = background?.let { decodePoster(context, it) }
    }

    val density = LocalDensity.current
    // «Затенение» текста — мягкая тень, чтобы надписи не терялись на пёстром арте
    val shadowStyle = TextStyle(
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.85f),
            offset = Offset(0f, with(density) { 1.5.dp.toPx() }),
            blurRadius = with(density) { 6.dp.toPx() }
        )
    )
    // Плашки игр — ширина по содержимому, но длинные названия должны
    // обрезаться: ширина экрана минус горизонтальные поля Column
    val itemMaxWidth = LocalConfiguration.current.screenWidthDp.dp - 48.dp
    val h = hAlign(menuAlign)
    val t = tAlign(h)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        poster?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Мягкое затемнение верха и низа: шапка и подсказка читаются,
            // центр постера остаётся открытым
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.65f),
                            0.20f to Color.Black.copy(alpha = 0.05f),
                            0.62f to Color.Black.copy(alpha = 0.05f),
                            1f to Color.Black.copy(alpha = 0.75f)
                        )
                    )
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = h
        ) {
            // ---- Содержимое, стянутое по ширине самого широкого блока ----
            // Ширина = максимум из ширины шапки, плашек и футера (ограничено
            // шириной экрана минус поля): разделители получаются ровно по блокам.
            Column(
                Modifier.width(IntrinsicSize.Max),
                horizontalAlignment = h
            ) {
                // ---- Шапка: «N ИГР В 1» + название сборника ----
                if (collectionTitle.isNullOrBlank()) {
                    Text(
                        text = gamesInOne(games.size),
                        color = NesYellow,
                        style = shadowStyle,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        textAlign = t,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = gamesInOne(games.size),
                        color = NesYellow,
                        style = shadowStyle,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp,
                        textAlign = t,
                        maxLines = 1
                    )
                    Text(
                        text = collectionTitle,
                        color = Color.White,
                        style = shadowStyle,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 23.sp,
                        textAlign = t,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                ThinDivider(Modifier.padding(top = 12.dp, bottom = 6.dp))

                // ---- Список игр: компактные плашки (выравнивание — как у всех блоков) ----
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = h
                ) {
                    games.forEach { game ->
                        GamePill(
                            game = game,
                            maxWidth = itemMaxWidth,
                            shadowStyle = shadowStyle,
                            onClick = { onPick(game.index) }
                        )
                    }
                }

                ThinDivider(Modifier.padding(top = 6.dp, bottom = 10.dp))

                // ---- Подсказка / выход ----
                Text(
                    text = "Нажмите на игру, чтобы начать",
                    color = Color.White.copy(alpha = 0.75f),
                    style = shadowStyle,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    textAlign = t
                )
                TextButton(
                    onClick = onExit,
                    modifier = Modifier.align(h)
                ) {
                    Text(
                        "Выход",
                        color = Color.White.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/** Плашка игры: ширина по содержимому, тёмное затенение, подсветка при нажатии. */
@Composable
private fun GamePill(
    game: CollectionEntry,
    maxWidth: Dp,
    shadowStyle: TextStyle,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .background(
                if (pressed) Color.Black.copy(alpha = 0.78f) else Color.Black.copy(alpha = 0.55f),
                RoundedCornerShape(10.dp)
            )
            .border(
                1.dp,
                if (pressed) NesYellow else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "%02d.".format(game.index + 1),
            color = NesYellow,
            style = shadowStyle,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        Text(
            text = game.title,
            color = Color.White,
            style = shadowStyle,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 9.dp)
        )
    }
}

/** Тонкая линия между секциями: во всю ширину блока контента, растворяется по краям. */
@Composable
private fun ThinDivider(modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.18f to Color.White.copy(alpha = 0.32f),
                0.82f to Color.White.copy(alpha = 0.32f),
                1f to Color.Transparent
            )
        )
    }
}

/** Постер из assets с даунсэмплом до размера экрана (экономия памяти). */
private fun decodePoster(context: Context, assetPath: String): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
    val dm = context.resources.displayMetrics
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= dm.widthPixels &&
        bounds.outHeight / (sample * 2) >= dm.heightPixels
    ) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
        ?.asImageBitmap()
} catch (_: Exception) {
    null // файла нет / битый — остаётся чёрный фон
}
