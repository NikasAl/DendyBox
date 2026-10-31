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
import com.dendybox.app.emulator.EmulatorEngine
import com.dendybox.app.emulator.FRect

/**
 * Скин «корпуса» — декоративный слой-гаджет ПОД ВСЕЙ игровой поверхностью:
 * панель тела вокруг кадра игры и рамка-безель экрана; колонки отверстий
 * динамиков опциональны ([DeviceSkin.speakerCount], у «Гаджета» выключены —
 * отверстия в полях мешали и убраны по обратной связи).
 *
 * КОРПУС — ЧАСТЬ СКИНА КНОПОК: отдельного тумблера больше нет, корпус
 * выбирается вместе со скином ([DeviceSkins.forControlSkin]) — скин
 * «Гаджет» подразумевает подложку-корпус (без неё крестовина выглядит
 * плохо — единый дизайн), у остальных скинов корпуса нет.
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
 *  - смена скина живая: id хранится в SettingsStore.controlSkin (StateFlow),
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

    /**
     * Скин с альфами, умноженными на [alpha] (0..1, значения вне — зажимаются):
     * слайдер «Видимость» применяется и к корпусу — тело, контур, безель
     * (и динамики, если включены) растворяются вместе с кнопками. RGB не
     * трогается; у выключенного скина ("none") цвета не меняются — слой и
     * так не рисуется. При alpha = 1 вид идентичен исходному скину.
     */
    fun tinted(alpha: Float): DeviceSkin {
        if (!enabled) return this
        val a = alpha.coerceIn(0f, 1f)
        if (a >= 1f) return this
        fun tint(c: Color) = c.copy(alpha = c.alpha * a)
        return copy(
            bodyColor = tint(bodyColor),
            bodyStroke = tint(bodyStroke),
            bezelColor = tint(bezelColor),
            speakerColor = tint(speakerColor)
        )
    }
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

    /**
     * Корпус для скина кнопок [controlSkinId]: корпус — часть скина, у
     * «Гаджета» ([ControlSkins.GADGET_ID]) он есть, у остальных скинов нет.
     * Неизвестный id — «нет корпуса» (тот же фолбэк, что у [byId]).
     */
    fun forControlSkin(controlSkinId: String): DeviceSkin =
        if (controlSkinId == ControlSkins.GADGET_ID) byId(ControlSkins.GADGET_ID) else DEFAULT

    /**
     * Резерв кадра под корпус [skin] в dp: отступ тела + зазор + безель —
     * столько движок оставляет вокруг кадра ([EmulatorEngine.shellReservePx]),
     * чтобы корпус целиком вмещал картинку даже при почти нулевых полях
     * letterbox. У выключенного скина («нет корпуса») резерва нет — 0.
     */
    fun reserveDp(skin: DeviceSkin): Float =
        if (skin.enabled) skin.bodyMarginDp + skin.bezelGapDp + skin.bezelWidthDp else 0f

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
    // Корпус выбирается ВМЕСТЕ со скином кнопок (forControlSkin) —
    // id совпадает с ControlSkins.GADGET_ID.
    // ------------------------------------------------------------------
    private fun gadget() = DeviceSkin(
        id = ControlSkins.GADGET_ID,
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
 *
 * [alpha] — вес слоя из слайдера «Видимость» ([ControlAlphas.shellAlpha]):
 * при базовом значении слайдера корпус рисуется как спроектирован
 * (полностью непрозрачные краски), к минимуму — равномерно растворяется
 * вместе с кнопками; выше базы плотнее не становится (краски и так
 * непрозрачны). Подложка (imgBlock) и текстуры кнопок/крестовины движутся
 * тем же слайдером своими весами — весь «стиль корпуса» прозрачен целиком.
 */
@Composable
fun DeviceShellLayer(framePx: RectF, skin: DeviceSkin, alpha: Float = 1f) {
    if (!skin.enabled) return
    Canvas(Modifier.fillMaxSize()) {
        drawShell(size, framePx, skin.tinted(alpha))
    }
}

/**
 * Отрисовка корпуса в области [area]: тело (внешний скруглённый
 * прямоугольник минус «окно» кадра, even-odd), контур тела, безель
 * (кольцо между окном и кадром) и колонки отверстий динамиков в
 * свободных полосах слева/справа от окна. Всё зажимается в границы
 * поверхности (см. [shellRects]); на узких полосах динамики не рисуются.
 */
internal fun DrawScope.drawShell(area: Size, frame: RectF, skin: DeviceSkin) {
    val m = skin.bodyMarginDp.dp.toPx()
    val gap = skin.bezelGapDp.dp.toPx()
    val bezel = skin.bezelWidthDp.dp.toPx()
    val (body, win) = shellRects(
        area.width, area.height,
        FRect(frame.left, frame.top, frame.right, frame.bottom),
        m, gap, bezel
    )
    if (win.width <= 0f || win.height <= 0f) return
    val corner = skin.bodyCornerDp.dp.toPx()

    // 1. Тело: внешний прямоугольник минус окно (even-odd — окно прозрачно,
    //    сквозь него видна игра)
    val bodyPath = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(
            RoundRect(body.left, body.top, body.right, body.bottom, CornerRadius(corner))
        )
        addRect(CRect(win.left, win.top, win.right, win.bottom))
    }
    drawPath(bodyPath, skin.bodyColor)

    // 2. Контур тела
    drawRoundRect(
        color = skin.bodyStroke,
        topLeft = Offset(body.left, body.top),
        size = Size(body.width, body.height),
        cornerRadius = CornerRadius(corner),
        style = Stroke(1.dp.toPx())
    )

    // 3. Безель: кольцо между окном и кадром (+зазор) — рамка «экрана»
    val innerL = (frame.left - gap).coerceIn(win.left, win.right)
    val innerT = (frame.top - gap).coerceIn(win.top, win.bottom)
    val innerR = (frame.right + gap).coerceIn(win.left, win.right)
    val innerB = (frame.bottom + gap).coerceIn(win.top, win.bottom)
    if (innerR - innerL > 0f && innerB - innerT > 0f) {
        val bezelPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addRoundRect(
                RoundRect(win.left, win.top, win.right, win.bottom, CornerRadius(skin.bezelCornerDp.dp.toPx()))
            )
            addRect(CRect(innerL, innerT, innerR, innerB))
        }
        drawPath(bezelPath, skin.bezelColor)
    }

    // 4. Динамики: колонки точек в свободных полосах между телом и окном
    drawSpeakers(body, win, skin)
}

