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
 * на КАЖДОМ событии заново определяет, над какой кнопкой находится палец:
 * при переезде с кнопки на кнопку старая отжимается, новая нажимается —
 * как на реальном геймпаде.
 *
 * Семантика:
 *  - Кнопка нажата, пока на ней находится ХОТЯ БЫ ОДИН палец (счётчик).
 *  - Палец «принадлежит» группе, только если начал касание НА её кнопке;
 *    старт мимо кнопок (крестовина, пустое место) — палец игнорируется
 *    до отпускания, чтобы не конфликтовать с обработчиком крестовины
 *    и другими группами.
 *  - Ушедший в пустоту (не на другую кнопку) палец отжимает кнопку.
 *
 * Класс НЕ зависит от Compose/Android — вся логика покрыта юнит-тестами
 * (app/src/test/.../FingerRouterTest.kt), которые прогоняют сценарии
 * касаний: down/move/up с несколькими пальцами одновременно.
 *
 * Методы потокобезопасности не требуют: все вызовы — из единственной
 * pointerInput-корутины своей группы.
 */
internal class FingerRouter(
    /** Кнопка в точке (x, y) px или null — вне кнопок группы. */
    private val hitTest: (Float, Float) -> String?,
    /** Нажатие/отжатие кнопки (InputState + подсветка). */
    private val onPress: (String, Boolean) -> Unit
) {
    // палец → кнопка под ним; запись null = палец отслеживается (стартовал
    // внутри жеста группы), но сейчас вне кнопок. LinkedHashMap — чтобы
    // порядок обхода кнопок (например, в releaseAll) был детерминированным.
    private val assigned = LinkedHashMap<Long, String?>()
    // кнопка → число находящихся на ней пальцев (есть только у нажатых)
    private val counts = LinkedHashMap<String, Int>()
    // пальцы, стартовавшие МИМО кнопок группы (крестовина, пустое место):
    // группа их не ведёт — иначе они нажимали бы кнопки при заезде на них,
    // конфликтуя с обработчиком крестовины и другими группами
    private val aliens = HashSet<Long>()

    /** Палец под управлением этой группы (начал касание на её кнопке)? */
    fun isTracked(pointerId: Long): Boolean = assigned.containsKey(pointerId)

    fun isPressed(button: String): Boolean = counts.containsKey(button)

    /**
     * Касание пальца [pointerId] в точке ([x], [y]).
     * Возвращает true, если палец попал в кнопку группы (под её управлением).
     * Повторный вызов для уже отслеживаемого пальца — no-op (движение
     * обрабатывает [move]).
     */
    fun down(pointerId: Long, x: Float, y: Float): Boolean {
        if (assigned.containsKey(pointerId)) return false
        val hit = hitTest(x, y)
        if (hit == null) {
            // старт мимо кнопок — чужой палец, игнорируем до отпускания
            aliens.add(pointerId)
            return false
        }
        assigned[pointerId] = hit
        bump(hit, +1)
        return true
    }

    /**
     * Перемещение пальца: переназначить кнопку по текущей точке.
     * Старая кнопка теряет палец (отжимается, если он был последним),
     * новая — получает. Палец вне кнопок группы не обрабатывается.
     * Возвращает true — палец под управлением группы.
     */
    fun move(pointerId: Long, x: Float, y: Float): Boolean {
        if (aliens.contains(pointerId)) return false
        if (!assigned.containsKey(pointerId)) return false
        val cur = assigned[pointerId]
        val hit = hitTest(x, y)
        if (hit == cur) return true
        if (cur != null) bump(cur, -1)
        assigned[pointerId] = hit
        if (hit != null) bump(hit, +1)
        return true
    }

    /**
     * Отпускание (или отмена системного жеста) пальца.
     * Возвращает true, если палец был под управлением группы.
     */
    fun up(pointerId: Long): Boolean {
        aliens.remove(pointerId)
        val cur = assigned.remove(pointerId)
        if (cur != null) bump(cur, -1)
        return cur != null
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
