package com.dendybox.app.ui.controls

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Спецификация экранной кнопки: id, подпись, позиция по умолчанию (доли экрана) и размер (dp).
 */
data class CtrlSpec(val id: String, val label: String, val defX: Float, val defY: Float, val defSize: Float)

val SPECS = listOf(
    CtrlSpec("B", "B", 0.775f, 0.63f, 62f),
    CtrlSpec("A", "A", 0.905f, 0.51f, 62f),
    CtrlSpec("TB", "B′", 0.665f, 0.75f, 40f),
    CtrlSpec("TA", "A′", 0.795f, 0.64f, 40f),
    CtrlSpec("SELECT", "SEL", 0.535f, 0.88f, 44f),
    CtrlSpec("START", "START", 0.660f, 0.88f, 44f)
)

fun specById(id: String?): CtrlSpec? = SPECS.firstOrNull { it.id == id }

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
// Слой кнопок A/B, турбо, Select/Start
// ---------------------------------------------------------------------------

@Composable
fun ControlsLayer(
    store: LayoutStore,
    editing: Boolean,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val parentPx = IntSize(constraints.maxWidth, constraints.maxHeight)
        SPECS.forEach { spec ->
            key(spec.id) {
                GameButton(
                    spec = spec,
                    store = store,
                    parentPx = parentPx,
                    editing = editing,
                    selected = selectedId == spec.id,
                    onPress = { down -> handlePress(spec.id, down) },
                    onSelect = { onSelect(spec.id) }
                )
            }
        }
    }
}

@Composable
private fun GameButton(
    spec: CtrlSpec,
    store: LayoutStore,
    parentPx: IntSize,
    editing: Boolean,
    selected: Boolean,
    onPress: (Boolean) -> Unit,
    onSelect: () -> Unit
) {
    val opacity by SettingsStore.controlsOpacity.collectAsState()
    val pos = store.pos(spec.id, spec.defX, spec.defY, spec.defSize)
    val density = LocalDensity.current
    val sizePx = with(density) { pos.size.dp.toPx() }
    val x = (pos.x * parentPx.width - sizePx / 2f).toInt()
    val y = (pos.y * parentPx.height - sizePx / 2f).toInt()
    val isPill = spec.id == "SELECT" || spec.id == "START"
    val shape = if (isPill) RoundedCornerShape(12.dp) else CircleShape

    Box(
        modifier = Modifier
            .offset { IntOffset(x, y) }
            .size(pos.size.dp)
            .then(
                if (editing) {
                    Modifier.pointerInput(spec.id) {
                        detectDragGestures(
                            onDragStart = { _ -> onSelect() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val cur = store.pos(spec.id, spec.defX, spec.defY, spec.defSize)
                                val nx = (cur.x + dragAmount.x / parentPx.width).coerceIn(0.03f, 0.97f)
                                val ny = (cur.y + dragAmount.y / parentPx.height).coerceIn(0.03f, 0.97f)
                                store.setPos(spec.id, LayoutStore.Pos(nx, ny, cur.size))
                            }
                        )
                    }
                } else {
                    Modifier.pointerInput(spec.id) {
                        detectTapGestures(
                            onPress = {
                                onPress(true)
                                try {
                                    awaitRelease()
                                } finally {
                                    onPress(false)
                                }
                            }
                        )
                    }
                }
            )
            .clip(shape)
            .background(if (selected) AccentRed.copy(alpha = opacity) else Color.White.copy(alpha = opacity))
            .border(2.dp, Color.White.copy(alpha = 0.55f), shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = spec.label,
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Bold,
            fontSize = if (pos.size < 50f) 10.sp else 18.sp
        )
    }
}

// ---------------------------------------------------------------------------
// Плавающая крестовина
// ---------------------------------------------------------------------------

private class DpadVisual {
    var visible by mutableStateOf(false)
    var center by mutableStateOf(Offset.Zero)
    var bits by mutableStateOf(0)
}

/**
 * Плавающая крестовина: появляется в точке касания левой зоны экрана,
 * 8 направлений (сектора по 45°) с мёртвой зоной и гистерезисом,
 * исчезает при отпускании пальца.
 */
@Composable
fun FloatingDPad(enabled: Boolean, onBits: (Int) -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val dpadSize by SettingsStore.dpadSize.collectAsState()
    val visual = remember { DpadVisual() }

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
                            visual.visible = true
                            visual.center = down.position
                            var bits = 0

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val vec = change.position - down.position
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

                                if (bits != visual.bits) {
                                    visual.bits = bits
                                    onBits(bits)
                                    if (SettingsStore.haptics.value) {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    }
                                }
                                change.consume()
                            }

                            visual.visible = false
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
