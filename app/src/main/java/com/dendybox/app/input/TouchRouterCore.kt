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
 * Владение пальцем: при КАСАНИИ (down) палец забирает ПЕРВАЯ привязанная
 * группа, чью кнопку он накрыл центром или пятном; пальцы, стартовавшие
 * мимо всех кнопок (крестовина, меню, пустота), ядром не отслеживаются
 * вовсе — скольжение такого пальца на кнопки ничего не нажимает (семантика
 * прежних зон сохранена). Крестовина и верхнее меню продолжают обрабатываться
 * Compose-обработчиками как раньше — ядро к их координатам равнодушно.
 *
 * Все вызовы — с главного потока (dispatchTouchEvent): синхронизация не
 * нужна. Время — ev.eventTime (мс, monotonic uptime): подписчик получает
 * ТОЧНОЕ время события нажатия, никаких таймеров/дебаунсов внутри.
 */
class TouchRouterCore(
    /**
     * Доля большой оси контакта, считающаяся «ядром пятна» (радиус круга,
     * которым палец нажимает НАМЕРЕННО; см. patchTest в FingerRouter).
     */
    val patchCoreFraction: Float = 0.25f
) {
    /** Итог пробы: какая группа забрала бы палец и какие кнопки накрыты. */
    data class DownInfo(val group: String?, val buttons: Set<String>, val patchR: Float)

    private class BoundGroup(val id: String, val router: FingerRouter)

    // Порядок привязки = приоритет владения: первую очередь забирает палец.
    private val groups = LinkedHashMap<String, BoundGroup>()
    // pointerId → id группы-владельца
    private val owners = HashMap<Long, String>()
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
        patchTest: ((Float, Float, Float) -> Set<String>)?,
        port: () -> Int
    ) {
        unbindGroup(id, tMs)
        groups[id] = BoundGroup(id, FingerRouter(hitTest, patchTest) { b, d ->
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
     * Проба (без изменения состояния): что было бы нажато пальцем (центр +
     * пятно) и какая группа его забрала бы. Для диагностического лога.
     */
    fun probe(x: Float, y: Float, majorPx: Float): DownInfo {
        val patchR = majorPx * patchCoreFraction
        for (g in groups.values) {
            val buttons = g.router.buttonsAt(x, y, patchR)
            if (buttons.isNotEmpty()) return DownInfo(g.id, buttons, patchR)
        }
        return DownInfo(null, emptySet(), patchR)
    }

    /** Касание новым пальцем (ACTION_DOWN / ACTION_POINTER_DOWN). */
    fun down(pointerId: Long, x: Float, y: Float, majorPx: Float, tMs: Long) {
        this.tMs = tMs
        if (owners.containsKey(pointerId)) return // повторный down того же пальца
        val patchR = majorPx * patchCoreFraction
        for (g in groups.values) {
            val buttons = g.router.buttonsAt(x, y, patchR)
            if (buttons.isNotEmpty()) {
                g.router.down(pointerId, x, y, patchR)
                owners[pointerId] = g.id
                return
            }
        }
        // мимо всех кнопок — чужой палец: не отслеживается вовсе
    }

    /** Движение пальца — пересчитать кнопки ТОЛЬКО владеющей группы. */
    fun move(pointerId: Long, x: Float, y: Float, majorPx: Float, tMs: Long) {
        val gid = owners[pointerId] ?: return
        val g = groups[gid] ?: return
        this.tMs = tMs
        g.router.move(pointerId, x, y, majorPx * patchCoreFraction)
    }

    /** Отпускание ОДНОГО пальца (ACTION_POINTER_UP). */
    fun up(pointerId: Long, tMs: Long) {
        this.tMs = tMs
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
        for (g in groups.values) g.router.releaseAll()
    }
}
