package com.dendybox.app.ui.controls

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dendybox.app.input.InputState
import com.dendybox.app.settings.SettingsStore
import com.dendybox.app.ui.AccentRed
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Спецификация экранной кнопки: id, подпись, офсет относительно ЦЕНТРА группы
 * (в dp, при масштабе 1.0) и базовый размер (dp).
 *
 * Кластер (вид «геймпад Dendy», сверху вниз):
 *
 *          [SEL]    [START]    <- пилюли (верх)
 *       [B]    [•]    [A]      <- основные
 *          [B′]      [A′]      <- турбо (низ)
 *
 * Офсеты подобраны так, чтобы кнопки не пересекались, а весь блок
 * по умолчанию располагался в правой нижней части экрана.
 */
data class CtrlSpec(val id: String, val label: String, val dx: Float, val dy: Float, val size: Float)

val SPECS = listOf(
    CtrlSpec("SELECT", "SEL", -30f, -58f, 46f),
    CtrlSpec("START", "START", 30f, -58f, 46f),
    CtrlSpec("B", "B", -36f, 2f, 64f),
    CtrlSpec("A", "A", 36f, 2f, 64f),
    CtrlSpec("TB", "B′", -36f, 58f, 40f),
    CtrlSpec("TA", "A′", 36f, 58f, 40f)
)

private fun handlePress(id: String, down: Boolean) {
    when (id) {
        "A" -> InputState.press(InputState.A, down)
        "B" -> InputState.press(InputState.B, down)
        "TA" -> InputState.turboActiveA = down
        "TB" -> InputState.turboActiveB = down
        "SELECT" -> InputState.press(InputState.SELECT, down)
        "START" -> InputState.press(InputState.START, down)
    }
}

// ---------------------------------------------------------------------------
// Слой кнопок A/B, турбо, Select/Start — вся группа двигается и масштабируется
// как единое целое (LayoutStore.group: якорь + масштаб).
// ---------------------------------------------------------------------------

@Composable
fun ControlsLayer(
    store: LayoutStore,
    editing: Boolean
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val parentPx = IntSize(constraints.maxWidth, constraints.maxHeight)
        val density = LocalDensity.current
        val g = store.group // чтение state — recomposition при каждом изменении

        // Пиксельные полуразмеры кластера (для рамки в режиме редактирования)
        var halfWdp = 0f
        var halfHdp = 0f
        SPECS.forEach { s ->
            halfWdp = maxOf(halfWdp, abs(s.dx) + s.size / 2f)
            halfHdp = maxOf(halfHdp, abs(s.dy) + s.size / 2f)
        }
        val halfWpx = with(density) { (halfWdp * g.scale).dp.toPx() }
        val halfHpx = with(density) { (halfHdp * g.scale).dp.toPx() }

        // Фон редактора: перетаскивание в любом пустом месте двигает группу,
        // щипок двумя пальцами масштабирует. Кнопки перехватывают касания
        // только на себе, всё состояние читаем свежим внутри колбэка.
        if (editing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(store) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val g = store.group
                            store.updateGroup(
                                LayoutStore.Group(
                                    x = (g.x + pan.x / size.width).coerceIn(0.02f, 0.98f),
                                    y = (g.y + pan.y / size.height).coerceIn(0.02f, 0.98f),
                                    scale = (g.scale * zoom)
                                        .coerceIn(LayoutStore.MIN_SCALE, LayoutStore.MAX_SCALE)
                                )
                            )
                        }
                    }
            )
        }

        // Рамка вокруг группы в режиме редактирования — видно, что двигается всё сразу
        if (editing) {
            val cx = g.x * parentPx.width
            val cy = g.y * parentPx.height
            Box(
                Modifier
                    .offset { IntOffset((cx - halfWpx).toInt(), (cy - halfHpx).toInt()) }
                    .size((halfWpx * 2 / density.density).dp, (halfHpx * 2 / density.density).dp)
                    .border(1.dp, AccentRed.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            )
        }

        SPECS.forEach { spec ->
            key(spec.id) {
                val sizePx = with(density) { (spec.size * g.scale).dp.toPx() }
                val x = (g.x * parentPx.width + spec.dx * g.scale * density.density - sizePx / 2f).toInt()
                val y = (g.y * parentPx.height + spec.dy * g.scale * density.density - sizePx / 2f).toInt()
                GameButton(
                    spec = spec,
                    sizeDp = spec.size * g.scale,
                    xPx = x,
                    yPx = y,
                    store = store,
                    parentPx = parentPx,
                    editing = editing,
                    onPress = { down -> handlePress(spec.id, down) }
                )
            }
        }
    }
}

