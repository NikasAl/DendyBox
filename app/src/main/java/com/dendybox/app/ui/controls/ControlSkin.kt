package com.dendybox.app.ui.controls

import androidx.compose.ui.graphics.Color
import com.dendybox.app.R
import com.dendybox.app.ui.AccentRed

/**
 * Скин кнопок управления — ЧИСТО визуальный конфиг: форма, цвета, шрифт,
 * стиль крестовины и ОПЦИОНАЛЬНО картинки из ресурсов приложения (PNG из
 * res/drawable-nodpi). Подхватывается в [GameButton] и DpadLayer
 * (ControlsOverlay.kt); текущий классический вид описан тем же конфигом —
 * захардкоженных цветов в отрисовке контролов не осталось.
 *
 * Инварианты (на них опирается отрисовка):
 *  - все базовые цвета ПОЛНОСТЬЮ непрозрачны (alpha = 1): прозрачность целиком
 *    принадлежит [ControlAlphas] (слайдер «Видимость»), скин только красит;
 *  - скин НЕ влияет на логику нажатий, размеры, раскладку и hit-test —
 *    меняются только Shape, Color, шрифт и картинка;
 *  - у контрола с картинкой параметры формы/цвета НЕ применяются: рендерится
 *    сама картинка (ContentScale.Fit — корректный aspect-ratio при любом
 *    размере контрола), альфу двигает слайдер «Видимость» как у всех;
 *  - смена скина живая: id хранится в SettingsStore.controlSkin (StateFlow),
 *    кнопки и крестовина перекрашиваются без перезапуска игры.
 *
 * НАБОР СКИНОВ — ТРИ: «Классический» (дефолт, параметрика), «Гаджет»
 * (пакет-скин по фото-референсам корпуса, единственный с корпусом —
 * DeviceSkins.forControlSkin) и «Приставка» (фото-крестовина и кнопки
 * классического геймпада, без корпуса). Прежние Неон/Пиксель/Тёмный/
 * Денди/Карбон/Аркада удалены вместе с ассетами; сохранённый в настройках
 * старый id резолвится в классику (byId) — отдельной миграции не нужно.
 *
 * Картинки задаются ТРЕМЯ способами (механизм общескиновый):
 *  - [imgDpad] — крестовина целым блоком (одна картинка на весь блок);
 *  - [imgButtons] — картинка на отдельную кнопку, ключ — id спека
 *    ("A", "B", "TA", "TB", "START", "SELECT", "SAVE", "LOAD"); комбо-кнопки
 *    (id "K*") картинок не имеют — их подписи пользовательские, рендер
 *    параметрический;
 *  - [imgBlock] — картинка-подложка под ВСЮ группу (панель джойстика),
 *    рисуется под кнопками, кнопки сохраняют свой обычный рендер
 *    (в текущем наборе не используется ни одним скином — механизм оставлен).
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
    /**
     * Заливка комбо-кнопок ("K*") или null — обычная [fill]. Переопределяется
     * скинами, где комбо должно выделяться цветом физической кнопки другого
     * типа: в «Гаджете» комбо красные, как турбо-кнопки-фото (подпись
     * комбо при этом светлая — см. [comboLabel]).
     */
    val comboFill: Color? = null,
    /** Цвет подписи комбо-кнопок ("K*") или null — обычная [label]. */
    val comboLabel: Color? = null,
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
    val dpadVisual: DpadVisual,
    /** Картинка крестовины целым блоком (res/drawable id) или null — параметрический рендер. */
    val imgDpad: Int? = null,
    /** Картинки кнопок: id спека ("A"…"SELECT") → drawable; комбо-кнопки ("K*") не картиночные. */
    val imgButtons: Map<String, Int> = emptyMap(),
    /** Картинка-подложка под всю группу (панель) или null. */
    val imgBlock: Int? = null,
    /**
     * Каптионы: подписи кнопок рисуются НАД кнопкой (на «корпусе»), а не
     * внутри неё — как надписи на теле гаджета. Касаются только физических
     * кнопок [IMAGEABLE_IDS]; у комбо-кнопок ("K*") подписи всегда внутри
     * (они пользовательские и длинные). Каптион — ЧИСТАЯ отрисовка: не
     * входит в зону нажатия и не меняет геометрию (hit-test читает спеки).
     */
    val captions: Boolean = false,
    /** Тексты каптионов поверх стандартных подписей спеков: id → текст. */
    val captionLabels: Map<String, String> = emptyMap()
) {
    /** Все цвета скина одним списком — для инвариантов тестов и перебора. */
    fun colors(): List<Color> = listOf(
        fill, border, turboBorder, comboBorder, label,
        dpadFill, dpadStroke, dpadArrow, dpadArrowActive, dpadDot
    )

    /**
     * Картинка кнопки с id спека [specId] или null. Возвращается ТОЛЬКО для
     * физических кнопок [IMAGEABLE_IDS]; у комбо-кнопок ("K*") подписи
     * пользовательские — картинка для них не задана по определению.
     */
    fun buttonImage(specId: String): Int? =
        if (specId in IMAGEABLE_IDS) imgButtons[specId] else null

    /**
     * Текст каптиона кнопки [specId]: переопределение из [captionLabels]
     * (например «TURBO A» для турбо-кнопки) или стандартная подпись спека.
     */
    fun captionFor(specId: String, fallback: String): String =
        captionLabels[specId] ?: fallback

    companion object {
        /**
         * Id спеков, которым разрешена картинка (см. [buttonImage]): кнопки
         * джойстика (A/B/турбо, SELECT/START), системные кнопки-действия
         * (PAUSE/MUTE/EDIT/SAVE/LOAD — группы «Пауза+Mute» и
         * «Редактор+Save+Load», см. SPECS_PAUSE_MUTE/SPECS_EDIT_SAVE_LOAD).
         */
        val IMAGEABLE_IDS = setOf(
            "A", "B", "TA", "TB", "START", "SELECT", "SAVE", "LOAD", "PAUSE", "MUTE", "EDIT"
        )

        /**
         * Альфа картинки кнопки — та же механика, что у заливки обычной кнопки:
         * базовое значение задаёт слайдер «Видимость», нажатие подсвечивает
         * картинку тем же бустом (+0.3, с капом 1). Турбо-приглушение (×0.75)
         * к картинкам не применяется — картинка и есть идентичность кнопки.
         * Результат зажат в 0..1 — функция тотальна при любом входе.
         */
        fun buttonImageAlpha(opacity: Float, pressed: Boolean): Float =
            (if (pressed) minOf(1f, opacity + 0.3f) else opacity).coerceIn(0f, 1f)
    }
}

