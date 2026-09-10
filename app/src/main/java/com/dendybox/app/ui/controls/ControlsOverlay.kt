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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.Density
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
 * Спецификация экранной кнопки: id, подпись, офсет относительно ЦЕНТРА своей
 * группы (в dp, при масштабе 1.0) и базовый размер (dp).
 */
data class CtrlSpec(val id: String, val label: String, val dx: Float, val dy: Float, val size: Float)

/**
 * Кластер A/B — «джойстик Dendy»: ВЕРХНИЙ ряд — турбо (B′, A′),
 * под ними основные B и A (B слева, A справа, как на геймпаде Dendy).
 */
val SPECS_AB = listOf(
    CtrlSpec("TB", "B′", -36f, -60f, 42f),
    CtrlSpec("TA", "A′", 36f, -60f, 42f),
    CtrlSpec("B", "B", -36f, 0f, 64f),
    CtrlSpec("A", "A", 36f, 0f, 64f)
)

/** Кластер A/B второго игрока — только основные кнопки, без турбо. */
val SPECS_AB2 = listOf(
    CtrlSpec("B", "B", -36f, 0f, 64f),
    CtrlSpec("A", "A", 36f, 0f, 64f)
)

/** Select / Start — отдельная группа: двигается и масштабируется независимо. */
val SPECS_META = listOf(
    CtrlSpec("SELECT", "SEL", -29f, 0f, 46f),
    CtrlSpec("START", "START", 29f, 0f, 46f)
)

private fun specsOf(id: LayoutStore.GroupId): List<CtrlSpec> = when (id) {
    LayoutStore.GroupId.AB -> SPECS_AB
    LayoutStore.GroupId.AB2 -> SPECS_AB2
    LayoutStore.GroupId.META -> SPECS_META
    LayoutStore.GroupId.DPAD, LayoutStore.GroupId.DPAD2 -> emptyList()
}

/**
 * Границы контента группы относительно её якоря (в dp при масштабе 1.0):
 * насколько контент выступает влево/вверх/вправо/вниз от центра.
 *
 * Блок A/B НЕсимметричен по вертикали: сверху турбо-ряд (81dp), снизу —
 * только основные кнопки (32dp). Рамка в редакторе и зажим в границах
 * экрана следуют контенту — без «фантомного» отступа под кнопками,
 * чтобы блок можно было прижать к нижнему краю экрана.
 */
data class Extents(val left: Float, val top: Float, val right: Float, val bottom: Float)

fun extentsDp(id: LayoutStore.GroupId, dpadBaseRadiusDp: Float): Extents = when (id) {
    LayoutStore.GroupId.AB, LayoutStore.GroupId.AB2 ->
        Extents(left = 68f, top = 81f, right = 68f, bottom = 32f)
    LayoutStore.GroupId.META -> Extents(left = 52f, top = 23f, right = 52f, bottom = 23f)
    LayoutStore.GroupId.DPAD, LayoutStore.GroupId.DPAD2 -> Extents(
        left = dpadBaseRadiusDp,
        top = dpadBaseRadiusDp,
        right = dpadBaseRadiusDp,
        bottom = dpadBaseRadiusDp
    )
}

/** Кнопка [id] нажата на порту [port] (турбо — только P1). */
private fun handlePress(id: String, down: Boolean, port: Int) {
    when (id) {
        "A" -> InputState.press(InputState.A, down, port)
        "B" -> InputState.press(InputState.B, down, port)
        "TA" -> if (port == 0) InputState.turboActiveA = down
        "TB" -> if (port == 0) InputState.turboActiveB = down
        "SELECT" -> InputState.press(InputState.SELECT, down, port)
        "START" -> InputState.press(InputState.START, down, port)
    }
}

// ---------------------------------------------------------------------------
// Общая механика редактора: сдвиг/масштаб группы с зажимом в границах экрана
// ---------------------------------------------------------------------------

/**
 * Сдвинуть группу на [dxPx]/[dyPx] пикселей и умножить масштаб на [zoom].
 * Зажим — по контентным границам группы (несимметричным для A/B: снизу
 * рамка прижата к кнопкам — блок можно ставить у самого низа экрана).
 * Всё состояние читается ВНУТРИ на момент события (колбэки pointerInput
 * не пересоздаются при рекомпозиции — захватывать значения «на старте» нельзя).
 */