@Composable
private fun GameButton(
    spec: CtrlSpec,
    sizeDp: Float,
    xPx: Int,
    yPx: Int,
    store: LayoutStore,
    parentPx: IntSize,
    editing: Boolean,
    onPress: (Boolean) -> Unit
) {
    val opacity by SettingsStore.controlsOpacity.collectAsState()
    val isTurbo = spec.id == "TA" || spec.id == "TB"
    val isPill = spec.id == "SELECT" || spec.id == "START"
    val shape = if (isPill) RoundedCornerShape(12.dp) else CircleShape
    var pressed by remember { mutableStateOf(false) }

    val fillAlpha = when {
        pressed -> minOf(1f, opacity + 0.3f)
        isTurbo -> opacity * 0.75f
        else -> opacity
    }
    val borderAlpha = if (isTurbo) 0.35f else 0.55f

    Box(
        modifier = Modifier
            .offset { IntOffset(xPx, yPx) }
            .size(sizeDp.dp)
            .then(
                if (editing) {
                    // Редактор: тянем ЛЮБУЮ кнопку — двигается вся группа.
                    // Всё состояние читаем ВНУТРИ колбэка на момент события:
                    // pointerInput не пересоздаётся при рекомпозиции, поэтому
                    // захваченные «на старте» значения устаревают.
                    // Ключ parentPx — перезапуск жеста при смене размеров экрана.
                    Modifier.pointerInput(parentPx) {
                        detectDragGestures(
                            onDragStart = { _ -> },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val g = store.group
                                var hw = 0f
                                var hh = 0f
                                SPECS.forEach { s ->
                                    hw = maxOf(hw, abs(s.dx) + s.size / 2f)
                                    hh = maxOf(hh, abs(s.dy) + s.size / 2f)
                                }
                                val halfW = (hw * g.scale).dp.toPx() / parentPx.width
                                val halfH = (hh * g.scale).dp.toPx() / parentPx.height
                                val mx = halfW.coerceAtMost(0.5f)
                                val my = halfH.coerceAtMost(0.5f)
                                store.updateGroup(
                                    LayoutStore.Group(
                                        x = (g.x + dragAmount.x / parentPx.width)
                                            .coerceIn(mx, (1f - mx).coerceAtLeast(mx)),
                                        y = (g.y + dragAmount.y / parentPx.height)
                                            .coerceIn(my, (1f - my).coerceAtLeast(my)),
                                        scale = g.scale
                                    )
                                )
                            }
                        )
                    }
                } else {
                    Modifier.pointerInput(spec.id) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                onPress(true)
                                try {
                                    awaitRelease()
                                } finally {
                                    pressed = false
                                    onPress(false)
                                }
                            }
                        )
                    }
                }
            )
            .clip(shape)
            .background(Color.White.copy(alpha = fillAlpha))
            .border(
                if (isTurbo) 1.dp else 2.dp,
                if (isTurbo) AccentRed.copy(alpha = borderAlpha) else Color.White.copy(alpha = borderAlpha),
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = spec.label,
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Bold,
            fontSize = if (sizeDp < 50f) 10.sp else 18.sp
        )
    }
}

// ---------------------------------------------------------------------------
// Плавающая крестовина
// ---------------------------------------------------------------------------

private class DpadVisual {
    var visible by mutableStateOf(false)
    var touchActive by mutableStateOf(false)
    var center by mutableStateOf(Offset.Zero)
    var bits by mutableStateOf(0)
}

/**
 * Плавающая крестовина: появляется в точке касания левой зоны экрана,
 * 8 направлений (сектора по 45°) с мёртвой зоной и гистерезисом.
 * После отпускания пальца остаётся на экране ещё [HIDE_DELAY_MS] мс
 * и только затем исчезает (направления при этом сбрасываются сразу).
 */
private const val HIDE_DELAY_MS = 5000L

