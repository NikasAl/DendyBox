package com.dendybox.app.ui.controls

/**
 * Маршрутизатор пальцев по кнопкам группы (кластер A/B, Select/Start).
 *
 * Проблема, которую решает: экранная кнопка, обрабатывающая касания на себе,
 * «привязывает» палец в момент касания — пока палец не отпущен, события идут
 * только ей, и перенос пальца из области A в область B не нажимает B (а A
 * остаётся зажатой). Быстрые серии «сначала A, потом B без отрыва пальца»
 * в играх становятся невозможны.
 *
 * Решение: обработка касаний поднята на уровень группы, а [FingerRouter]
 * на КАЖДОМ событии заново определяет, какие кнопки накрыты пальцем:
 * при переезде с кнопки на кнопку старая отжимается, новая нажимается —
 * как на реальном геймпаде.
 *
 * Помимо центра пальца учитывается «пятно касания» ([patchTest]): Android
 * сообщает эллипс контакта пальца с экраном, и если он дотягивается до
 * соседней кнопки, она нажимается ВМЕСТЕ с центральной — одним пальцем
 * можно держать A+B (палец между кнопками или широкое прижатие).
 *
 * Семантика:
 *  - Кнопка нажата, пока на ней находится ХОТЯ БЫ ОДИН палец (счётчик);
 *    неважно, центром он её накрыл или краем пятна.
 *  - Палец «принадлежит» группе, только если при КАСАНИИ накрыл хоть одну
 *    её кнопку (центром или пятном); старт мимо кнопок (крестовина, пустое
 *    место) — палец игнорируется до отпускания, чтобы не конфликтовать
 *    с обработчиком крестовины и другими группами.
 *  - Ушедший в пустоту палец отжимает все свои кнопки.
 *
 * Класс НЕ зависит от Compose/Android — вся логика покрыта юнит-тестами
 * (app/src/test/.../FingerRouterTest.kt), которые прогоняют сценарии
 * касаний: down/move/up с несколькими пальцами одновременно.
 *
 * Вызывается ядром сырых касаний (input/TouchRouterCore) с главного потока;
 * на каждый палец события приходят ПО ПОРЯДКУ диспетча — с точным временем
 * исходного MotionEvent (оно уходит в колбэк onPress подписчиком ядра).
 */