private fun moveGroupBy(
    store: LayoutStore,
    id: LayoutStore.GroupId,
    dxPx: Float,
    dyPx: Float,
    zoom: Float,
    parentPx: IntSize,
    density: Density,
    dpadBaseRadiusDp: Float
) {
    val g = store.group(id)
    val e = extentsDp(id, dpadBaseRadiusDp)
    val l = with(density) { (e.left * g.scale).dp.toPx() / parentPx.width }.coerceAtMost(0.5f)
    val r = with(density) { (e.right * g.scale).dp.toPx() / parentPx.width }.coerceAtMost(0.5f)
    val t = with(density) { (e.top * g.scale).dp.toPx() / parentPx.height }.coerceAtMost(0.5f)
    val b = with(density) { (e.bottom * g.scale).dp.toPx() / parentPx.height }.coerceAtMost(0.5f)
    store.updateGroup(
        id,
        LayoutStore.Group(
            x = (g.x + dxPx / parentPx.width).coerceIn(l, (1f - r).coerceAtLeast(l)),
            y = (g.y + dyPx / parentPx.height).coerceIn(t, (1f - b).coerceAtLeast(t)),
            scale = (g.scale * zoom).coerceIn(LayoutStore.MIN_SCALE, LayoutStore.MAX_SCALE)
        )
    )
}

/**
 * Невидимый ФОН редактора: перетаскивание в любом пустом месте двигает
 * ВЫДЕЛЕННУЮ группу (видно по рамке), щипок двумя пальцами меняет её масштаб.
 * Касания самих блоков перехватываются блоками — они выше по z-порядку.
 */
@Composable
fun EditorSurface(store: LayoutStore, selected: LayoutStore.GroupId) {
    val dpadBaseR = SettingsStore.dpadSize.value
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val parentPx = IntSize(constraints.maxWidth, constraints.maxHeight)
        val density = LocalDensity.current
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(parentPx, selected) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (pan == Offset.Zero && zoom == 1f) return@detectTransformGestures
                        moveGroupBy(
                            store, selected, pan.x, pan.y, zoom,
                            parentPx, density, dpadBaseR
                        )
                    }
                }
        )
    }
}

/**
 * Рамка группы в редакторе: подпись + выделение цветом. Тап — выделить,
 * перетаскивание за пустое место рамки — двигать группу.
 * Границы задаются контентными отступами [leftPx]/[topPx]/[rightPx]/[bottomPx]
 * от якоря (для A/B снизу рамка прижата к кнопкам без зазора).
 * Рисуется ПОД кнопками группы (кнопки перехватывают касания на себе).
 */
@Composable
private fun GroupFrame(
    cxPx: Float,
    cyPx: Float,
    leftPx: Float,
    topPx: Float,
    rightPx: Float,
    bottomPx: Float,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onDragPx: (Float, Float) -> Unit
) {
    val density = LocalDensity.current
    val borderColor = if (selected) AccentRed.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.45f)
    Box(
        Modifier
            .offset { IntOffset((cxPx - leftPx).toInt(), (cyPx - topPx).toInt()) }
            .size(
                ((leftPx + rightPx) / density.density).dp,
                ((topPx + bottomPx) / density.density).dp
            )
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onSelect() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, amt ->
                        change.consume()
                        onDragPx(amt.x, amt.y)
                    }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(top = 3.dp)
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = if (selected) AccentRed else Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Слой кнопок. Параметры:
//  [inputPort] — в какой порт джойстика пишутся нажатия (0 — P1; в сетевой
//  игре гость передаёт 1, при этом раскладка остаётся обычной);
//  [p2Style] — рисовать ВТОРОЙ набор (кластер AB2 без турбо, без Select/Start)
//  для локальной игры на двоих на одном экране.
// ---------------------------------------------------------------------------

