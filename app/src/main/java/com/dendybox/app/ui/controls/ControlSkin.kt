package com.dendybox.app.ui.controls

import androidx.compose.ui.graphics.Color
import com.dendybox.app.ui.AccentRed

/**
 * Скин кнопок управления — ЧИСТО визуальный конфиг: форма, цвета, шрифт и
 * стиль крестовины. Подхватывается в [GameButton] (ControlsOverlay.kt) и
 * [drawDpad]; текущий классический вид описан тем же конфигом — захардкоженных
 * цветов в отрисовке контролов не осталось.
 *
 * Инварианты (на них опирается отрисовка):
 *  - все базовые цвета ПОЛНОСТЬЮ непрозрачны (alpha = 1): прозрачность целиком
 *    принадлежит [ControlAlphas] (слайдер «Видимость»), скин только красит;
 *  - скин НЕ влияет на логику нажатий, размеры, раскладку и hit-test —
 *    меняются только Shape, Color и шрифт подписи;
 *  - смена скина живая: id хранится в SettingsStore.controlSkin (StateFlow),
 *    кнопки и крестовина перекрашиваются без перезапуска игры.
 */
enum class SkinShape { CIRCLE, PILL, SQUARE }

/** Стиль крестовины: стрелки в круге (классика) или цельный крест (Dendy-геймпад). */
enum class DpadVisual { TRIANGLES, CROSS }

data class ControlSkin(
    val id: String,
    val title: String,
    /** Форма основных кнопок: A/B, турбо A′/B′ и комбо-кнопки. */
    val shapeMain: SkinShape,
    /** Форма SELECT/START. */
    val shapeMeta: SkinShape,
    /** Заливка всех кнопок. */
    val fill: Color,
    /** Рамка основных кнопок. */
    val border: Color,
    /** Рамка турбо-кнопок (в классике — красная подсветка турбо). */
    val turboBorder: Color,
    /** Рамка программируемых комбо-кнопок. */
    val comboBorder: Color,
    /** Цвет подписи на кнопках. */
    val label: Color,
    /** Моноширинный шрифт подписей (пиксель/ретро). */
    val monospace: Boolean,
    // Крестовина: заливка, обводка, стрелки (обычная/активная), центральная точка
    val dpadFill: Color,
    val dpadStroke: Color,
    val dpadArrow: Color,
    val dpadArrowActive: Color,
    val dpadDot: Color,
    /** Стиль крестовины. */
    val dpadVisual: DpadVisual
) {
    /** Все цвета скина одним списком — для инвариантов тестов и перебора. */
    fun colors(): List<Color> = listOf(
        fill, border, turboBorder, comboBorder, label,
        dpadFill, dpadStroke, dpadArrow, dpadArrowActive, dpadDot
    )
}

object ControlSkins {

    /** Идентификатор скина по умолчанию (совпадает с ключом в SharedPreferences). */
    const val DEFAULT_ID = "classic"

    /** Набор скинов; ПЕРВЫЙ — классика: по нему resolve'ится неизвестный id. */
    val ALL: List<ControlSkin> = listOf(classic(), neon(), pixel(), dark())

    /** Классический скин (= дефолт). */
    val DEFAULT: ControlSkin get() = byId(DEFAULT_ID)

    /** Скин по id; неизвестный/пустой id → классика (например, старый билд). */
    fun byId(id: String): ControlSkin = ALL.firstOrNull { it.id == id } ?: ALL.first()

    // ------------------------------------------------------------------
    // «Классический» — прежний вид: белые круги/пилюли с рамкой, красная
    // подсветка турбо и активных стрелок. Значения повторяют константы,
    // которые раньше были захардкожены в GameButton/drawDpad.
    // ------------------------------------------------------------------
    private fun classic() = ControlSkin(
        id = DEFAULT_ID,
        title = "Классический",
        shapeMain = SkinShape.CIRCLE,
        shapeMeta = SkinShape.PILL,
        fill = Color.White,
        border = Color.White,
        turboBorder = AccentRed,
        comboBorder = AccentRed,
        label = Color.White,
        monospace = false,
        dpadFill = Color.White,
        dpadStroke = Color.White,
        dpadArrow = Color.White,
        dpadArrowActive = AccentRed,
        dpadDot = Color.White,
        dpadVisual = DpadVisual.TRIANGLES
    )

    // ------------------------------------------------------------------
    // «Неон» — контурные кнопки с цветной обводкой; заливка тёмная,
    // на фоне игры выглядит почти прозрачной.
    // ------------------------------------------------------------------
    private fun neon() = ControlSkin(
        id = "neon",
        title = "Неон",
        shapeMain = SkinShape.CIRCLE,
        shapeMeta = SkinShape.PILL,
        fill = Color(0xFF0B1026),
        border = Color(0xFF00E5FF),
        turboBorder = Color(0xFFFF2E88),
        comboBorder = Color(0xFFB388FF),
        label = Color(0xFF9BEBFF),
        monospace = false,
        dpadFill = Color(0xFF0B1026),
        dpadStroke = Color(0xFF00E5FF),
        dpadArrow = Color(0xFF00E5FF),
        dpadArrowActive = Color(0xFFFF2E88),
        dpadDot = Color(0xFF00E5FF),
        dpadVisual = DpadVisual.TRIANGLES
    )

    // ------------------------------------------------------------------
    // «Пиксель» — угловатые квадратные кнопки в цвете пластика Dendy,
    // тёмные подписи моноширинным шрифтом, крестовина — цельный крест.
    // ------------------------------------------------------------------
    private fun pixel() = ControlSkin(
        id = "pixel",
        title = "Пиксель",
        shapeMain = SkinShape.SQUARE,
        shapeMeta = SkinShape.SQUARE,
        fill = Color(0xFFE8E4D4),
        border = Color(0xFF2E2E2E),
        turboBorder = Color(0xFFC62828),
        comboBorder = Color(0xFFC62828),
        label = Color(0xFF2E2E2E),
        monospace = true,
        dpadFill = Color(0xFFE8E4D4),
        dpadStroke = Color(0xFF2E2E2E),
        dpadArrow = Color(0xFF2E2E2E),
        dpadArrowActive = Color(0xFFC62828),
        dpadDot = Color(0xFF2E2E2E),
        dpadVisual = DpadVisual.CROSS
    )

    // ------------------------------------------------------------------
    // «Тёмный» — тёмная полупрозрачная заливка (на фоне игры) со светлой
    // рамкой и светлыми подписями.
    // ------------------------------------------------------------------
    private fun dark() = ControlSkin(
        id = "dark",
        title = "Тёмный",
        shapeMain = SkinShape.CIRCLE,
        shapeMeta = SkinShape.PILL,
        fill = Color(0xFF15151C),
        border = Color(0xFFB8B8C4),
        turboBorder = Color(0xFFFF8A80),
        comboBorder = Color(0xFFFF8A80),
        label = Color(0xFFF2F2F7),
        monospace = false,
        dpadFill = Color(0xFF15151C),
        dpadStroke = Color(0xFFB8B8C4),
        dpadArrow = Color(0xFFB8B8C4),
        dpadArrowActive = Color(0xFFFF8A80),
        dpadDot = Color(0xFFB8B8C4),
        dpadVisual = DpadVisual.TRIANGLES
    )
}
