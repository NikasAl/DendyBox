package com.dendybox.app.ui.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dendybox.app.input.GamepadInput
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
 * Турбо ТОГО ЖЕ РАЗМЕРА, что основные (64dp). Ряд выше на dy = -72:
 * зазор между рядами 8dp — ровно как между B и A по горизонтали
 * (расстояние центров 72 при размере 64). При прежних dy = -60 круги
 * 64dp наезжали бы друг на друга на 4dp.
 */
val SPECS_AB = listOf(
    CtrlSpec("TB", "B′", -36f, -72f, 64f),
    CtrlSpec("TA", "A′", 36f, -72f, 64f),
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

/**
 * Группа «Пауза+Mute» — системные кнопки-действия тем же рядом, что
 * SELECT/START (пилюли 46dp, шаг 58dp). Заменяют прежние неподвижные
 * кнопки верхней панели: PAUSE открывает меню паузы, MUTE переключает
 * звук. Нажатия идут через тот же маршрутизатор касаний (FingerRouter,
 * подсветка), но в InputState не попадают — [GamepadInput.onSystemPress]
 * выполняет действие на нажатие.
 */
val SPECS_PAUSE_MUTE = listOf(
    CtrlSpec("PAUSE", "PAUSE", -29f, 0f, 46f),
    CtrlSpec("MUTE", "MUTE", 29f, 0f, 46f)
)

/**
 * Группа «Редактор+Save+Load» — системные кнопки-действия: EDIT открывает
 * настройки управления (редактор раскладки), SAVE/LOAD — квик-сейв/лоад
 * QUICK-слота (тем же путём, что раньше шла верхняя панель). Пилюли того
 * же размера, что SELECT/START; у скина «Гаджет» SAVE/LOAD — фото-пилюли
 * с каптионами, как на корпусе референса.
 */
val SPECS_EDIT_SAVE_LOAD = listOf(
    CtrlSpec("EDIT", "EDIT", -58f, 0f, 46f),
    CtrlSpec("SAVE", "SAVE", 0f, 0f, 46f),
    CtrlSpec("LOAD", "LOAD", 58f, 0f, 46f)
)

/** Спеки-пилюли: SELECT/START и системные кнопки PAUSE/MUTE/EDIT/SAVE/LOAD. */
val PILL_IDS = setOf("SELECT", "START", "PAUSE", "MUTE", "EDIT", "SAVE", "LOAD")

/**
 * Блок на игровой поверхности: обычная группа ([gid]) или комбо-группа
 * №[comboIdx] ([comboGroups]). Обычные группы (крестовина, A/B и т.д.)
 * не тронуты — BlockRef обобщает только позиционирование/рисовку/ hit-test,
 * чтобы комбо-кнопок могло быть несколько блоков.
 */
data class BlockRef(val gid: LayoutStore.GroupId, val comboIdx: Int = -1) {
    val isCombo: Boolean get() = gid == LayoutStore.GroupId.COMBO && comboIdx >= 0

    /** Имя привязки сырых касаний (уникально на блок; GamepadInput). */
    val bindName: String
        get() = if (isCombo) "${gid.name}:$comboIdx" else gid.name
}

private fun specsOf(id: LayoutStore.GroupId): List<CtrlSpec> = when (id) {
    LayoutStore.GroupId.AB -> SPECS_AB
    LayoutStore.GroupId.AB2 -> SPECS_AB2
    LayoutStore.GroupId.META -> SPECS_META
    LayoutStore.GroupId.PAUSE_MUTE -> SPECS_PAUSE_MUTE
    LayoutStore.GroupId.EDIT_SAVE_LOAD -> SPECS_EDIT_SAVE_LOAD
    else -> emptyList()
}

/**
 * Спеки кластера A/B с учётом скрытых пар ([LayoutStore.abOff]): скрытая
 * пара НЕ рисуется и не занимает места. При скрытии ОСНОВНОЙ пары —
 * КОМПАКТНАЯ ПОДТЯЖКА: турбо-ряд опускается на её место (dy = 0), группа
 * стягивается без дырки на месте убранных кнопок (рамка/зона/hit-test
 * следуют за спеками — см. [extentsAb]). Возврат пары возвращает турбо
 * наверх, к заводу — стандартный двухрядный кластер.
 */
fun specsAb(hidden: Set<String>): List<CtrlSpec> {
    val mainVisible = LayoutStore.AB_MAIN !in hidden
    val turboVisible = LayoutStore.AB_TURBO !in hidden
    return when {
        mainVisible && turboVisible -> SPECS_AB
        mainVisible -> SPECS_AB.filter { it.id == "A" || it.id == "B" }
        turboVisible ->
            // подтяжка: турбо-ряд занимает место скрытой пары A/B
            SPECS_AB.filter { it.id == "TA" || it.id == "TB" }.map { it.copy(dy = 0f) }
        else -> emptyList()
    }
}

/**
 * Спеки БЛОКА: комбо-группа №i, кластер A/B с учётом скрытых пар, прочие.
 * От скина корпуса НЕ зависят: SAVE/LOAD живут в своей группе
 * «Редактор+Save+Load» и показываются всегда (у прочих скинов —
 * параметрические пилюли).
 */
internal fun specsFor(store: LayoutStore, ref: BlockRef): List<CtrlSpec> = when {
    ref.isCombo -> comboSpecs(comboButtonsOf(store, ref.comboIdx))
    ref.gid == LayoutStore.GroupId.AB -> specsAb(store.abOff)
    else -> specsOf(ref.gid)
}

/**
 * Границы контента группы относительно её якоря (в dp при масштабе 1.0):
 * насколько контент выступает влево/вверх/вправо/вниз от центра.
 *
 * Блок A/B НЕсимметричен по вертикали: сверху турбо-ряд (72dp до центра
 * кнопки + 32dp радиус = 104dp), снизу — только основные кнопки (32dp).
 * У A/B второго игрока турбо нет — рамка обнимает кнопки симметрично.
 * Рамка в редакторе и зажим в границах экрана следуют контенту — без
 * «фантомного» отступа под кнопками, чтобы блок можно было прижать
 * к нижнему краю экрана. Скрытые пары ([specsAb]) места не занимают —
 * см. [extentsAb].
 */
data class Extents(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Границы кластера A/B при скрытых парах: рамка обнимает видимую половину
 * БЕЗ дырки — при скрытии основной пары турбо-ряд подтягивается на её
 * место (см. [specsAb]), поэтому и рамка, и зона касаний сжимаются
 * симметрично вокруг якоря. Обе пары скрыты — квадратик 24dp (как пустой
 * блок комбо), чтобы блок можно было взять в редакторе.
 */
fun extentsAb(hidden: Set<String>): Extents {
    val mainVisible = LayoutStore.AB_MAIN !in hidden
    val turboVisible = LayoutStore.AB_TURBO !in hidden
    return when {
        mainVisible && turboVisible -> Extents(68f, 104f, 68f, 32f)
        mainVisible -> Extents(68f, 32f, 68f, 32f)
        // турбо-ряд подтянут к якорю (dy = 0) — рамка как у одной пары
        turboVisible -> Extents(68f, 32f, 68f, 32f)
        else -> Extents(24f, 24f, 24f, 24f)
    }
}

fun extentsDp(
    id: LayoutStore.GroupId,
    dpadBaseRadiusDp: Float
): Extents = when (id) {
    LayoutStore.GroupId.AB -> extentsAb(emptySet())
    LayoutStore.GroupId.AB2 ->
        Extents(left = 68f, top = 32f, right = 68f, bottom = 32f)
    LayoutStore.GroupId.META -> Extents(left = 52f, top = 23f, right = 52f, bottom = 23f)
    // «Пауза+Mute» — две пилюли с шагом 58 (как META);
    // «Редактор+Save+Load» — три пилюли: 58 до крайней + 23 радиус
    LayoutStore.GroupId.PAUSE_MUTE -> Extents(left = 52f, top = 23f, right = 52f, bottom = 23f)
    LayoutStore.GroupId.EDIT_SAVE_LOAD -> Extents(left = 81f, top = 23f, right = 81f, bottom = 23f)
    LayoutStore.GroupId.COMBO -> comboExtentsDp(ComboStore.effective().size)
    LayoutStore.GroupId.DPAD, LayoutStore.GroupId.DPAD2 -> Extents(
        left = dpadBaseRadiusDp,
        top = dpadBaseRadiusDp,
        right = dpadBaseRadiusDp,
        bottom = dpadBaseRadiusDp
    )
}

/** Позиция/масштаб блока (комбо-группа №i или обычная группа). */
internal fun blockGroup(store: LayoutStore, ref: BlockRef): LayoutStore.Group =
    if (ref.isCombo) {
        val cg = store.comboGroups[ref.comboIdx]
        LayoutStore.Group(cg.x, cg.y, cg.scale)
    } else {
        store.group(ref.gid)
    }

/** Границы контента блока (комбо-группы — по числу ВИДИМЫХ кнопок). */
internal fun blockExtents(
    store: LayoutStore,
    ref: BlockRef,
    dpadBaseRadiusDp: Float
): Extents =
    when {
        ref.isCombo -> comboExtentsDp(comboButtonsOf(store, ref.comboIdx).size)
        ref.gid == LayoutStore.GroupId.AB -> extentsAb(store.abOff)
        else -> extentsDp(ref.gid, dpadBaseRadiusDp)
    }

// ---------------------------------------------------------------------------
// Программируемые комбо-кнопки (наполнение — ComboStore: пресеты игры +
// пользовательские). Блок рисуется/маршрутизируется как обычная группа;
// отличие — кнопки ДИНАМИЧЕСКИЕ: сетка пересчитывается по их числу.
// ---------------------------------------------------------------------------

/** Размер одной комбо-кнопки, dp. */
const val COMBO_SIZE = 56f

/** Шаг сетки между центрами комбо-кнопок, dp (зазор 8dp — как в кластере A/B). */
const val COMBO_PITCH = 64f

/** Колонок в сетке комбо-кнопок: 1; 2×N до четырёх; 3×N дальше. */
fun comboCols(n: Int): Int = when {
    n <= 1 -> 1
    n <= 4 -> 2
    else -> 3
}

/**
 * Спеки комбо-кнопок: id «K<id кнопки>» (префикс маршрутизируется в
 * ComboRunner, см. GamepadInput), подпись — имя кнопки. Сетка выровнена
 * по центру якоря группы.
 */
fun comboSpecs(buttons: List<ComboButton>): List<CtrlSpec> {
    if (buttons.isEmpty()) return emptyList()
    val cols = comboCols(buttons.size)
    val rows = (buttons.size + cols - 1) / cols
    return buttons.mapIndexed { i, b ->
        val dx = ((i % cols) - (cols - 1) / 2f) * COMBO_PITCH
        val dy = ((i / cols) - (rows - 1) / 2f) * COMBO_PITCH
        CtrlSpec("K${b.id}", b.name, dx, dy, COMBO_SIZE)
    }
}

/**
 * Границы блока из n комбо-кнопок (сетка [comboSpecs]); пустой блок —
 * квадратик 48dp, чтобы рамку можно было тянуть в редакторе.
 */
fun comboExtentsDp(n: Int): Extents =
    if (n == 0) {
        Extents(24f, 24f, 24f, 24f)
    } else {
        val cols = comboCols(n)
        val rows = (n + cols - 1) / cols
        val hw = (cols - 1) * COMBO_PITCH / 2f + COMBO_SIZE / 2f
        val hh = (rows - 1) * COMBO_PITCH / 2f + COMBO_SIZE / 2f
        Extents(hw, hh, hw, hh)
    }

/**
 * Кнопки комбо-группы №[idx] в ПОРЯДКЕ СЕТКИ: участники из
 * [LayoutStore.comboGroups] (ключи [ComboStore.disabledKey]) плюс — только
 * для группы №0 — непрописанные кнопки в конце (созданная кнопка
 * появляется там автоматически). Ключи отсутствующих кнопок («дремлющие»:
 * другой ROM сборника, удалённая кнопка) молча пропускаются.
 */
fun comboButtonsOf(store: LayoutStore, idx: Int): List<ComboButton> {
    if (idx !in store.comboGroups.indices) return emptyList()
    val all = ComboStore.effective()
    val keys = store.comboGroups[idx].keys
    val listed = keys.mapNotNull { k -> all.firstOrNull { ComboStore.disabledKey(it) == k } }
    if (idx != 0) return listed
    // группа №0 добирает кнопки, не прописанные ни в одной группе
    val assigned = store.comboGroups.flatMap { it.keys }.toSet()
    return listed + all.filter { ComboStore.disabledKey(it) !in assigned }
}

/**
 * Номер ячейки сетки из n кнопок, ближайшей к точке ([dxDp], [dyDp]) от
 * якоря (обратная задача [comboSpecs]); результат 0..n — n означает
 * «в конец списка». Используется при перетаскивании кнопки в редакторе.
 */
fun comboCellAt(n: Int, dxDp: Float, dyDp: Float): Int {
    if (n <= 0) return 0
    val cols = comboCols(n)
    val rows = (n + cols - 1) / cols
    val col = Math.round(dxDp / COMBO_PITCH + (cols - 1) / 2f).coerceIn(0, cols - 1)
    val row = Math.round(dyDp / COMBO_PITCH + (rows - 1) / 2f).coerceIn(0, rows - 1)
    return (row * cols + col).coerceIn(0, n)
}

/**
 * Перетаскивание комбо-кнопки в редакторе: точка ([xPx], [yPx]) px — палец
 * на кнопке [specId] из группы [fromIdx]. Кнопка переставляется ВНУТРИ
 * своей группы (порядок в сетке) или переносится в группу, над которой
 * отпущен палец. Живая реакция: состав групп меняется прямо во время
 * перетаскивания, кнопка «прыгает» на свою ячейку. Точка мимо всех
 * рамок (с запасом в полшага сетки) — изменений нет.
 */
internal fun comboDragTo(
    store: LayoutStore,
    fromIdx: Int,
    specId: String,
    xPx: Float,
    yPx: Float,
    parentPx: IntSize,
    density: Density
) {
    val source = comboButtonsOf(store, fromIdx)
    val dragged = source.firstOrNull { "K${it.id}" == specId } ?: return
    val key = ComboStore.disabledKey(dragged)

    for (i in store.comboGroups.indices) {
        val cg = store.comboGroups[i]
        val cx = cg.x * parentPx.width
        val cy = cg.y * parentPx.height
        val dxDp = with(density) { (xPx - cx).toDp().value } / cg.scale
        val dyDp = with(density) { (yPx - cy).toDp().value } / cg.scale
        val members = comboButtonsOf(store, i)
        val e = comboExtentsDp(members.size)
        val slack = COMBO_PITCH / 2f
        if (dxDp < -e.left - slack || dxDp > e.right + slack ||
            dyDp < -e.top - slack || dyDp > e.bottom + slack
        ) continue

        val memberKeys = members.map { ComboStore.disabledKey(it) }
        // «дремлющие» ключи группы (кнопки другого ROM/удалённые) — в конец,
        // порядок между собой сохраняем: расстановка восстановится с ромом
        val dormant = store.comboGroups[i].keys.filter { it !in memberKeys }
        if (i == fromIdx) {
            val cell = comboCellAt(members.size, dxDp, dyDp)
            val own = memberKeys.indexOf(key)
            // порядок не меняется: кнопка уже на своей ячейке (или сразу за ней)
            if (own >= 0 && (cell == own || cell == own + 1)) return
            val without = memberKeys - key
            // индекс вставки после удаления: до своей позиции — как есть,
            // после — сдвиг на единицу
            val at = (if (own >= 0 && cell > own) cell - 1 else cell).coerceIn(0, without.size)
            store.setComboGroupKeys(
                i,
                without.toMutableList().apply { add(at, key) } + dormant
            )
        } else {
            val cell = comboCellAt(members.size, dxDp, dyDp).coerceIn(0, members.size)
            // перенос: из исходной группы убрать, в целевую вставить на ячейку
            store.setComboGroupKeys(fromIdx, store.comboGroups[fromIdx].keys - key)
            store.setComboGroupKeys(
                i,
                memberKeys.toMutableList().apply { add(cell, key) } +
                    dormant.filter { it != key }
            )
        }
        return
    }
}

// ---------------------------------------------------------------------------
// Общая механика редактора: сдвиг/масштаб группы с зажимом в границах экрана
// ---------------------------------------------------------------------------

/**
 * Сдвинуть блок на [dxPx]/[dyPx] пикселей и умножить масштаб на [zoom].
 * Зажим — по контентным границам блока (несимметричным для A/B: снизу
 * рамка прижата к кнопкам — блок можно ставить у самого низа экрана;
 * у комбо-групп — по числу её видимых кнопок).
 * Всё состояние читается ВНУТРИ на момент события (колбэки pointerInput
 * не пересоздаются при рекомпозиции — захватывать значения «на старте» нельзя).
 */
private fun moveGroupBy(
    store: LayoutStore,
    ref: BlockRef,
    dxPx: Float,
    dyPx: Float,
    zoom: Float,
    parentPx: IntSize,
    density: Density,
    dpadBaseRadiusDp: Float
) {
    val g = blockGroup(store, ref)
    val e = blockExtents(store, ref, dpadBaseRadiusDp)
    val l = with(density) { (e.left * g.scale).dp.toPx() / parentPx.width }.coerceAtMost(0.5f)
    val r = with(density) { (e.right * g.scale).dp.toPx() / parentPx.width }.coerceAtMost(0.5f)
    val t = with(density) { (e.top * g.scale).dp.toPx() / parentPx.height }.coerceAtMost(0.5f)
    val b = with(density) { (e.bottom * g.scale).dp.toPx() / parentPx.height }.coerceAtMost(0.5f)
    val next = LayoutStore.Group(
        x = (g.x + dxPx / parentPx.width).coerceIn(l, (1f - r).coerceAtLeast(l)),
        y = (g.y + dyPx / parentPx.height).coerceIn(t, (1f - b).coerceAtLeast(t)),
        scale = (g.scale * zoom).coerceIn(LayoutStore.MIN_SCALE, LayoutStore.MAX_SCALE)
    )
    if (ref.isCombo) store.updateComboGroupPos(ref.comboIdx, next.x, next.y, next.scale)
    else store.updateGroup(ref.gid, next)
}

/**
 * Невидимый ФОН редактора: перетаскивание в любом пустом месте двигает
 * ВЫДЕЛЕННЫЙ блок (видно по рамке), щипок двумя пальцами меняет его масштаб.
 * Касания самих блоков перехватываются блоками — они выше по z-порядку.
 */
@Composable
fun EditorSurface(store: LayoutStore, selected: BlockRef) {
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

/**
 * Какая кнопка находится в точке ([x], [y]) px, если кнопки блока — [specs]
 * с якорем/масштабом [g]? Формулы позиций — ТЕ ЖЕ, что при отрисовке кнопок
 * в [ControlsLayer]: центр блока (g.x/g.y в долях экрана) + офсет спецификации,
 * размер с масштабом. null — точка вне кнопок блока.
 * Координаты — окно (сырые события активности, см. GamepadInput); при
 * immersive-полноэкране совпадают с координатами родителя слоёв управления.
 * Позиция/масштаб/состав кнопок читаются на момент события — редактирование
 * раскладки без пересоздания бинда не рассинхронизирует hit-test и рисунок.
 */
private fun hitTestButton(
    specs: List<CtrlSpec>,
    g: LayoutStore.Group,
    x: Float,
    y: Float,
    parentPx: IntSize,
    density: Density
): String? {
    val cx = g.x * parentPx.width
    val cy = g.y * parentPx.height
    for (spec in specs) {
        val s = spec.size * g.scale * density.density
        val left = cx + spec.dx * g.scale * density.density - s / 2f
        val top = cy + spec.dy * g.scale * density.density - s / 2f
        if (x >= left && x < left + s && y >= top && y < top + s) return spec.id
    }
    return null
}

@Composable
fun ControlsLayer(
    store: LayoutStore,
    editing: Boolean,
    selected: BlockRef = BlockRef(LayoutStore.GroupId.AB),
    onSelect: (BlockRef) -> Unit = {},
    inputPort: Int = 0,
    p2Style: Boolean = false
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val parentPx = IntSize(constraints.maxWidth, constraints.maxHeight)
        val density = LocalDensity.current

        // Скин (один на все группы). collectAsState — смена скина перекрашивает
        // блоки без рестарта; геометрия/hit-test от скина НЕ зависят
        val skin = ControlSkins.byId(SettingsStore.controlSkin.collectAsState().value)
        // Слайдер «Видимость» двигает и картинку-подложку блока (imgBlock)
        val opacity by SettingsStore.controlsOpacity.collectAsState()

        // Блоки слоя. Кластер A/B в игре скрывается целиком, если скрыты
        // ОБЕ пары; каждая комбо-группа — отдельный блок: в игре — только
        // с кнопками, в редакторе — всегда (рамку можно поставить заранее).
        // Системные группы «Пауза+Mute» и «Редактор+Save+Load» — всегда
        // видимы (в P2-режиме их нет: системные действия общие)
        val blocks = if (p2Style) {
            listOf(BlockRef(LayoutStore.GroupId.AB2))
        } else {
            buildList {
                if (editing || specsAb(store.abOff).isNotEmpty()) {
                    add(BlockRef(LayoutStore.GroupId.AB))
                }
                add(BlockRef(LayoutStore.GroupId.META))
                add(BlockRef(LayoutStore.GroupId.PAUSE_MUTE))
                add(BlockRef(LayoutStore.GroupId.EDIT_SAVE_LOAD))
                store.comboGroups.indices.forEach { i ->
                    if (editing || comboButtonsOf(store, i).isNotEmpty()) {
                        add(BlockRef(LayoutStore.GroupId.COMBO, i))
                    }
                }
            }
        }

        // Порт СВОЕЙ стороны читаем В МОМЕНТ СОБЫТИЯ (бинд-колбэки живут
        // дольше рекомпозиций; см. комментарий в GameScreen про netplay)
        val currentPort by rememberUpdatedState(inputPort)

        // Подсветка нажатых кнопок: одна карта на блок со СТАБИЛЬНОЙ идентичностью
        // (getOrPut в remember-карте — добавление/удаление комбо-групп не сбрасывает
        // подсветку и не пересоздаёт бинды остальных блоков).
        val pressedMaps = remember { HashMap<BlockRef, SnapshotStateMap<String, Boolean>>() }

        // КАК РАБОТАЮТ КАСАНИЯ КНОПОК в игровом режиме: НЕ через Compose.
        // Раньше зона группы была pointerInput-боксом — hit-path/жестовая
        // машина Compose на устройстве теряла второй палец (A+B не нажимались
        // одновременно) и гасила быстрые серии нажатий. Теперь сырые
        // MotionEvents принимает dispatchTouchEvent активности и передаёт
        // GamepadInput → TouchRouterCore → FingerRouter (скольжение пальца
        // между кнопками, многопальцевость, самовосстановление потерянных
        // пальцев — см. TouchRouterCore). Compose остаётся только подсветку
        // рисовать. «Пятно касания» (ко-нажатие по эллипсу контакта) УДАЛЕНО:
        // AXIS_TOUCH_MAJOR на устройствах недостоверен — пятно нажимало
        // соседнюю кнопку в момент касания и отжимало её первым же движением.
        //
        // Группа привязана, пока слой в игровом режиме на экране: уход в
        // паузу/редактор (DisposableEffect.onDispose) отжимает её кнопки.
        // Геометрия (hitTest) читает масштаб/позицию/состав НА МОМЕНТ СОБЫТИЯ —
        // редактирование раскладки не рассинхронизирует hit-test и рисунок.
        // Координаты — окно (сырые события); при immersive-полноэкране
        // совпадают с координатами этого BoxWithConstraints.
        blocks.forEach { ref ->
            val g = blockGroup(store, ref) // чтение state — рекомпозиция при изменении
            val cx = g.x * parentPx.width
            val cy = g.y * parentPx.height
            val e = blockExtents(store, ref, 0f)
            val lPx = with(density) { (e.left * g.scale).dp.toPx() }
            val tPx = with(density) { (e.top * g.scale).dp.toPx() }
            val rPx = with(density) { (e.right * g.scale).dp.toPx() }
            val bPx = with(density) { (e.bottom * g.scale).dp.toPx() }

            // Подложка image-скина: одна картинка целым БЛОКОМ под всей группой
            // (панель джойстика). Кнопки сверху сохраняют свой обычный рендер;
            // подложка без pointerInput — зоны касаний не меняются
            val blockImg = skin.imgBlock
            if (blockImg != null) {
                Image(
                    painter = painterResource(blockImg),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alpha = opacity,
                    modifier = Modifier
                        .offset { IntOffset((cx - lPx).toInt(), (cy - tPx).toInt()) }
                        .size(
                            with(density) { ((e.left + e.right) * g.scale).dp },
                            with(density) { ((e.top + e.bottom) * g.scale).dp }
                        )
                )
            }

            // Привязка группы к сырым касаниям (только в игровом режиме)
            if (!editing) {
                key(ref.bindName) {
                    val pressedState = pressedMaps.getOrPut(ref) { mutableStateMapOf() }
                    // Актуальные размеры/плотность на момент события: бинд
                    // живёт дольше рекомпозиций — захватывать значения нельзя
                    val px by rememberUpdatedState(parentPx)
                    val den by rememberUpdatedState(density)
                    DisposableEffect(pressedState) {
                        GamepadInput.bindGroup(
                            ref.bindName,
                            hitTest = { x, y ->
                                hitTestButton(
                                    specsFor(store, ref),
                                    blockGroup(store, ref), x, y, px, den
                                )
                            },
                            port = { currentPort },
                            pressed = pressedState
                        )
                        onDispose { GamepadInput.unbindGroup(ref.bindName) }
                    }
                }
            }

            // Рамка группы — ПОД кнопками (объявлена раньше в z-порядке)
            if (editing) {
                key(ref.bindName) {
                    GroupFrame(
                        cxPx = cx,
                        cyPx = cy,
                        leftPx = lPx,
                        topPx = tPx,
                        rightPx = rPx,
                        bottomPx = bPx,
                        label = if (ref.isCombo) {
                            store.comboGroups[ref.comboIdx].name
                        } else {
                            LayoutStore.title(ref.gid)
                        },
                        selected = selected == ref,
                        onSelect = { onSelect(ref) },
                        onDragPx = { dx, dy ->
                            moveGroupBy(store, ref, dx, dy, 1f, parentPx, density, 0f)
                        }
                    )
                }
            }

            specsFor(store, ref).forEach { spec ->
                key(ref.bindName + spec.id) {
                    val sizePx = with(density) { (spec.size * g.scale).dp.toPx() }
                    val x = (cx + spec.dx * g.scale * density.density - sizePx / 2f).toInt()
                    val y = (cy + spec.dy * g.scale * density.density - sizePx / 2f).toInt()
                    GameButton(
                        spec = spec,
                        block = ref,
                        sizeDp = spec.size * g.scale,
                        xPx = x,
                        yPx = y,
                        store = store,
                        parentPx = parentPx,
                        density = density,
                        editing = editing,
                        onSelect = { onSelect(ref) },
                        // В редакторе комбо-кнопка тянется КАК КНОПКА: порядок
                        // в группе / перенос между группами (группу двигают
                        // рамкой или фоном). Палец в координатах родителя:
                        // собственный офсет кнопки известен в GameButton
                        onComboDragPx = if (editing && ref.isCombo) {
                            { px, py ->
                                comboDragTo(
                                    store, ref.comboIdx, spec.id, px, py, parentPx, density
                                )
                            }
                        } else null,
                        // Подсветку нажатия ставит маршрутизатор касаний группы
                        pressed = pressedMaps[ref]?.get(spec.id) == true
                    )
                }
            }
        } // blocks.forEach
    }
}

// ---------------------------------------------------------------------------
// Прозрачность контролов
// ---------------------------------------------------------------------------

/**
 * Слайдер «Видимость» (SettingsStore.controlsOpacity, 0.2..0.9) задаёт ОБЩУЮ
 * яркость ВСЕХ экранных контролов: заливки кнопок, их рамки, подписи и вся
 * крестовина (круг, обводка, стрелки, точка) движутся вместе.
 *
 * Базовые альфы дизайна (рамка 0.55 / турбо 0.35, крестовина
 * 0.12/0.55/0.42/0.95/0.5, подпись 0.9) сохраняют относительные веса и
 * умножаются на k = opacity / [ControlAlphas.OPACITY_BASE]: при базовом
 * значении слайдера вид ровно такой же, как до ведения слайдера для всех
 * элементов; к максимуму всё становится плотнее, к минимуму — равномерно
 * растворяется. Заливки кнопок и подсветка нажатия по-прежнему считаются
 * напрямую из opacity (они и раньше от него зависели).
 */
internal data class ControlAlphas(
    val borderMain: Float,
    val borderTurbo: Float,
    val label: Float,
    val dpadFill: Float,
    val dpadStroke: Float,
    val dpadArrow: Float,
    val dpadArrowActive: Float,
    val dpadDot: Float
) {
    companion object {
        /** Базовое (проектное) значение слайдера — при нём k = 1. */
        const val OPACITY_BASE = 0.55f

        fun of(opacity: Float): ControlAlphas {
            val k = (opacity / OPACITY_BASE).coerceIn(0f, 1f / OPACITY_BASE)
            return ControlAlphas(
                borderMain = (0.55f * k).coerceAtMost(1f),
                borderTurbo = (0.35f * k).coerceAtMost(1f),
                label = (0.9f * k).coerceAtMost(1f),
                dpadFill = (0.12f * k).coerceIn(0f, 1f),
                dpadStroke = (0.55f * k).coerceAtMost(1f),
                dpadArrow = (0.42f * k).coerceIn(0f, 1f),
                dpadArrowActive = (0.95f * k).coerceAtMost(1f),
                dpadDot = (0.5f * k).coerceAtMost(1f)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Визуальное решение кнопки — чистая функция (тестируемая на JVM)
// ---------------------------------------------------------------------------

/**
 * Готовый вид кнопки: форма, заливка, рамка, подпись. Считается из скина,
 * слайдера «Видимость» и состояния нажатия — [buttonLook]. GameButton
 * только переносит эти значения в Compose-модификаторы, логики в нём нет.
 *
 * Инвариант «комбо-кнопки в гармонии со скином»: у комбо-кнопок ("K*")
 * вид следует за активным [ControlSkin] точно как у обычных кнопок — форма
 * [ControlSkin.shapeMain], заливка [ControlSkin.fill], рамка
 * [ControlSkin.comboBorder], подпись [ControlSkin.label] (у комбо — всегда
 * ВНУТРИ кнопки: каптионы для них не включаются никогда), картинок нет.
 * Слайдер «Видимость» двигает их заливку/рамку/подпись теми же формулами,
 * что у всех; нажатие подсвечивает тем же бустом (+0.3 к альфе заливки).
 */
internal data class ButtonLook(
    /** Картинка скина (res/drawable) или null — параметрический рендер. */
    val imageRes: Int?,
    /** Форма кнопки (до приведения к Compose-Shape). */
    val shape: SkinShape,
    /** Заливка с альфой (параметрика; при картинке не применяется). */
    val fill: Color,
    /** Цвет рамки с альфой. */
    val borderColor: Color,
    /** Толщина рамки, dp. */
    val borderWidthDp: Float,
    /** Цвет подписи с альфой. */
    val labelColor: Color,
    /**
     * Каптионный режим скина: подпись живёт НАД кнопкой, внутри текста нет.
     * Касается только физических кнопок ([ControlSkin.IMAGEABLE_IDS]);
     * комбо-кнопки всегда с подписью внутри ([ControlSkin.captions] на них
     * не действует — подписи пользовательские).
     */
    val captionMode: Boolean,
    /** Текст каптиона (при [captionMode]); пустой каптион не рисуется. */
    val captionText: String
)

/**
 * Визуальное решение кнопки [specId] (подпись спека — [specLabel]) в скине
 * [skin] при значении слайдера «Видимость» [opacity] и состоянии [pressed].
 * ЧИСТАЯ функция: без Compose-состояния — одинаковый вход даёт одинаковый
 * вид, контракт закреплён тестами (ButtonLookTest).
 */
internal fun buttonLook(
    skin: ControlSkin,
    specId: String,
    specLabel: String,
    opacity: Float,
    pressed: Boolean
): ButtonLook {
    val isTurbo = specId == "TA" || specId == "TB"
    val isCombo = specId.startsWith("K") // программируемая комбо-кнопка
    val isPill = specId in PILL_IDS
    // Слайдер двигает и рамку, и подпись вместе с заливкой (см. ControlAlphas)
    val alphas = ControlAlphas.of(opacity)
    // Заливка: нажатие подсвечивает бустом, турбо слегка приглушено
    val fillAlpha = when {
        pressed -> minOf(1f, opacity + 0.3f)
        isTurbo -> opacity * 0.75f
        else -> opacity
    }
    val captionMode = skin.captions && specId in ControlSkin.IMAGEABLE_IDS
    val captionText = if (captionMode) skin.captionFor(specId, specLabel) else ""
    return ButtonLook(
        imageRes = skin.buttonImage(specId),
        shape = if (isPill) skin.shapeMeta else skin.shapeMain,
        fill = skin.fill.copy(alpha = fillAlpha),
        borderColor = when {
            isTurbo -> skin.turboBorder.copy(alpha = alphas.borderTurbo)
            // комбо-кнопки отличаются рамкой (в классике — красной, как турбо)
            isCombo -> skin.comboBorder.copy(alpha = alphas.borderMain)
            else -> skin.border.copy(alpha = alphas.borderMain)
        },
        borderWidthDp = when {
            isTurbo -> 1f
            isCombo -> 1.5f
            else -> 2f
        },
        labelColor = skin.label.copy(alpha = alphas.label),
        captionMode = captionMode,
        captionText = captionText
    )
}

@Composable
private fun GameButton(
    spec: CtrlSpec,
    block: BlockRef,
    sizeDp: Float,
    xPx: Int,
    yPx: Int,
    store: LayoutStore,
    parentPx: IntSize,
    density: Density,
    editing: Boolean,
    onSelect: () -> Unit,
    // Редактор, комбо-кнопка: тянется САМА КНОПКА (порядок в группе /
    // перенос между группами, см. comboDragTo); палец — в координатах
    // родителя. null — обычное поведение: тянется вся группа
    onComboDragPx: ((Float, Float) -> Unit)?,
    // Нажатие выставляет маршрутизатор касаний группы (ControlsLayer);
    // в редакторе кнопки не «нажимаются» — параметр остаётся false
    pressed: Boolean
) {
    val opacity by SettingsStore.controlsOpacity.collectAsState()
    // Скин: форма/цвета/шрифт. collectAsState — смена скина перекрашивает
    // кнопку на живом игровом экране, без рестарта
    val skin = ControlSkins.byId(SettingsStore.controlSkin.collectAsState().value)
    // pointerInput-корутины живут дольше рекомпозиций — колбэки и координаты
    // (сетка комбо перестраивается прямо во время перетаскивания) читаем
    // через rememberUpdatedState, иначе корутина держит лямбду первого состава
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentComboDrag by rememberUpdatedState(onComboDragPx)
    val currentX by rememberUpdatedState(xPx)
    val currentY by rememberUpdatedState(yPx)
    // Вид кнопки — чистая функция [buttonLook] (скин + слайдер + нажатие):
    // форма/цвета/рамка/каптионы для ВСЕХ кнопок, включая комбо
    val look = buttonLook(skin, spec.id, spec.label, opacity, pressed)
    // длинное имя комбо-кнопки — мелкий шрифт в две строки (см. Text ниже)
    val isCombo = spec.id.startsWith("K")
    // Форму задаёт скин (круг для A/B, капсула для системных пилюль в классике);
    // в «Пикселе» всё угловатое. Логика нажатий и размеры от формы не зависят
    val shape = when (look.shape) {
        SkinShape.CIRCLE -> CircleShape
        SkinShape.PILL -> RoundedCornerShape(12.dp)
        SkinShape.SQUARE -> RoundedCornerShape(3.dp)
    }

    // Картинка контрола или null — тогда параметрические форма/цвета.
    // Разветвление ТОЛЬКО визуальное: размер, зона касаний и логика нажатий
    // одинаковы (hit-test читает спеки, а не скин)
    val imgRes = look.imageRes
    val decoration = if (imgRes == null) {
        Modifier
            .clip(shape)
            .background(look.fill)
            .border(look.borderWidthDp.dp, look.borderColor, shape)
    } else {
        Modifier // картинку рисует Image внутри — форма/цвета не применяются
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(xPx, yPx) }
            .size(sizeDp.dp)
            .then(
                if (editing) {
                    // Редактор: тап — выделить блок. Тянуть: обычная кнопка
                    // двигает СВОЙ блок; комбо-кнопка — переставляется сама
                    // (порядок в группе / перенос между группами). Всё
                    // состояние читаем ВНУТРИ колбэка на момент события:
                    // pointerInput не пересоздаётся при рекомпозиции, поэтому
                    // захваченные «на старте» значения устаревают.
                    // В игровом режиме касаний НЕТ: их маршрутизирует группа
                    // (FingerRouter в ControlsLayer) — палец может скользить
                    // между кнопками, перебрасывая нажатие с одной на другую.
                    if (onComboDragPx != null) {
                        Modifier
                            .pointerInput(block) {
                                detectTapGestures(onTap = { currentOnSelect() })
                            }
                            .pointerInput(parentPx, block) {
                                detectDragGestures(
                                    onDragStart = { currentOnSelect() },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentComboDrag?.invoke(
                                            currentX + change.position.x,
                                            currentY + change.position.y
                                        )
                                    }
                                )
                            }
                    } else {
                        Modifier
                            .pointerInput(block) {
                                detectTapGestures(onTap = { currentOnSelect() })
                            }
                            .pointerInput(parentPx, block) {
                                detectDragGestures(
                                    onDragStart = { currentOnSelect() },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        moveGroupBy(
                                            store, block, dragAmount.x, dragAmount.y, 1f,
                                            parentPx, density, 0f
                                        )
                                    }
                                )
                            }
                    }
                } else {
                    Modifier
                }
            )
            .then(decoration),
        contentAlignment = Alignment.Center
    ) {
        if (imgRes != null) {
            // Картинка скина: Fit — корректный aspect-ratio при любом размере
            // контрола; альфа = слайдер «Видимость», нажатие подсвечивает
            // картинку тем же бустом, что заливку обычной кнопки
            Image(
                painter = painterResource(imgRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = ControlSkin.buttonImageAlpha(opacity, pressed),
                modifier = Modifier.fillMaxSize()
            )
        }
        // Каптионный режим скина (решение в [buttonLook]): подпись живёт
        // НАД кнопкой (на «корпусе»), внутри кнопки текста нет — ДАЖЕ если
        // каптион пуст («A»/«B» гаджета: буква запечена в фото-кнопку,
        // дублировать её текстом нельзя). Касаются только физических
        // кнопок — у комбо-кнопок подписи пользовательские, всегда внутри.
        // Каптион — чистая отрисовка (Text без pointerInput, вне бокса):
        // зона нажатия остаётся ровно кнопкой, hit-test не меняется
        if (look.captionText.isNotEmpty()) {
            Text(
                text = look.captionText,
                color = look.labelColor,
                fontWeight = FontWeight.Bold,
                fontFamily = if (skin.monospace) FontFamily.Monospace else null,
                fontSize = 9.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-15).dp)
            )
        }
        if (!look.captionMode) {
            Text(
                text = spec.label,
                color = look.labelColor,
                fontWeight = FontWeight.Bold,
                fontFamily = if (skin.monospace) FontFamily.Monospace else null,
                fontSize = when {
                    // имя комбо-кнопки длинное — мелкий шрифт в две строки
                    isCombo -> 9.sp
                    sizeDp < 50f -> 10.sp
                    else -> 18.sp
                },
                textAlign = if (isCombo) TextAlign.Center else TextAlign.Unspecified,
                lineHeight = if (isCombo) 10.sp else TextUnit.Unspecified
            )
        }
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
    val density = LocalDensity.current
    val dpadBaseR by SettingsStore.dpadSize.collectAsState()
    // Слайдер «Видимость» действует и на крестовину (обе — P1 и P2)
    val opacity by SettingsStore.controlsOpacity.collectAsState()
    val alphas = ControlAlphas.of(opacity)
    // Скин крестовины: collectAsState — перекраска без рестарта (см. GameButton)
    val skin = ControlSkins.byId(SettingsStore.controlSkin.collectAsState().value)
    val g = store.group(gid) // recomposition при изменении позиции/масштаба
    val st = remember { DpadState() }
    // см. GameButton: pointerInput живёт дольше рекомпозиций — читаем
    // актуальные колбэки через rememberUpdatedState
    val currentOnBits by rememberUpdatedState(onBits)
    val currentOnSelect by rememberUpdatedState(onSelect)

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
                                    onTap = { currentOnSelect(gid) }
                                )
                            }
                            .pointerInput(parentPx, dpadBaseR) {
                                detectDragGestures(
                                    onDragStart = { currentOnSelect(gid) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        moveGroupBy(
                                            store, BlockRef(gid),
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
                                        currentOnBits(bits)
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
                                currentOnBits(0)
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
                        store, BlockRef(gid), dx, dy, 1f,
                        parentPx, density, dpadBaseR
                    )
                }
            )
        }

        // Картинка крестовины (image-скин) — целым блоком: Fit сохраняет
        // aspect-ratio, альфу двигает слайдер «Видимость» (вес крестовины).
        // Сама картинка не перехватывает касания — зона прежняя
        if (skin.imgDpad != null) {
            Image(
                painter = painterResource(skin.imgDpad),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = alphas.dpadStroke,
                modifier = Modifier
                    .offset { IntOffset((cx - rPx).toInt(), (cy - rPx).toInt()) }
                    .size((rPx * 2f / density.density).dp)
            )
        }

        // Поверх: прижатые направления (image-скин) или вся параметрическая
        // крестовина (классика/неон/пиксель/тёмный)
        Canvas(Modifier.fillMaxSize()) {
            drawDpad(Offset(cx, cy), rPx, st.bits, alphas, skin)
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

/**
 * Отрисовка крестовины. Все альфы — из [a] ([ControlAlphas]): слайдер
 * «Видимость» управляет и крестовиной, а не только кнопками. Цвета и стиль
 * — из скина [skin]: image-скин рисует только подсветку прижатых направлений
 * (саму картинку выводит Image-слой в [DpadLayer]); «Классический/Неон/
 * Тёмный» — круг со стрелками, «Пиксель» — цельный крест как на Dendy-геймпаде.
 */
private fun DrawScope.drawDpad(center: Offset, r: Float, bits: Int, a: ControlAlphas, skin: ControlSkin) {
    if (skin.imgDpad != null) {
        drawDpadPressedArrows(center, r, bits, a, skin)
        return
    }
    when (skin.dpadVisual) {
        DpadVisual.TRIANGLES -> drawDpadTriangles(center, r, bits, a, skin)
        DpadVisual.CROSS -> drawDpadCross(center, r, bits, a, skin)
    }
}

/** Треугольная стрелка направления — общая для классики и подсветки image-скина. */
private fun dpadArrowPath(center: Offset, r: Float, angle: Double): Path {
    val rad = Math.toRadians(angle)
    val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
    val perp = Offset(-dir.y, dir.x)
    val tip = center + dir * (r * 0.95f)
    val b1 = center + dir * (r * 0.52f) + perp * (r * 0.22f)
    val b2 = center + dir * (r * 0.52f) - perp * (r * 0.22f)
    return Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(b1.x, b1.y)
        lineTo(b2.x, b2.y)
        close()
    }
}

/**
 * Подсветка нажатых направлений для image-крестовины: картинка статична,
 * поэтому нажатие видно по активным стрелкам поверх неё (цвет —
 * [ControlSkin.dpadArrowActive] скина, альфа — из слайдера).
 */
private fun DrawScope.drawDpadPressedArrows(center: Offset, r: Float, bits: Int, a: ControlAlphas, skin: ControlSkin) {
    val active = skin.dpadArrowActive.copy(alpha = a.dpadArrowActive)
    if (bits and InputState.RIGHT != 0) drawPath(dpadArrowPath(center, r, 0.0), active)
    if (bits and InputState.DOWN != 0) drawPath(dpadArrowPath(center, r, 90.0), active)
    if (bits and InputState.LEFT != 0) drawPath(dpadArrowPath(center, r, 180.0), active)
    if (bits and InputState.UP != 0) drawPath(dpadArrowPath(center, r, 270.0), active)
}

/** Круг с треугольными стрелками — классический вид (цвета — из скина). */
private fun DrawScope.drawDpadTriangles(center: Offset, r: Float, bits: Int, a: ControlAlphas, skin: ControlSkin) {
    drawCircle(skin.dpadFill.copy(alpha = a.dpadFill), radius = r, center = center)
    drawCircle(skin.dpadStroke.copy(alpha = a.dpadStroke), radius = r, center = center, style = Stroke(2.dp.toPx()))

    fun arrow(angle: Double, active: Boolean) {
        drawPath(
            dpadArrowPath(center, r, angle),
            if (active) skin.dpadArrowActive.copy(alpha = a.dpadArrowActive)
            else skin.dpadArrow.copy(alpha = a.dpadArrow)
        )
    }

    arrow(0.0, (bits and InputState.RIGHT) != 0)
    arrow(90.0, (bits and InputState.DOWN) != 0)
    arrow(180.0, (bits and InputState.LEFT) != 0)
    arrow(270.0, (bits and InputState.UP) != 0)
    drawCircle(skin.dpadDot.copy(alpha = a.dpadDot), radius = 3.dp.toPx(), center = center)
}

/**
 * Крестовина «Пиксель»: цельный плюс, как на настоящем Dendy-геймпаде —
 * угловатый контур из двух планок (полутолщина 0.36·R, плечи до 0.95·R),
 * прижатое плечо подсвечивается целиком. Подсветка — от кромки центрального
 * квадрата до кончика плеча, поэтому соседние плечи не накладываются друг
 * на друга и не уплотняют альфу; обводка рисуется последней — контур чёткий.
 */
private fun DrawScope.drawDpadCross(center: Offset, r: Float, bits: Int, a: ControlAlphas, skin: ControlSkin) {
    val t = r * 0.36f // полутолщина планки
    val outline = Path().apply {
        // Контур «плюса» — 12 вершин по часовой стрелке
        moveTo(center.x - r, center.y - t)
        lineTo(center.x - t, center.y - t)
        lineTo(center.x - t, center.y - r)
        lineTo(center.x + t, center.y - r)
        lineTo(center.x + t, center.y - t)
        lineTo(center.x + r, center.y - t)
        lineTo(center.x + r, center.y + t)
        lineTo(center.x + t, center.y + t)
        lineTo(center.x + t, center.y + r)
        lineTo(center.x - t, center.y + r)
        lineTo(center.x - t, center.y + t)
        lineTo(center.x - r, center.y + t)
        close()
    }
    drawPath(outline, skin.dpadFill.copy(alpha = a.dpadFill))

    val activeFill = skin.dpadArrowActive.copy(alpha = a.dpadArrowActive)
    fun armHorizontal(fromLeft: Boolean) {
        val left = if (fromLeft) center.x - r else center.x + t
        drawRect(activeFill, topLeft = Offset(left, center.y - t), size = Size(r - t, 2f * t))
    }
    fun armVertical(fromTop: Boolean) {
        val top = if (fromTop) center.y - r else center.y + t
        drawRect(activeFill, topLeft = Offset(center.x - t, top), size = Size(2f * t, r - t))
    }
    if (bits and InputState.LEFT != 0) armHorizontal(fromLeft = true)
    if (bits and InputState.RIGHT != 0) armHorizontal(fromLeft = false)
    if (bits and InputState.UP != 0) armVertical(fromTop = true)
    if (bits and InputState.DOWN != 0) armVertical(fromTop = false)

    drawPath(outline, skin.dpadStroke.copy(alpha = a.dpadStroke), style = Stroke(2.dp.toPx()))
}