object ControlSkins {

    /** Идентификатор скина по умолчанию (совпадает с ключом в SharedPreferences). */
    const val DEFAULT_ID = "classic"

    /** Идентификатор скина «Гаджет» — единственного с корпусом (см. DeviceSkins). */
    const val GADGET_ID = "gadget"

    /** Идентификатор скина «Приставка» (фото крестовины/кнопок геймпада). */
    const val CONSOLE_ID = "console"

    /**
     * Набор скинов — ровно три: «Классический» (первый — по нему resolve'ится
     * неизвестный id), «Гаджет» и «Приставка». Прочие удалены по итогам ревью.
     */
    val ALL: List<ControlSkin> = listOf(classic(), gadget(), console())

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
    // «Гаджет» — пакет-скин по фото-референсам настоящего корпуса
    // (scripts/photo_src): крестовина и кнопки — ВЫРЕЗКИ ИЗ СНИМКОВ,
    // буквы на кнопках запечены в картинки. Подписи над кнопками —
    // каптионы: у основных B/A их НЕТ (буква уже на кнопке — пустой
    // каптион не рисуется), над турбо — «TURBO» (как на корпусе),
    // над SELECT/START/SAVE/LOAD — их имена. Параметрические цвета
    // остаются только подсветке нажатых стрелок крестовины и КОМБО-
    // КНОПКАМ: они красные, как турбо-кнопки-фото (лицо #AC2829 и тёмная
    // кромка #8A1D1C сняты с того же референса, skin_gadget_btn_ta),
    // подпись комбо — светлая внутри (пользовательская, картинок нет).
    // Скин НЕ влияет на геометрию: зоны нажатий — по спекам, как у всех.
    // Единственный скин С КОРПУСОМ (DeviceSkins.forControlSkin) — подложка
    // вокруг кадра игры составляет с ним единый дизайн; у остальных
    // скинов корпуса нет.
    // ------------------------------------------------------------------
    private fun gadget() = ControlSkin(
        id = GADGET_ID,
        title = "Гаджет",
        shapeMain = SkinShape.CIRCLE,
        shapeMeta = SkinShape.PILL,
        fill = Color.White,
        border = Color(0xFF3A3A42),
        turboBorder = AccentRed,
        comboBorder = Color(0xFF8A1D1C),
        comboFill = Color(0xFFAC2829),
        comboLabel = Color.White,
        label = Color(0xFF3A3A42),
        monospace = false,
        dpadFill = Color.White,
        dpadStroke = Color(0xFF3A3A42),
        dpadArrow = Color(0xFF3A3A42),
        dpadArrowActive = AccentRed,
        dpadDot = Color(0xFF3A3A42),
        dpadVisual = DpadVisual.TRIANGLES,
        captions = true,
        captionLabels = mapOf(
            // буквы B/A запечены в фото-кнопки — каптионы над ними скрыты
            "A" to "",
            "B" to "",
            "TA" to "TURBO",
            "TB" to "TURBO",
            // у спека SELECT подпись «SEL» — каптион даёт полное имя
            "SELECT" to "SELECT"
        ),
        imgDpad = R.drawable.skin_gadget_dpad,
        imgButtons = mapOf(
            "A" to R.drawable.skin_gadget_btn_a,
            "B" to R.drawable.skin_gadget_btn_b,
            "TA" to R.drawable.skin_gadget_btn_ta,
            "TB" to R.drawable.skin_gadget_btn_tb,
            "START" to R.drawable.skin_gadget_pill,
            "SELECT" to R.drawable.skin_gadget_pill,
            "SAVE" to R.drawable.skin_gadget_pill,
            "LOAD" to R.drawable.skin_gadget_pill,
            // системные кнопки — та же пилюля, что SELECT/START:
            // единый вид ряда системных действий на корпусе
            "PAUSE" to R.drawable.skin_gadget_pill,
            "MUTE" to R.drawable.skin_gadget_pill,
            "EDIT" to R.drawable.skin_gadget_pill
        )
    )

