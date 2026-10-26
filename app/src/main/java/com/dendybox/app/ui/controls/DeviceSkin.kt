package com.dendybox.app.ui.controls

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as CRect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Скин «корпуса» — декоративный слой-гаджет ПОД ВСЕЙ игровой поверхностью:
 * панель тела вокруг кадра игры и рамка-безель экрана; колонки отверстий
 * динамиков опциональны ([DeviceSkin.speakerCount], у «Гаджета» выключены —
 * отверстия в полях мешали и убраны по обратной связи).
 *
 * Инварианты (как у [ControlSkin]):
 *  - слой ТОЛЬКО рисуется: ни одного pointerInput/кликабельного модификатора —
 *    роутинг касаний, зоны нажатий и перемещаемость групп не затрагиваются
 *    (касание проходит сквозь слой к крестовине/кнопкам/меню);
 *  - геометрия слоя привязывается к прямоугольнику игрового кадра
 *    (EmulatorEngine.frameRect) — картинка игры полностью видима в «окне»;
 *  - скин НЕ влияет на логику, размеры и раскладку контролов: группы как
 *    двигались, так и двигаются; дефолт (id "none", [DeviceSkins.DEFAULT])
 *    слой не рисует вовсе — классический вид без изменений;
 *  - смена скина живая: id хранится в SettingsStore.deviceSkin (StateFlow),
 *    корпус появляется/исчезает без перезапуска игры.
 *
 * Совместимость с «фон — прокрутка игры»: игровая поверхность (SurfaceView)
 * остаётся нижним слоем и рисует кадр как раньше; корпус ложится ВЫШЕ
 * поверхности, закрывая только чёрные поля letterbox, а «окно» вырезается
 * ровно по кадру (even-odd) — ни один пиксель игры не перекрывается.
 */
data class DeviceSkin(
    val id: String,
    val title: String,
    /** false — слой не рисуется (классический вид, «фон — прокрутка игры»). */
    val enabled: Boolean,
    /** Цвет тела корпуса. */
    val bodyColor: Color,
    /** Контур тела корпуса. */
    val bodyStroke: Color,
    /** Рамка-безель вокруг экрана. */
    val bezelColor: Color,
    /** Отверстия динамиков. */
    val speakerColor: Color,
    /** Отступ корпуса от краёв поверхности, dp. */
    val bodyMarginDp: Float = 6f,
    /** Радиус скругления корпуса, dp. */
    val bodyCornerDp: Float = 26f,
    /** Зазор между кадром игры и безелем, dp. */
    val bezelGapDp: Float = 3f,
    /** Толщина безеля, dp. */
    val bezelWidthDp: Float = 8f,
    /** Радиус скругления безеля, dp. */
    val bezelCornerDp: Float = 10f,
    /** Отверстий в колонке динамика (с каждой стороны). */
    val speakerCount: Int = 7,
    /** Радиус отверстия, dp. */
    val speakerRadiusDp: Float = 2.4f,
    /** Шаг между отверстиями, dp. */
    val speakerStepDp: Float = 9f
) {
    /** Все цвета скина одним списком — для инвариантов тестов. */
    fun colors(): List<Color> = listOf(bodyColor, bodyStroke, bezelColor, speakerColor)
}

object DeviceSkins {

    /** Идентификатор «без корпуса» — значение по умолчанию (и запасное). */
    const val NONE_ID = "none"

    /** Набор скинов корпуса; ПЕРВЫЙ — «нет корпуса»: по нему resolve'ится неизвестный id. */
    val ALL: List<DeviceSkin> = listOf(none(), gadget())

    /** Скин по умолчанию — корпус не рисуется (дефолтный вид не затронут). */
    val DEFAULT: DeviceSkin get() = byId(NONE_ID)

    /** Скин по id; неизвестный/пустой id → «нет корпуса» (например, старый билд). */
    fun byId(id: String): DeviceSkin = ALL.firstOrNull { it.id == id } ?: ALL.first()

    private fun none() = DeviceSkin(
        id = NONE_ID,
        title = "Нет",
        enabled = false,
        // цвета не используются (enabled = false), но непрозрачны — единый инвариант
        bodyColor = Color.Black,
        bodyStroke = Color.Black,
        bezelColor = Color.Black,
        speakerColor = Color.Black
    )

    // ------------------------------------------------------------------
    // «Гаджет» — корпус карманной консоли по референсу: светлое тело,
    // тёмный безель экрана. Динамики УБРАНЫ (speakerCount = 0): отверстия
    // в свободных полосах мешали и не читались — решение по обратной связи.
    // ------------------------------------------------------------------
    private fun gadget() = DeviceSkin(
        id = "gadget",
        title = "Гаджет",
        enabled = true,
        bodyColor = Color(0xFFF2F1EC),
        bodyStroke = Color(0xFFC6C4BA),
        bezelColor = Color(0xFF23232A),
        speakerColor = Color(0xFFDBD9CF),
        speakerCount = 0
    )
}

