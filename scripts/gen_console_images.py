#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ассеты скина «Приставка» — вырезки из фото-референсов элементов классического
геймпада (scripts/photo_src/console_dpad.png — крестовина, console_btns.png —
четыре круглые кнопки; тёмный фон, светло-серые детали). Пайплайн тот же, что
у «Гаджета»: функции gen_gadget_images.py переиспользуются (заливка фона от
краёв, связные компоненты, эрозия+гаусс края, квадратный кадр, Ланцош).

Результат (app/src/main/res/drawable-nodpi):
  skin_console_dpad.png — крестовина (фото, квадрат 512);
  skin_console_btn.png  — круглая кнопка (фото, квадрат 256), одна на все
                          кнопки A/B/A′/B′ — на референсе они одинаковые.

Подписи НЕ запечены: кнопки на референсе пустые (метки «B»/«A» напечатаны
на корпусе рядом, в кадр не попадают) — текст рисует приложение внутри
кнопок (у скина captions = false: корпуса нет, подписи НАД кнопками были
бы нечитаемы на игре).

Параметрические цвета скина сняты с тех же фото (лицо кнопки #837C6F,
тёмный бевел #444036, тело крестовины #9F9A8F, стрелки #56524C) —
контролируются этим скриптом при перегенерации.

Запуск из корня репозитория: python3 scripts/gen_console_images.py
(требуется Pillow; превью — /tmp/gadget_preview_console_*.png).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gen_gadget_images import (  # noqa: E402
    OUT,
    bg_mask,
    comp_stats,
    extract_rgba,
    finish,
    foreground_components,
    load,
    preview,
    save,
    square,
)


def gen_console_dpad():
    print("console_dpad.png:")
    im = load("console_dpad.png")
    w, h = im.size
    bg = bg_mask(im)
    comps = foreground_components(bg, w, h)
    assert comps, "крестовина не найдена"
    cross = max(comps, key=len)
    img = square(extract_rgba(im, bg, cross, pad=2))
    save(finish(img, 512), "skin_console_dpad.png")
    preview([finish(img, 160)], "console_dpad")


def gen_console_button():
    print("console_btns.png:")
    im = load("console_btns.png")
    w, h = im.size
    bg = bg_mask(im)
    comps = foreground_components(bg, w, h)
    # круглые кнопки: крупные и почти квадратные по bbox; мелкие метки
    # «B»/«A» на корпусе отсекаются площадью (в диагностике их и так нет)
    circles = []
    for c in comps:
        l, t, r, b = comp_stats(c, w)
        bw, bh = r - l, b - t
        if len(c) > 400 and 0.75 < bw / bh < 1.33:
            circles.append((len(c), c))
    assert len(circles) == 4, f"круглых кнопок найдено {len(circles)}, ожидалось 4"
    circles.sort(reverse=True)  # самая крупная — эталонная вырезка
    img = square(extract_rgba(im, bg, circles[0][1], pad=2))
    save(finish(img, 256), "skin_console_btn.png")
    preview([finish(img, 120)], "console_btn")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    gen_console_dpad()
    gen_console_button()
    print("OK")
