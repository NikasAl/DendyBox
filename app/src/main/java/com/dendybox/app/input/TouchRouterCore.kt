package com.dendybox.app.input

import com.dendybox.app.ui.controls.FingerRouter

/**
 * Ядро маршрутизации СЫРЫХ касаний по экранным кнопкам — чистый Kotlin
 * (без Compose/Android), покрыт юнит-тестами с явным временем.
 *
 * ЗАЧЕМ оно вместо прежних Compose-зон (pointerInput на боксе группы):
 * Compose доставляет события узлу по «hit-path» каждого пальца и ведёт
 * жестовую машину (awaitEachGesture/awaitAllPointersUp) — на устройстве это
 * проявилось двумя дефектами: второй палец (A+B двумя пальцами) не всегда
 * доходил до зоны группы, а быстрые серии нажатий (десятки-сотни мс)
 * гасились/задерживались. Сырой поток MotionEvents содержит ВСЕ пальцы
 * КАЖДОГО события — маршрутизатор физически не может их не увидеть, а
 * press-событие уходит подписчику синхронно с диспетчем события (первый же
 * кадр эмулятора).
 *
 * Поток данных: MainActivity.dispatchTouchEvent → GamepadInput.onMotionEvent
 * → этот класс → FingerRouter (состояние пальцев и счётчики одной группы)
 * → колбэк подписчика (InputState + подсветка + диагностический лог).
 *
 * Самовосстановление «потерянных» пальцев: если событие КАСАНИЯ пальца по
 * какой-то причине не дошло до ядра (гонка привязки групп при старте игры,
 * проглоченный системой POINTER_DOWN), палец больше не остаётся мёртвым до
 * отпускания: его ПЕРВОЕ ЖЕ движение пробует взять в работу — нажатие
 * уходит с временем события движения. Пальцы, чьё касание БЫЛО обработано,
 * но попало мимо кнопок (крестовина, меню, пустота), как и прежде не
 * отслеживаются — скольжение такого пальца на кнопки ничего не нажимает
 * (семантика прежних зон сохранена).
 *
 * Все вызовы — с главного потока (dispatchTouchEvent): синхронизация не
 * нужна. Время — ev.eventTime (мс, monotonic uptime): подписчик получает
 * ТОЧНОЕ время события нажатия, никаких таймеров/дебаунсов внутри.
 *
 * @param onAdopt диагностический колбэк: палец взят в работу движением
 * (его касание не было обработано ядром) — для лога «adopt:» в GamepadInput.
 */
class TouchRouterCore(
    private val onAdopt: ((pointerId: Long, group: String, button: String, tMs: Long) -> Unit)? = null
) {
    /** Итог пробы: какая группа забрала бы палец и какая кнопка под ним. */
    data class DownInfo(val group: String?, val button: String?)

    private class BoundGroup(val id: String, val router: FingerRouter)

    // Порядок привязки = приоритет владения: первую очередь забирает палец.
    private val groups = LinkedHashMap<String, BoundGroup>()
    // pointerId → id группы-владельца
    private val owners = HashMap<Long, String>()
    // пальцы, чьё КАСАНИЕ было обработано ядром (в т.ч. мимо всех кнопок).
    // Только у НЕЗАНЕСЁННЫХ сюда пальцев движение пробует «усыновление» —
    // значит, их потерянный DOWN не оставит кнопку мёртвой до отрыва.
    private val downSeen = HashSet<Long>()
    private var sink: ((group: String, button: String, down: Boolean, port: Int, tMs: Long) -> Unit)? =
        null
    // Время события, обрабатываемого ПРЯМО СЕЙЧАС: колбэки FingerRouter
    // вызываются синхронно внутри down/move/up — значение актуально.
    private var tMs = 0L

    /** Подписка на нажатия: (группа, кнопка, нажата?, порт, время события, мс). */
    fun onPress(sink: (group: String, button: String, down: Boolean, port: Int, tMs: Long) -> Unit) {
        this.sink = sink
    }

    fun hasGroups(): Boolean = groups.isNotEmpty()

    /**
     * Привязать группу кнопок. Повторная привязка той же группы сбрасывает
     * её состояние (её кнопки отжимаются через колбэк).
     */
    fun bindGroup(
        id: String,
        hitTest: (Float, Float) -> String?,
        port: () -> Int
    ) {
        unbindGroup(id, tMs)
        groups[id] = BoundGroup(id, FingerRouter(hitTest) { b, d ->
            sink?.invoke(id, b, d, port(), tMs)
        })
    }

    /** Отвязать группу: её кнопки отжимаются, пальцы более ей не принадлежат. */
    fun unbindGroup(id: String, tMs: Long = this.tMs): Boolean {
        val g = groups.remove(id) ?: return false
        this.tMs = tMs
        g.router.releaseAll()
        owners.entries.removeAll { it.value == id }
        return true
    }

    /**
     * Проба (без изменения состояния): какая кнопка находится под точкой
     * и какая группа её забрала бы. Для диагностического лога.
     */
    fun probe(x: Float, y: Float): DownInfo {
        for (g in groups.values) {
            val b = g.router.buttonAt(x, y)
            if (b != null) return DownInfo(g.id, b)
        }
        return DownInfo(null, null)
    }

    /** Касание новым пальцем (ACTION_DOWN / ACTION_POINTER_DOWN). */
    fun down(pointerId: Long, x: Float, y: Float, tMs: Long) {
        this.tMs = tMs
        downSeen.add(pointerId)
        if (owners.containsKey(pointerId)) return // повторный down того же пальца
        claim(pointerId, x, y)
    }

    /**
     * Движение пальца: пересчитать кнопку владеющей группы. Палец без
     * владельца, чьё касание не обрабатывалось ядром, пробуется «усыновить»
     * (см. [claim]) — потерянный DOWN не оставляет кнопку мёртвой.
     */
    fun move(pointerId: Long, x: Float, y: Float, tMs: Long) {
        this.tMs = tMs
        val gid = owners[pointerId]
        if (gid != null) {
            groups[gid]?.router?.move(pointerId, x, y)
            return
        }
        if (!downSeen.contains(pointerId)) claim(pointerId, x, y)
    }

    /** Отпускание ОДНОГО пальца (ACTION_POINTER_UP). */
    fun up(pointerId: Long, tMs: Long) {
        this.tMs = tMs
        downSeen.remove(pointerId)
        val gid = owners.remove(pointerId) ?: return
        groups[gid]?.router?.up(pointerId)
    }

    /**
     * Все пальцы подняты (ACTION_UP) или жест отменён системой
     * (ACTION_CANCEL): отжать ВСЁ и забыть всех пальцев.
     */
    fun liftAll(tMs: Long) {
        this.tMs = tMs
        owners.clear()
        downSeen.clear()
        for (g in groups.values) g.router.releaseAll()
    }

    /**
     * Отдать палец первой группе, чью кнопку он накрыл центром.
     * Если касание этого пальца раньше не обрабатывалось ядром (палец
     * «потерян»), сообщаем в [onAdopt] — для диагностического лога.
     */
    private fun claim(pointerId: Long, x: Float, y: Float) {
        for (g in groups.values) {
            val b = g.router.buttonAt(x, y) ?: continue
            if (g.router.down(pointerId, x, y)) {
                owners[pointerId] = g.id
                if (!downSeen.contains(pointerId)) {
                    downSeen.add(pointerId)
                    onAdopt?.invoke(pointerId, g.id, b, tMs)
                }
            }
            return
        }
    }
}
