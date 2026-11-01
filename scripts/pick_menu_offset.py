#!/usr/bin/env python3
"""Автоподбор горизонтального смещения меню сборника (menuOffsetX) по постеру.

Движок DendyBox: приложение работает в альбомной ориентации, постер меню
рисуется на весь экран ContentScale.Crop (CollectionMenu.kt), блок меню
(шапка/плашки/подсказка) имеет конечную ширину по содержимому и центрируется
по menuAlign. Скрипт симулирует Crop-обрезку постера под экран с заданным
aspect ratio, находит в видимом окне наименее «занятую» полосу шириной блока
и печатает menuOffsetX (доля ширины экрана, -1..1) на stdout (одна строка).

«Занятый» пиксель: высокая насыщенность (sat > 0.35) или высокая яркость
(lum > 150) — под тёмным блоком меню такие пиксели хуже всего читаются,
поэтому блок двигается туда, где постера меньше всего. Если центр и так
почти оптимальен (разница < --eps) — печатает 0.0 (манифест без смещения).
Детальная строка (# busy …) идёт в stderr.

Запуск вручную:
  python3 scripts/pick_menu_offset.py metadata/tmnt4in1/background.png
  python3 scripts/pick_menu_offset.py metadata/tmnt4in1/background.png \
      --aspect 2.333 --block-frac 0.32 --max-offset 0.30

Из build.gradle.kts вызывается автоматически при сборке flavor-сборника,
у которого в roms/games.json НЕ задан menuOffsetX явно: результат
подставляется в манифест assets/games/games.json. Явное значение из
roms/games.json всегда побеждает. Нужен python3 + Pillow.
"""

import argparse
import sys


def crop_window(poster_w: float, poster_h: float, aspect: float):
    """Часть постера, видимая при ContentScale.Crop на экран шириной/высотой = aspect.

    Координаты в единицах постера: (x0, y0, vis_w, vis_h).
    """
    scale = max(aspect / poster_w, 1.0 / poster_h)
    vis_w = aspect / scale
    vis_h = 1.0 / scale
    return (poster_w - vis_w) / 2, (poster_h - vis_h) / 2, vis_w, vis_h


def main() -> None:
    ap = argparse.ArgumentParser(description="menuOffsetX по постеру (см. заголовок скрипта)")
    ap.add_argument("image", help="постер: metadata/<flavor>/background.png|jpg|webp")
    ap.add_argument("--aspect", type=float, default=19.5 / 9,
                    help="ширина/высота экрана, альбомная ориентация (по умолчанию 19.5:9 = 2.167; 21:9 = 2.333)")
    ap.add_argument("--block-frac", type=float, default=0.32,
                    help="ширина блока меню в долях ширины экрана (по умолчанию 0.32: ширина самой длинной плашки на типичном FHD+ альбоме)")
    ap.add_argument("--align", choices=("center", "left", "right"), default="center",
                    help="menuAlign flavor'а (по умолчанию center): от этого якоря считается смещение")
    ap.add_argument("--max-offset", type=float, default=0.30,
                    help="предельное смещение, доля ширины (по умолчанию 0.30)")
    ap.add_argument("--eps", type=float, default=0.02,
                    help="минимальное улучшение к busyness центра, чтобы двигать блок (по умолчанию 0.02)")
    args = ap.parse_args()

    from PIL import Image

    im = Image.open(args.image).convert("RGB")
    pw, ph = im.size
    # Даунсэмпл до 512px шириной — для анализа плотности достаточно
    im = im.resize((min(pw, 512), max(1, round(ph * 512 / pw))))
    w, h = im.size
    px = im.load()

    x0, y0, vw, vh = crop_window(pw, ph, args.aspect)
    # Координаты окна в пикселях ИСХОДНОГО постера — переводим в размер после даунсэмпла
    sx, sy = w / pw, h / ph
    X0, X1 = max(0, round(x0 * sx)), min(w, max(1, round((x0 + vw) * sx)))
    Y0, Y1 = max(0, round(y0 * sy)), min(h, max(1, round((y0 + vh) * sy)))
    if X1 <= X0 or Y1 <= Y0:
        X0, X1, Y0, Y1 = 0, w, 0, h  # вырожденное окно — весь постер

    # По вертикальным столбцам в видимом окне — доля «занятых» пикселей
    cols = []
    for x in range(X0, X1):
        busy = 0
        for y in range(Y0, Y1):
            r, g, b = px[x, y]
            mx, mn = max(r, g, b), min(r, g, b)
            sat = (mx - mn) / mx if mx else 0.0
            lum = 0.299 * r + 0.587 * g + 0.114 * b
            if sat > 0.35 or lum > 150:
                busy += 1
        cols.append(busy / (Y1 - Y0))
    n = len(cols)

    # Скользящее окно шириной блока: суммарный busyness под блоком.
    # «Текущая» позиция блока — по menuAlign-якорю (left — блок прижат влево:
    # центр в block_frac/2; right — симметрично; center — 0.5)
    pref = [0.0] * (n + 1)
    for i, v in enumerate(cols):
        pref[i + 1] = pref[i] + v
    bw = min(n, max(1, round(args.block_frac * n)))

    def window_score(i: int) -> float:
        i = max(0, min(n - bw, i))
        return (pref[i + bw] - pref[i]) / bw

    anchor_center = {"center": 0.5, "left": args.block_frac / 2, "right": 1 - args.block_frac / 2}[args.align]
    anchor_i = round(anchor_center * n - bw / 2)
    anchor_score = window_score(anchor_i)
    best_score, best_pos = anchor_score, anchor_center
    for i in range(n - bw + 1):
        s = window_score(i)
        if s < best_score:
            best_score, best_pos = s, (i + bw / 2) / n

    if anchor_score - best_score < args.eps:
        offset = 0.0
    else:
        offset = round(max(-args.max_offset, min(args.max_offset, best_pos - anchor_center)), 3)

    print(f"# busy: позиция {args.align} {anchor_score:.3f} → лучшее {best_score:.3f} "
          f"(центр окна {best_pos:.3f}) | окно postera x {x0:.2f}..{x0 + vw:.2f}, "
          f"y {y0:.2f}..{y0 + vh:.2f} (aspect {args.aspect:.3f})", file=sys.stderr)
    print(offset)


if __name__ == "__main__":
    main()