@Composable
fun ControlsLayer(
    store: LayoutStore,
    editing: Boolean,
    selected: LayoutStore.GroupId = LayoutStore.GroupId.AB,
    onSelect: (LayoutStore.GroupId) -> Unit = {},
    inputPort: Int = 0,
    p2Style: Boolean = false
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val parentPx = IntSize(constraints.maxWidth, constraints.maxHeight)
        val density = LocalDensity.current

        val groups = if (p2Style) {
            listOf(LayoutStore.GroupId.AB2)
        } else {
            listOf(LayoutStore.GroupId.AB, LayoutStore.GroupId.META)
        }
        groups.forEach { gid ->
            val g = store.group(gid) // чтение state — рекомпозиция при изменении
            val cx = g.x * parentPx.width
            val cy = g.y * parentPx.height
            val e = extentsDp(gid, 0f)
            val lPx = with(density) { (e.left * g.scale).dp.toPx() }
            val tPx = with(density) { (e.top * g.scale).dp.toPx() }
            val rPx = with(density) { (e.right * g.scale).dp.toPx() }
            val bPx = with(density) { (e.bottom * g.scale).dp.toPx() }

            // Рамка группы — ПОД кнопками (объявлена раньше в z-порядке)
            if (editing) {
                key(gid) {
                    GroupFrame(
                        cxPx = cx,
                        cyPx = cy,
                        leftPx = lPx,
                        topPx = tPx,
                        rightPx = rPx,
                        bottomPx = bPx,
                        label = LayoutStore.title(gid),
                        selected = selected == gid,
                        onSelect = { onSelect(gid) },
                        onDragPx = { dx, dy ->
                            moveGroupBy(store, gid, dx, dy, 1f, parentPx, density, 0f)
                        }
                    )
                }
            }

            specsOf(gid).forEach { spec ->
                key(gid.name + spec.id) {
                    val sizePx = with(density) { (spec.size * g.scale).dp.toPx() }
                    val x = (cx + spec.dx * g.scale * density.density - sizePx / 2f).toInt()
                    val y = (cy + spec.dy * g.scale * density.density - sizePx / 2f).toInt()
                    GameButton(
                        spec = spec,
                        gid = gid,
                        sizeDp = spec.size * g.scale,
                        xPx = x,
                        yPx = y,
                        store = store,
                        parentPx = parentPx,
                        density = density,
                        editing = editing,
                        inputPort = inputPort,
                        onSelect = { onSelect(gid) },
                        onPress = { down -> handlePress(spec.id, down, inputPort) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GameButton(
    spec: CtrlSpec,
    gid: LayoutStore.GroupId,
    sizeDp: Float,
    xPx: Int,
    yPx: Int,
    store: LayoutStore,
    parentPx: IntSize,
    density: Density,
    editing: Boolean,
    inputPort: Int,
    onSelect: () -> Unit,
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
                    // Редактор: тап — выделить группу, тянуть — двигать СВОЮ группу.
                    // Всё состояние читаем ВНУТРИ колбэка на момент события:
                    // pointerInput не пересоздаётся при рекомпозиции, поэтому
                    // захваченные «на старте» значения устаревают.
                    Modifier
                        .pointerInput(gid) {
                            detectTapGestures(onTap = { onSelect() })
                        }
                        .pointerInput(parentPx, gid) {
                            detectDragGestures(
                                onDragStart = { onSelect() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    moveGroupBy(
                                        store, gid, dragAmount.x, dragAmount.y, 1f,
                                        parentPx, density, 0f
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
// Фиксированная крестовина (всегда видима, позиция/масштаб — LayoutStore.dpad)
// ---------------------------------------------------------------------------

private class DpadState {
    var bits by mutableStateOf(0)
}

/**
 * Крестовина на фиксированном месте: 8 направлений (сектора по 45°)
 * с мёртвой зоной и гистерезисом. Видима всегда; зона касания — квадрат
 * 1.45×R вокруг центра, чтобы не нужно было попадать точно в стрелку.
 * [p2Style] — второй набор (группа DPAD2) для локальной игры на двоих.
 *
 * В редакторе: тап — выделить, перетаскивание — передвинуть.
 */
@Composable
fun DpadLayer(
    store: LayoutStore,
    editing: Boolean,
    selected: LayoutStore.GroupId = LayoutStore.GroupId.DPAD,
    onSelect: (LayoutStore.GroupId) -> Unit = {},
    enabled: Boolean,
    onBits: (Int) -> Unit,
    p2Style: Boolean = false
) {
    val gid = if (p2Style) LayoutStore.GroupId.DPAD2 else LayoutStore.GroupId.DPAD
    val view = LocalView.current
    val density = LocalDensity.current
    val dpadBaseR by SettingsStore.dpadSize.collectAsState()
    val g = store.group(gid) // recomposition при изменении позиции/масштаба
    val st = remember { DpadState() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val parentPx = IntSize(constraints.maxWidth, constraints.maxHeight)
        val cx = g.x * parentPx.width
        val cy = g.y * parentPx.height
        val rPx = with(density) { (dpadBaseR * g.scale).dp.toPx() }
        val zonePx = rPx * 1.45f

        // Невидимая зона касания вокруг крестовины
        Box(
            Modifier
                .offset { IntOffset((cx - zonePx).toInt(), (cy - zonePx).toInt()) }
                .size((zonePx * 2f / density.density).dp)
                .then(
                    when {
                        editing -> Modifier
                            .pointerInput(parentPx) {
                                detectTapGestures(
                                    onTap = { onSelect(gid) }
                                )
                            }
                            .pointerInput(parentPx, dpadBaseR) {
                                detectDragGestures(
                                    onDragStart = { onSelect(gid) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        moveGroupBy(
                                            store, gid,
                                            dragAmount.x, dragAmount.y, 1f,
                                            parentPx, density, dpadBaseR
                                        )
                                    }
                                )
                            }
                        enabled -> Modifier.pointerInput(rPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val enterR = rPx * 0.32f
                                val exitR = rPx * 0.20f
                                // Центр крестовины в ЛОКАЛЬНЫХ координатах зоны —
                                // зона всегда центрирована на крестовине
                                val center = Offset(size.width / 2f, size.height / 2f)
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
                                            // Гистерезис: смена направления только
                                            // при уходе от центра сектора (> 24°)
                                            if (curCenter == null || angularDist(ang, curCenter) > 24.0) {
                                                bits = candidate
                                            }
                                        }
                                    }
                                }

                                fun applyBits() {
                                    if (bits != st.bits) {
                                        st.bits = bits
                                        onBits(bits)
                                        if (SettingsStore.haptics.value) {
                                            view.performHapticFeedback(
                                                HapticFeedbackConstants.KEYBOARD_TAP
                                            )
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

                                // Палец отпущен — направления сбрасываются сразу
                                st.bits = 0
                                onBits(0)
                            }
                        }
                        else -> Modifier
                    }
                )
        )

        // Рамка редактора — под визуалом крестовины
        if (editing) {
            GroupFrame(
                cxPx = cx,
                cyPx = cy,
                leftPx = rPx,
                topPx = rPx,
                rightPx = rPx,
                bottomPx = rPx,
                label = LayoutStore.title(gid),
                selected = selected == gid,
                onSelect = { onSelect(gid) },
                onDragPx = { dx, dy ->
                    moveGroupBy(
                        store, gid, dx, dy, 1f,
                        parentPx, density, dpadBaseR
                    )
                }
            )
        }

        // Сама крестовина — рисуется ВСЕГДА
        Canvas(Modifier.fillMaxSize()) {
            drawDpad(Offset(cx, cy), rPx, st.bits)
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
    drawCircle(Color.White.copy(alpha = 0.12f), radius = r, center = center)
    drawCircle(Color.White.copy(alpha = 0.55f), radius = r, center = center, style = Stroke(2.dp.toPx()))

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
        drawPath(path, if (active) AccentRed.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.42f))
    }

    arrow(0.0, (bits and InputState.RIGHT) != 0)
    arrow(90.0, (bits and InputState.DOWN) != 0)
    arrow(180.0, (bits and InputState.LEFT) != 0)
    arrow(270.0, (bits and InputState.UP) != 0)
    drawCircle(Color.White.copy(alpha = 0.5f), radius = 3.dp.toPx(), center = center)
}