    // ------------------------------------------------------------------
    // «Приставка» — фото-элементы классического геймпада
    // (scripts/photo_src/console_dpad.png — крестовина, console_btns.png —
    // круглые кнопки; вырезки делает scripts/gen_console_images.py).
    // Крестовина и основные кнопки (B/A и обе турбо — на референсе они
    // одинаковые пустые круги) — ВЫРЕЗКИ ИЗ СНИМКОВ; подписи рисует
    // приложение ВНУТРИ кнопок тёмным (каптионов нет: корпуса у скина
    // нет, подписи НАД кнопками были бы нечитаемы на игре). Параметрика
    // снята с тех же фото: лицо кнопки #837C6F, тёмный бевел #444036,
    // тело крестовины #9F9A8F, стрелки #56524C (значения семплирует
    // gen_console_images.py). SELECT/START/SAVE/LOAD и системные —
    // параметрические пилюли в цвет фото-кнопок. Комбо — обычные для
    // скина (светло-серые с красной рамкой); корпус отсутствует
    // (DeviceSkins.forControlSkin — только у «Гаджета»). Скин НЕ влияет
    // на геометрию: зоны нажатий — по спекам, как у всех.
    // ------------------------------------------------------------------
    private fun console() = ControlSkin(
        id = CONSOLE_ID,
        title = "Приставка",
        shapeMain = SkinShape.CIRCLE,
        shapeMeta = SkinShape.PILL,
        fill = Color(0xFF837C6F),
        border = Color(0xFF444036),
        turboBorder = AccentRed,
        comboBorder = AccentRed,
        label = Color(0xFF2E2B26),
        monospace = false,
        dpadFill = Color(0xFF9F9A8F),
        dpadStroke = Color(0xFF56524C),
        dpadArrow = Color(0xFF56524C),
        dpadArrowActive = AccentRed,
        dpadDot = Color(0xFF56524C),
        dpadVisual = DpadVisual.TRIANGLES,
        imgDpad = R.drawable.skin_console_dpad,
        imgButtons = mapOf(
            "A" to R.drawable.skin_console_btn,
            "B" to R.drawable.skin_console_btn,
            "TA" to R.drawable.skin_console_btn,
            "TB" to R.drawable.skin_console_btn
        )
    )
}
