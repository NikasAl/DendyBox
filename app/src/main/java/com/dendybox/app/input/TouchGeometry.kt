package com.dendybox.app.input

import android.view.MotionEvent

/**
 * Реестр «пятна касания» (размера контакта пальца с экраном) по pointerId.
 *
 * Compose не отдаёт геометрию касания публично: эллипс контакта пальца
 * (MotionEvent.AXIS_TOUCH_MAJOR/MINOR) спрятан внутрь PointerEvent и недоступен
 * из pointerInput-корутин. Поэтому сырые события перехватываются на уровне
 * активности ([com.dendybox.app.MainActivity.dispatchTouchEvent] — до раздачи
 * их Compose) и складываются сюда, а логика экранных кнопок
 * (ControlsOverlay/FingerRouter) читает размер по id пальца. Реестр ничего
 * не потребляет и не блокирует — только записывает.
 *
 * Единицы — пиксели MotionEvent, те же, в которых Compose выдаёт координаты
 * касаний, так что пересчётов не требуется. Все вызовы — с главного потока.
 */
object TouchGeometry {
    // pointerId → большой диаметр эллипса контакта (px); 0 или нет записи —
    // устройство не сообщает размер (эмулятор, мышь): ко-нажатие отключено
    private val major = HashMap<Long, Float>()

    /** Обновить записи по сырому событию (вызывается из dispatchTouchEvent). */
    fun record(ev: MotionEvent) {
        val skipIndex = when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // подняты ВСЕ пальцы — реестр пуст
                major.clear()
                return
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // поднят один палец (его данные ещё в event — исключаем)
                val id = ev.getPointerId(ev.actionIndex).toLong()
                major.remove(id)
                ev.actionIndex
            }
            else -> -1
        }
        for (i in 0 until ev.pointerCount) {
            if (i == skipIndex) continue
            major[ev.getPointerId(i).toLong()] =
                ev.getAxisValue(MotionEvent.AXIS_TOUCH_MAJOR, i)
        }
    }

    /** Большой диаметр пятна касания пальца [pointerId] (px) или 0. */
    fun majorPx(pointerId: Long): Float = major[pointerId] ?: 0f
}