@Composable
fun FloatingDPad(enabled: Boolean, onBits: (Int) -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val dpadSize by SettingsStore.dpadSize.collectAsState()
    val visual = remember { DpadVisual() }

    // Задержка исчезновения: перезапускается при каждом начале/конце касания
    LaunchedEffect(visual.visible, visual.touchActive) {
        if (visual.visible && !visual.touchActive) {
            delay(HIDE_DELAY_MS)
            visual.visible = false
        }
    }

    Box(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.55f)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val rPx = with(density) { SettingsStore.dpadSize.value.dp.toPx() }
                            val enterR = rPx * 0.32f
                            val exitR = rPx * 0.20f

                            // Если крестовина уже висит на экране (после предыдущего
                            // касания) и палец попал в её зону — НЕ переносим её:
                            // это нажатие по существующей крестовине. Перенос центра —
                            // только касанием вне её зоны.
                            val reuse = visual.visible &&
                                (down.position - visual.center).getDistance() <= rPx * 1.25f
                            if (!reuse) visual.center = down.position
                            visual.visible = true
                            visual.touchActive = true
                            val center = visual.center
                            var bits = 0

                            fun evalAt(pos: Offset) {
                                val vec = pos - center
                                val dist = vec.getDistance()
                                if (dist < exitR) {
                                    bits = 0
                                } else if (dist >= enterR) {
                                    val candidate = bitsForAngle(vec)
                                    if (candidate != bits) {
                                        val curCenter = sectorCenterDeg(bits)
                                        val ang = angleDeg(vec)
                                        // Гистерезис: смена направления только при явном
                                        // уходе от центра текущего сектора (> 24°)
                                        if (curCenter == null || angularDist(ang, curCenter) > 24.0) {
                                            bits = candidate
                                        }
                                    }
                                }
                            }

                            fun applyBits() {
                                if (bits != visual.bits) {
                                    visual.bits = bits
                                    onBits(bits)
                                    if (SettingsStore.haptics.value) {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    }
                                }
                            }

                            // Направление определяется сразу в точке касания —
                            // без ожидания движения пальца
                            evalAt(down.position)
                            applyBits()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                evalAt(change.position)
                                applyBits()
                                change.consume()
                            }

                            // Палец отпущен: направления сбрасываем сразу,
                            // но визуально крестовина остаётся ещё 5 секунд
                            visual.touchActive = false
                            visual.bits = 0
                            onBits(0)
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        if (visual.visible) {
            Canvas(Modifier.fillMaxSize()) {
                drawDpad(visual.center, dpadSize.dp.toPx(), visual.bits)
            }
        }
    }
}

// ---------------------------------------------------------------------------

private fun angleDeg(vec: Offset): Double = Math.toDegrees(atan2(vec.y, vec.x).toDouble())

private fun bitsForAngle(vec: Offset): Int {
    val deg = angleDeg(vec)
    return when {
        deg >= -22.5 && deg < 22.5 -> InputState.RIGHT
        deg >= 22.5 && deg < 67.5 -> InputState.DOWN or InputState.RIGHT
        deg >= 67.5 && deg < 112.5 -> InputState.DOWN
        deg >= 112.5 && deg < 157.5 -> InputState.DOWN or InputState.LEFT
        deg >= 157.5 || deg < -157.5 -> InputState.LEFT
        deg >= -157.5 && deg < -112.5 -> InputState.UP or InputState.LEFT
        deg >= -112.5 && deg < -67.5 -> InputState.UP
        else -> InputState.UP or InputState.RIGHT
    }
}

private fun sectorCenterDeg(bits: Int): Double? = when (bits) {
    InputState.RIGHT -> 0.0
    InputState.DOWN or InputState.RIGHT -> 45.0
    InputState.DOWN -> 90.0
    InputState.DOWN or InputState.LEFT -> 135.0
    InputState.LEFT -> 180.0
    InputState.UP or InputState.LEFT -> -135.0
    InputState.UP -> -90.0
    InputState.UP or InputState.RIGHT -> -45.0
    else -> null
}

private fun angularDist(a: Double, b: Double): Double {
    var d = (a - b) % 360.0
    if (d > 180.0) d -= 360.0
    if (d < -180.0) d += 360.0
    return abs(d)
}

private fun DrawScope.drawDpad(center: Offset, r: Float, bits: Int) {
    drawCircle(Color.White.copy(alpha = 0.10f), radius = r, center = center)
    drawCircle(Color.White.copy(alpha = 0.45f), radius = r, center = center, style = Stroke(2.dp.toPx()))

    fun arrow(angle: Double, active: Boolean) {
        val rad = Math.toRadians(angle)
        val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
        val perp = Offset(-dir.y, dir.x)
        val tip = center + dir * (r * 0.95f)
        val b1 = center + dir * (r * 0.52f) + perp * (r * 0.22f)
        val b2 = center + dir * (r * 0.52f) - perp * (r * 0.22f)
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(b1.x, b1.y)
            lineTo(b2.x, b2.y)
            close()
        }
        drawPath(path, if (active) AccentRed.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.35f))
    }

    arrow(0.0, (bits and InputState.RIGHT) != 0)
    arrow(90.0, (bits and InputState.DOWN) != 0)
    arrow(180.0, (bits and InputState.LEFT) != 0)
    arrow(270.0, (bits and InputState.UP) != 0)
    drawCircle(Color.White.copy(alpha = 0.5f), radius = 3.dp.toPx(), center = center)
}