/**
 * Геометрия корпуса — ЧИСТАЯ функция (px, JVM-тестируемая): окно корпуса —
 * кадр, раздвинутый на зазор+безель и зажатый в границы ПОВЕРХНОСТИ; тело —
 * окно, раздвинутое на отступ тела и зажатое в поверхность. Ключевое
 * отличие от прежней версии: раньше окно зажималось в тело с фиксированным
 * отступом, и на узких полях letterbox (высота поверхности, кратная 240, —
 * кадр касается краёв) безель/кадр резались краем тела — фрейм «выступал»
 * за корпус. Теперь НАОБОРОТ: тело расширяется вслед за окном вплоть до
 * края поверхности — корпус всегда накрывает кадр, насколько позволяет
 * экран. Резерв, оставляемый вокруг кадра самим движком
 * ([EmulatorEngine.shellReservePx]), гарантирует, что при активном корпусе
 * полей хватает на полный запас (отступ+зазор+безель).
 */
internal fun shellRects(
    areaW: Float,
    areaH: Float,
    frame: FRect,
    marginPx: Float,
    gapPx: Float,
    bezelPx: Float
): Pair<FRect, FRect> {
    // «Окно» корпуса: кадр + зазор + толщина безеля, в границах поверхности
    val winL = (frame.left - gapPx - bezelPx).coerceIn(0f, areaW)
    val winT = (frame.top - gapPx - bezelPx).coerceIn(0f, areaH)
    val winR = (frame.right + gapPx + bezelPx).coerceIn(winL, areaW)
    val winB = (frame.bottom + gapPx + bezelPx).coerceIn(winT, areaH)

    // Тело: окно + отступ, в границах поверхности (отступ сжимается,
    // но тело ВСЕГДА накрывает окно целиком)
    val bodyL = (winL - marginPx).coerceAtLeast(0f)
    val bodyT = (winT - marginPx).coerceAtLeast(0f)
    val bodyR = (winR + marginPx).coerceAtMost(areaW)
    val bodyB = (winB + marginPx).coerceAtMost(areaH)
    return FRect(bodyL, bodyT, bodyR, bodyB) to FRect(winL, winT, winR, winB)
}

/**
 * Отверстия динамиков: вертикальная колонка из [DeviceSkin.speakerCount]
 * точек по центру каждой свободной полосы (между телом и окном слева
 * и справа). Полоса уже трёх радиусов (маленький экран, кадр почти во всю
 * ширину) — колонка в ней не помещается и пропускается: деградация без
 * мусора.
 */
private fun DrawScope.drawSpeakers(
    body: FRect,
    win: FRect,
    skin: DeviceSkin
) {
    val n = skin.speakerCount
    if (n <= 0) return
    val r = skin.speakerRadiusDp.dp.toPx()
    val step = skin.speakerStepDp.dp.toPx()
    val cy = (body.top + body.bottom) / 2f
    val top = cy - (n - 1) * step / 2f
    for (cx in listOf((body.left + win.left) / 2f, (win.right + body.right) / 2f)) {
        val strip = minOf(cx - body.left, body.right - cx)
        if (strip < r * 3f) continue
        for (i in 0 until n) {
            drawCircle(skin.speakerColor, radius = r, center = Offset(cx, top + i * step))
        }
    }
}