/**
 * Слой корпуса: Canvas на всю игровую поверхность. Точка входа из
 * GameScreen — прямоугольник кадра [framePx] (px поверхности, та же
 * математика, что у движка). НЕ содержит pointerInput — слой прозрачен
 * для касаний, весь ввод обслуживают слои выше (меню, крестовина, кнопки).
 */
@Composable
fun DeviceShellLayer(framePx: RectF, skin: DeviceSkin) {
    if (!skin.enabled) return
    Canvas(Modifier.fillMaxSize()) {
        drawShell(size, framePx, skin)
    }
}

/**
 * Отрисовка корпуса в области [area]: тело (внешний скруглённый
 * прямоугольник минус «окно» кадра, even-odd), контур тела, безель
 * (кольцо между окном и кадром) и колонки отверстий динамиков в
 * свободных полосах слева/справа от окна. Всё зажимается в границы
 * корпуса; на узких полосах (маленькие экраны) динамики не рисуются.
 */
internal fun DrawScope.drawShell(area: Size, frame: RectF, skin: DeviceSkin) {
    val m = skin.bodyMarginDp.dp.toPx()
    if (area.width <= 2 * m || area.height <= 2 * m) return
    val corner = skin.bodyCornerDp.dp.toPx()
    val gap = skin.bezelGapDp.dp.toPx()
    val bezel = skin.bezelWidthDp.dp.toPx()

    // «Окно» корпуса: кадр игры + зазор + толщина безеля, в границах тела
    val winL = (frame.left - gap - bezel).coerceAtLeast(m)
    val winT = (frame.top - gap - bezel).coerceAtLeast(m)
    val winR = (frame.right + gap + bezel).coerceAtMost(area.width - m)
    val winB = (frame.bottom + gap + bezel).coerceAtMost(area.height - m)
    if (winR - winL <= 0f || winB - winT <= 0f) return

    // 1. Тело: внешний прямоугольник минус окно (even-odd — окно прозрачно,
    //    сквозь него видна игра)
    val body = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(
            RoundRect(m, m, area.width - m, area.height - m, CornerRadius(corner))
        )
        addRect(CRect(winL, winT, winR, winB))
    }
    drawPath(body, skin.bodyColor)

    // 2. Контур тела
    drawRoundRect(
        color = skin.bodyStroke,
        topLeft = Offset(m, m),
        size = Size(area.width - 2 * m, area.height - 2 * m),
        cornerRadius = CornerRadius(corner),
        style = Stroke(1.dp.toPx())
    )

    // 3. Безель: кольцо между окном и кадром (+зазор) — рамка «экрана»
    val innerL = (frame.left - gap).coerceIn(winL, winR)
    val innerT = (frame.top - gap).coerceIn(winT, winB)
    val innerR = (frame.right + gap).coerceIn(winL, winR)
    val innerB = (frame.bottom + gap).coerceIn(winL, winB)
    if (innerR - innerL > 0f && innerB - innerT > 0f) {
        val bezelPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addRoundRect(
                RoundRect(winL, winT, winR, winB, CornerRadius(skin.bezelCornerDp.dp.toPx()))
            )
            addRect(CRect(innerL, innerT, innerR, innerB))
        }
        drawPath(bezelPath, skin.bezelColor)
    }

    // 4. Динамики: колонки точек в свободных полосах между телом и окном
    drawSpeakers(area, m, winL, winR, skin)
}

/**
 * Отверстия динамиков: вертикальная колонка из [DeviceSkin.speakerCount]
 * точек по центру каждой свободной полосы (слева и справа от окна).
 * Полоса уже трёх радиусов (маленький экран, кадр почти во всю ширину) —
 * колонка в ней не помещается и пропускается: деградация без мусора.
 */
private fun DrawScope.drawSpeakers(
    area: Size,
    marginPx: Float,
    winL: Float,
    winR: Float,
    skin: DeviceSkin
) {
    val n = skin.speakerCount
    if (n <= 0) return
    val r = skin.speakerRadiusDp.dp.toPx()
    val step = skin.speakerStepDp.dp.toPx()
    val cy = area.height / 2f
    val top = cy - (n - 1) * step / 2f
    val rightEdge = area.width - marginPx
    for (cx in listOf((marginPx + winL) / 2f, (winR + rightEdge) / 2f)) {
        val strip = minOf(cx - marginPx, rightEdge - cx)
        if (strip < r * 3f) continue
        for (i in 0 until n) {
            drawCircle(skin.speakerColor, radius = r, center = Offset(cx, top + i * step))
        }
    }
}
