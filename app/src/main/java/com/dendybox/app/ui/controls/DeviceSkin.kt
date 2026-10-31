package com.dendybox.app.ui.controls

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as CRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.Composable
import com.dendybox.app.emulator.EmulatorEngine
import com.dendybox.app.emulator.FRect

/**
 * Скин «корпуса» — декоративный фон-гаджет, ЗАЛИВАЮЩИЙ ВЕСЬ экран: сплошное
 * светлое тело лежит под контролами, окно вырезано ровно по игровому кадру
 * (even-odd — ни один пиксель игры не перекрывается), вокруг кадра —
 * волосяное кольцо безеля ([DeviceSkins.FRAME_PAD_PX] = 1px, отделяет кадр
 * от тела). Панель вокруг кадра, скругления и динамики убраны: по обратной
 * связи прежний корпус «съедал» поле игры (фрейм ужимался резервом под
 * отступ+зазор+безель) — теперь игра максимально большая, а корпус — фон
 * всего экрана.
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
 *  - z-порядок: слой НИЖЕ контролов и меню; игровой кадр он не перекрывает
 *    В ПРИНЦИПЕ — окно вырезано ровно по кадру, а движок держит тот же
 *    резерв ([EmulatorEngine.shellReservePx] = 1px), поэтому окно корпуса
 *    всегда совпадает с картинкой. Физически Canvas лежит над SurfaceView
 *    (иначе его не видно: поверхность непрозрачна), но рисуется только на
 *    полях letterbox — фактически корпус «под дисплеем»;
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
    /** Цвет тела корпуса (заливка всего экрана). */
    val bodyColor: Color,
    /** Кольцо безеля вокруг кадра (1px) — линия «экрана» на теле. */
    val bezelColor: Color
) {
    /** Все цвета скина одним списком — для инвариантов тестов. */
    fun colors(): List<Color> = listOf(bodyColor, bezelColor)

    /**
     * Скин с альфами, умноженными на [alpha] (0..1, значения вне — зажимаются):
     * слайдер «Видимость» применяется и к корпусу — тело и безель растворяются
     * вместе с кнопками. RGB не трогается; у выключенного скина ("none") цвета
     * не меняются — слой и так не рисуется. При alpha = 1 вид идентичен
     * исходному скину.
     */
    fun tinted(alpha: Float): DeviceSkin {
        if (!enabled) return this
        val a = alpha.coerceIn(0f, 1f)
        if (a >= 1f) return this
        fun tint(c: Color) = c.copy(alpha = c.alpha * a)
        return copy(
            bodyColor = tint(bodyColor),
            bezelColor = tint(bezelColor)
        )
    }
}

object DeviceSkins {

    /** Идентификатор «без корпуса» — значение по умолчанию (и запасное). */
    const val NONE_ID = "none"

    /**
     * Отступ игрового кадра от краёв экрана при активном корпусе, px (не dp —
     * фиксированная волосяная линия): движок оставляет его вокруг кадра
     * ([EmulatorEngine.shellReservePx]), чтобы кадр не касался кромок и кольцо
     * безеля всегда читалось. Картинка при этом остаётся максимально большой:
     * целый масштаб сохраняется на всех экранах, где поля letterbox >= 2px,
     * и лишь там, где кадр иначе касался бы краёв (высота, кратная 240),
     * сжимается всего на ~2px по высоте.
     */
    const val FRAME_PAD_PX = 1f

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
     * Резерв кадра для скина [skin], px: при активном корпусе — [FRAME_PAD_PX]
     * (1px, отступ кадра от краёв экрана), у «нет корпуса» — 0: классический
     * вид не меняется.
     */
    fun framePadPx(skin: DeviceSkin): Float = if (skin.enabled) FRAME_PAD_PX else 0f

    private fun none() = DeviceSkin(
        id = NONE_ID,
        title = "Нет",
        enabled = false,
        // цвета не используются (enabled = false), но непрозрачны — единый инвариант
        bodyColor = Color.Black,
        bezelColor = Color.Black
    )

    // ------------------------------------------------------------------
    // «Гаджет» — фон-гаджет по референсу: светлое тело во весь экран,
    // волосяной тёмный безель вокруг кадра. Корпус выбирается ВМЕСТЕ со
    // скином кнопок (forControlSkin) — id совпадает с ControlSkins.GADGET_ID.
    // ------------------------------------------------------------------
    private fun gadget() = DeviceSkin(
        id = ControlSkins.GADGET_ID,
        title = "Гаджет",
        enabled = true,
        bodyColor = Color(0xFFF2F1EC),
        bezelColor = Color(0xFF23232A)
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
 * Отрисовка корпуса на области [area]: тело — ВЕСЬ экран минус «окно»
 * (even-odd — окно прозрачно, сквозь него видна игра), безель — волосяное
 * кольцо 1px между окном и кадром (линия «экрана» на светлом теле).
 * Всё зажимается в границы поверхности (см. [shellRects]).
 */
internal fun DrawScope.drawShell(area: Size, frame: RectF, skin: DeviceSkin) {
    val (_, win) = shellRects(
        area.width, area.height,
        FRect(frame.left, frame.top, frame.right, frame.bottom),
        DeviceSkins.FRAME_PAD_PX
    )
    if (win.width <= 0f || win.height <= 0f) return

    // 1. Тело: весь экран минус окно (even-odd) — корпус заливает весь экран,
    //    в окне видна игра
    val bodyPath = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(CRect(0f, 0f, area.width, area.height))
        addRect(CRect(win.left, win.top, win.right, win.bottom))
    }
    drawPath(bodyPath, skin.bodyColor)

    // 2. Безель: кольцо между окном и кадром (1px) — тонкая тёмная линия,
    //    отделяющая кадр от тела
    val innerL = frame.left.coerceIn(win.left, win.right)
    val innerT = frame.top.coerceIn(win.top, win.bottom)
    val innerR = frame.right.coerceIn(win.left, win.right)
    val innerB = frame.bottom.coerceIn(win.top, win.bottom)
    if (innerL > win.left || innerT > win.top || innerR < win.right || innerB < win.bottom) {
        val bezelPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(CRect(win.left, win.top, win.right, win.bottom))
            addRect(CRect(innerL, innerT, innerR, innerB))
        }
        drawPath(bezelPath, skin.bezelColor)
    }
}

/**
 * Геометрия корпуса — ЧИСТАЯ функция (px, JVM-тестируемая): тело — ВСЯ
 * поверхность (корпус заливает экран целиком), окно — кадр, раздвинутый на
 * толщину безеля [padPx] и зажатый в границы поверхности. Пиксели игры не
 * перекрываются: окно всегда содержит кадр (кадр от движка приходит в
 * границах поверхности, а с резервом [DeviceSkins.FRAME_PAD_PX] он и вовсе
 * не касается краёв). Вырожденные входы (кадр больше поверхности) деградируют
 * безопасно: окно зажимается в поверхность, кольцо безеля не рисуется.
 */
internal fun shellRects(
    areaW: Float,
    areaH: Float,
    frame: FRect,
    padPx: Float
): Pair<FRect, FRect> {
    // «Окно» корпуса: кадр + безель, в границах поверхности
    val winL = (frame.left - padPx).coerceIn(0f, areaW)
    val winT = (frame.top - padPx).coerceIn(0f, areaH)
    val winR = (frame.right + padPx).coerceIn(winL, areaW)
    val winB = (frame.bottom + padPx).coerceIn(winT, areaH)

    // Тело: весь экран — корпус заливает поверхность целиком
    return FRect(0f, 0f, areaW, areaH) to FRect(winL, winT, winR, winB)
}
