#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ассеты пакет-скина «Гаджет» — вырезаются из фото-референсов корпуса
(scripts/photo_src/*.png: снимки крестовины, пар кнопок B/A и пилюль
SELECT/START с настоящего «гаджета»). Раньше ассеты рисовались процедурно
(gen_skin_images.py), теперь — настоящие текстуры: пользователь прислал
вырезки кнопок, и скин должен совпадать с корпусом один в один.

Как вырезается:
  1. ФОН снимается заливкой от краёв кадра: фоновым считается пиксель,
     связный с рамкой ПЛАВНЫМ переходом (перепад цвета с соседом меньше
     порога). Резкая кромка кнопки заливку останавливает, мягкая тень под
     кнопкой проходится насквозь — ореолов не остаётся.
  2. Передний план разбирается на связные компоненты; нужные выбираются
     по цвету/положению (красные — турбо, чёрные — основные, широкие
     светлые — пилюли), надписи-каптионы и разделители отбрасываются.
  3. Компонент обводится, край слегка подтачивается (эрозия 1px + гаусс)
     и сглаживается; кадр дополняется до квадрата (крестовина/кнопки)
     и масштабируется Ланцошем с лёгкой подрезкой резкости.

Результат (app/src/main/res/drawable-nodpi):
  skin_gadget_dpad.png    — крестовина (фото, квадрат);
  skin_gadget_btn_a.png   — основная A (чёрная, буква запечена);
  skin_gadget_btn_b.png   — основная B (чёрная);
  skin_gadget_btn_ta.png  — турбо A (красная);
  skin_gadget_btn_tb.png  — турбо B (красная);
  skin_gadget_pill.png    — пилюля SELECT/START (пропорции сохранены).

Подписи (B, A, TURBO, SELECT…) НЕ запечены в ассеты, кроме букв на самих
кнопках с фото: каптионы над кнопками рисует приложение.

Запуск из корня репозитория: python3 scripts/gen_gadget_images.py
(требуется Pillow; превью для контроля — /tmp/gadget_preview_*.png).
"""
import os
from collections import deque

from PIL import Image, ImageFilter

BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, "photo_src")
OUT = os.path.join(os.path.dirname(BASE), "app/src/main/res/drawable-nodpi")

# Порог «плавного перехода» (сумма |dR|+|dG|+|dB| с соседним пикселем):
# шум/тень проходятся, кромка кнопки — нет.
T_LOCAL = 26


def load(name):
    return Image.open(os.path.join(SRC, name)).convert("RGB")


def bg_mask(im, t=T_LOCAL):
    """Заливка от краёв: 1 — фон. Сосед присоединяется, если переход
    от ТЕКУЩЕГО пикселя к нему плавный (<= t). Светлые пилюли почти
    сливаются с фоном — для meta.png порог жёстче (gen_pill)."""
    w, h = im.size
    px = im.load()
    bg = bytearray(w * h)
    q = deque()

    def seed(x, y):
        i = y * w + x
        if not bg[i]:
            bg[i] = 1
            q.append((x, y))

    for x in range(w):
        seed(x, 0)
        seed(x, h - 1)
    for y in range(h):
        seed(0, y)
        seed(w - 1, y)
    while q:
        x, y = q.popleft()
        c = px[x, y]
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not bg[i]:
                    n = px[nx, ny]
                    if abs(n[0] - c[0]) + abs(n[1] - c[1]) + abs(n[2] - c[2]) <= t:
                        bg[i] = 1
                        q.append((nx, ny))
    return bg


def foreground_components(bg, w, h, min_area=40):
    """Связные компоненты переднего плана (4-связность), площадь > min_area."""
    seen = bytearray(w * h)
    comps = []
    for i0 in range(w * h):
        if bg[i0] or seen[i0]:
            continue
        comp = []
        seen[i0] = 1
        q = deque([i0])
        while q:
            i = q.popleft()
            comp.append(i)
            x, y = i % w, i // w
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if 0 <= nx < w and 0 <= ny < h:
                    j = ny * w + nx
                    if not bg[j] and not seen[j]:
                        seen[j] = 1
                        q.append(j)
        if len(comp) >= min_area:
            comps.append(comp)
    return comps


def comp_stats(comp, w):
    """bbox, средний цвет (по координатам не нужен — цвет берёт вызывающий)."""
    xs = [i % w for i in comp]
    ys = [i // w for i in comp]
    return (min(xs), min(ys), max(xs) + 1, max(ys) + 1)


def extract_rgba(im, bg, comp, pad=3, feather=True):
    """RGBA-вырезка компонента: маска (эрозия 1px + гаусс — край берёт цвет
    изнутри, светлый ореол не появляется), автокадр с полем pad."""
    w, h = im.size
    mask = Image.new("L", (w, h), 0)
    mp = mask.load()
    for i in comp:
        mp[i % w, i // w] = 255
    if feather:
        mask = mask.filter(ImageFilter.MinFilter(3)).filter(
            ImageFilter.GaussianBlur(1.0))
    rgba = im.convert("RGBA")
    rgba.putalpha(mask)
    l, t, r, b = mask.getbbox()
    l = max(0, l - pad)
    t = max(0, t - pad)
    r = min(w, r + pad)
    b = min(h, b + pad)
    return rgba.crop((l, t, r, b))


def square(img):
    w, h = img.size
    s = max(w, h)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(img, ((s - w) // 2, (s - h) // 2))
    return out


def finish(img, size=None, w=None, h=None, sharpen=True):
    """Масштаб Ланкошем (Fit: вписать в w×h, пропорции сохраняются;
    +лёгкая подрезка резкости после апскейла)."""
    src_w, src_h = img.size
    if w is None:
        w = size
    if h is None:
        h = size
    scale = min(w / src_w, h / src_h)
    img = img.resize((round(src_w * scale), round(src_h * scale)), Image.LANCZOS)
    if sharpen and scale > 1.5:
        img = img.filter(ImageFilter.UnsharpMask(radius=2, percent=55, threshold=2))
    return img


def mean_color(im, comp):
    px = im.load()
    n = len(comp)
    r = sum(px[i % im.size[0], i // im.size[0]][0] for i in comp) / n
    g = sum(px[i % im.size[0], i // im.size[0]][1] for i in comp) / n
    b = sum(px[i % im.size[0], i // im.size[0]][2] for i in comp) / n
    return (r, g, b)


def save(img, name):
    path = os.path.join(OUT, name)
    img.save(path)
    print(f"  {name}: {img.size[0]}x{img.size[1]}")


def preview(imgs, name):
    """Превью на теле корпуса — контроль ореолов/краёв."""
    pad = 12
    w = sum(i.size[0] for i in imgs) + pad * (len(imgs) + 1)
    h = max(i.size[1] for i in imgs) + 2 * pad
    sheet = Image.new("RGB", (w, h), (242, 241, 236))
    x = pad
    for i in imgs:
        sheet.paste(i, (x, (h - i.size[1]) // 2), i)
        x += i.size[0] + pad
    sheet.save(f"/tmp/gadget_preview_{name}.png")
    print(f"  preview: /tmp/gadget_preview_{name}.png")


def gen_dpad():
    print("dpad.png:")
    im = load("dpad.png")
    w, h = im.size
    bg = bg_mask(im)
    comps = foreground_components(bg, w, h)
    assert comps, "крестовина не найдена"
    cross = max(comps, key=len)
    img = square(extract_rgba(im, bg, cross, pad=2))
    save(finish(img, 512), "skin_gadget_dpad.png")
    preview([finish(img, 160)], "dpad")


def gen_buttons():
    print("buttons.png:")
    im = load("buttons.png")
    w, h = im.size
    bg = bg_mask(im)
    comps = foreground_components(bg, w, h)
    reds, darks = [], []
    for c in comps:
        l, t, r, b = comp_stats(c, w)
        bw, bh = r - l, b - t
        (mr, mg, mb) = mean_color(im, c)
        if bw > 2.2 * bh and mr < 60:   # разделитель рядов
            continue
        if mr > 110 and mr - max(mg, mb) > 40:
            reds.append((l, t, r, b, c))
        elif (mr + mg + mb) / 3 < 95:
            darks.append((l, t, r, b, c))
    assert len(reds) >= 2, f"красных кнопок найдено {len(reds)}"
    assert len(darks) >= 2, f"чёрных кнопок найдено {len(darks)}"
    reds.sort(key=lambda s: s[0])
    darks.sort(key=lambda s: s[0])
    tb = extract_rgba(im, bg, reds[0][4])
    ta = extract_rgba(im, bg, reds[1][4])
    b = extract_rgba(im, bg, darks[0][4])
    a = extract_rgba(im, bg, darks[1][4])
    save(finish(square(tb), 256), "skin_gadget_btn_tb.png")
    save(finish(square(ta), 256), "skin_gadget_btn_ta.png")
    save(finish(square(b), 256), "skin_gadget_btn_b.png")
    save(finish(square(a), 256), "skin_gadget_btn_a.png")
    preview([finish(square(x), 120) for x in (tb, ta, b, a)], "buttons")


def gen_pill():
    print("meta.png:")
    im = load("meta.png")
    w, h = im.size
    # пилюля светло-серая на белом: плавность перехода фона и тела близки,
    # обычный порог проходит сквозь неё — берём жёсткий
    bg = bg_mask(im, t=9)
    comps = foreground_components(bg, w, h)
    pills = []
    for c in comps:
        l, t, r, b = comp_stats(c, w)
        bw, bh = r - l, b - t
        if bw / bh > 1.7 and len(c) > 400:  # пилюля шире и крупнее надписей
            pills.append((l, t, r, b, c))
    assert pills, "пилюли не найдены"
    pills.sort(key=lambda s: s[0])
    img = extract_rgba(im, bg, pills[0][4])
    save(finish(img, w=256, h=256), "skin_gadget_pill.png")
    preview([finish(img, w=140, h=140)], "pill")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    gen_dpad()
    gen_buttons()
    gen_pill()
    # старый процедурный ассет больше не используется — убрать из APK
    stale = os.path.join(OUT, "skin_gadget_btn.png")
    if os.path.exists(stale):
        os.remove(stale)
        print("  удалён устаревший skin_gadget_btn.png")
    print("OK")