internal class FingerRouter(
    /** Кнопка под ЦЕНТРОМ пальца (x, y) px или null — вне кнопок группы. */
    private val hitTest: (Float, Float) -> String?,
    /**
     * Кнопки, накрытые «пятном касания» пальца: (x, y) px — центр пальца,
     * [r] px — радиус ядра пятна. Вызывается только при r > 0; устройства,
     * не сообщающие размер касания, передают r = 0 и функция не зовётся
     * вовсе. null — ко-нажатие пятном отключено (поведение «только центр»).
     */
    private val patchTest: ((Float, Float, Float) -> Set<String>)? = null,
    /** Нажатие/отжатие кнопки (InputState + подсветка). */
    private val onPress: (String, Boolean) -> Unit
) {
    // палец → набор кнопок под ним; пустой набор = палец отслеживается
    // (стартовал внутри жеста группы), но сейчас не накрывает ни одной
    // кнопки. LinkedHashMap — детерминированный порядок обхода (releaseAll).
    private val assigned = LinkedHashMap<Long, Set<String>>()
    // кнопка → число накрывающих её пальцев (есть только у нажатых)
    private val counts = LinkedHashMap<String, Int>()
    // пальцы, стартовавшие МИМО кнопок группы (крестовина, пустое место):
    // группа их не ведёт — иначе они нажимали бы кнопки при заезде на них,
    // конфликтуя с обработчиком крестовины и другими группами
    private val aliens = HashSet<Long>()

    /** Палец под управлением этой группы (касание накрыло её кнопку)? */
    fun isTracked(pointerId: Long): Boolean = assigned.containsKey(pointerId)

    fun isPressed(button: String): Boolean = counts.containsKey(button)

    /**
     * Кнопки под точкой (центр + пятно касания) БЕЗ изменения состояния —
     * проба. Используется ядром сырых касаний (TouchRouterCore), чтобы решить,
     * какая группа забирает палец, и для диагностического лога.
     */
    internal fun buttonsAt(x: Float, y: Float, patchR: Float): Set<String> =
        desiredAt(x, y, patchR)

    /**
     * Касание пальца [pointerId] в точке ([x], [y]) px, ядро пятна —
     * [patchR] px (0 = устройство не сообщает размер касания).
     * Возвращает true, если палец попал в кнопку группы (под её управлением).
     * Повторный вызов для уже отслеживаемого пальца — no-op (движение
     * обрабатывает [move]).
     */
    fun down(pointerId: Long, x: Float, y: Float, patchR: Float = 0f): Boolean {
        if (assigned.containsKey(pointerId)) return false
        val desired = desiredAt(x, y, patchR)
        if (desired.isEmpty()) {
            // старт мимо кнопок — чужой палец, игнорируем до отпускания
            aliens.add(pointerId)
            return false
        }
        assigned[pointerId] = desired
        desired.forEach { bump(it, +1) }
        return true
    }

    /**
     * Перемещение пальца: пересчитать набор кнопок по текущей точке и
     * пятну касания. Кнопки, ушедшие из-под пальца, теряют его (отжимаются,
     * если он был последним), вошедшие — получают. Палец вне кнопок группы
     * события не нажимает. Возвращает true — палец под управлением группы.
     */
    fun move(pointerId: Long, x: Float, y: Float, patchR: Float = 0f): Boolean {
        if (aliens.contains(pointerId)) return false
        val cur = assigned[pointerId] ?: return false
        val desired = desiredAt(x, y, patchR)
        if (desired != cur) {
            (cur - desired).forEach { bump(it, -1) }
            (desired - cur).forEach { bump(it, +1) }
            assigned[pointerId] = desired
        }
        return true
    }

    /**
     * Отпускание (или отмена системного жеста) пальца.
     * Возвращает true, если палец был под управлением группы.
     */
    fun up(pointerId: Long): Boolean {
        aliens.remove(pointerId)
        val cur = assigned.remove(pointerId) ?: return false
        cur.forEach { bump(it, -1) }
        return true
    }

    /**
     * Отжать ВСЁ, что нажато этой группой (сброс маршрута).
     * Вызывается при отмене корутины pointerInput (уход с экрана, смена
     * размера окна, пересоздание слоя) — иначе кнопка залипла бы навсегда.
     */
    fun releaseAll() {
        for (b in counts.keys.toList()) onPress(b, false)
        counts.clear()
        assigned.clear()
        aliens.clear()
    }

    /**
     * Полный набор кнопок под пальцем: центральная (hitTest) + накрываемые
     * пятном касания (patchTest, только при r > 0).
     */
    private fun desiredAt(x: Float, y: Float, patchR: Float): Set<String> {
        val res = LinkedHashSet<String>()
        hitTest(x, y)?.let { res.add(it) }
        if (patchR > 0f) patchTest?.let { res.addAll(it(x, y, patchR)) }
        return res
    }

    /**
     * Изменить счётчик пальцев на кнопке. События нажатия/отжатия — ТОЛЬКО
     * на переходе 0 <-> 1: второй палец на уже нажатой кнопке не дублирует
     * нажатие, уход одного из двух пальцев не отжимает кнопку.
     */
    private fun bump(button: String, delta: Int) {
        val before = counts[button] ?: 0
        val n = (before + delta).coerceAtLeast(0)
        when {
            before == 0 && n == 1 -> {
                counts[button] = n
                onPress(button, true)
            }
            before >= 1 && n == 0 -> {
                counts.remove(button)
                onPress(button, false)
            }
            n > 0 -> counts[button] = n
            else -> counts.remove(button)
        }
    }
}
