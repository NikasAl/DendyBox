#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Генератор PNG-ассетов image-скинов DendyBox (Task 34).

Все ассеты — квадратный холст 512x512 с прозрачным фоном, рисование с
суперсэмплингом x4 (2048) и Lanczos-даунскейлом. Подписи на кнопки НЕ
запечены: буквы (A, B, START…) рисует приложение поверх картинки цветом
скина (skin.label) — меньше ассетов, единый шрифт с параметрическими
скинами. Крестовина — цельный крест (блок), стрелки нажатия рисует приложение.

Выход: app/src/main/res/drawable-nodpi/skin_*.png
"""
from PIL import Image, ImageDraw, ImageFilter
import os

OUT = "app/src/main/res/drawable-nodpi"
S = 512          # финальный размер
SS = 4           # суперсэмплинг
BIG = S * SS

os.makedirs(OUT, exist_ok=True)


def canvas():
    img = Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def down(img):
    return img.resize((S, S), Image.LANCZOS)


def save(img, name):
    img = down(img)
    img.save(os.path.join(OUT, name), "PNG", optimize=True)
    print("OK", name)


def vgrad(size, top, bottom):
    """Вертикальный градиент RGBA top->bottom на холсте size (быстрый: 1xh -> resize)."""
    w, h = size
    col = Image.new("RGBA", (1, h))
    px = col.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(4))
    return col.resize((w, h))


def radial(size, inner, outer, span=1.0):
    """Радиальный градиент: inner в центре -> outer на краю (span=1 — до угла)."""
    base = Image.radial_gradient("L").resize(size, Image.BICUBIC)  # 0 центр .. 255 угол
    if span != 1.0:
        base = base.point(lambda v: min(255, int(v * span)))
    inner_i, outer_i = Image.new("RGB", size, inner), Image.new("RGB", size, outer)
    rgb = Image.composite(outer_i, inner_i, base)
    rgba = Image.new("RGBA", size, (0, 0, 0, 0))
    rgba.paste(rgb, (0, 0))
    return rgba


def shape_mask(draw_fn):
    """Маска (L) фигуры: draw_fn(draw) рисует белым по чёрному."""
    m = Image.new("L", (BIG, BIG), 0)
    d = ImageDraw.Draw(m)
    draw_fn(d)
    return m


def apply_mask(img, mask):
    """Оставить img только внутри mask (альфа = min(альфа, mask))."""
    a = img.getchannel("A")
    from PIL import ImageChops
    a = ImageChops.multiply(a, mask)
    img.putalpha(a)
    return img


def ring_from_mask(mask, width_px):
    """Контур фигуры: (dilate - erode) маски. width_px — в финальных пикселях."""
    w = int(round(width_px * SS))
    if w < 1:
        w = 1
    dil = mask.filter(ImageFilter.MaxFilter(w * 2 + 1))
    ero = mask.filter(ImageFilter.MinFilter(w * 2 + 1))
    from PIL import ImageChops
    return ImageChops.subtract(dil, ero)


# ---------------------------------------------------------------------------
# Крестовина-крест (Денди): union двух скруглённых планок
# ---------------------------------------------------------------------------

def cross_mask(d, half_t, tip, radius):
    c = BIG / 2
    d.rounded_rectangle([c - tip, c - half_t, c + tip, c + half_t], radius=radius, fill=255)
    d.rounded_rectangle([c - half_t, c - tip, c + half_t, c + tip], radius=radius, fill=255)


def make_cross(name, top_col, bot_col, outline_col, outline_w, half_t=0.185, tip=0.485, radius=0.045):
    half_t, tip, radius = half_t * BIG, tip * BIG, radius * BIG
    m = shape_mask(lambda d: cross_mask(d, half_t, tip, radius))
    grad = vgrad((BIG, BIG), top_col, bot_col)
    img = apply_mask(grad, m)
    ring = ring_from_mask(m, outline_w)
    img.paste(outline_col, (0, 0), ring)
    # мягкая тень под фигурой для объёма
    sh = m.filter(ImageFilter.GaussianBlur(6 * SS))
    shadow = Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))
    shadow.putalpha(sh.point(lambda v: v // 3))
    base = Image.alpha_composite(shadow, img)
    save(base, name)


# ---------------------------------------------------------------------------
# Круглая кнопка (A/B/турбо)
# ---------------------------------------------------------------------------

def make_button(name, inner, outer, rim_col, rim_w, ring_col=None, ring_w=0.028):
    img = radial((BIG, BIG), inner, outer, span=1.35)  # полный внешний цвет к краю круга
    m = shape_mask(lambda d: d.ellipse([0.03 * BIG, 0.03 * BIG, 0.97 * BIG, 0.97 * BIG], fill=255))
    img = apply_mask(img, m)
    # тёмный ободок по краю круга
    rim = ring_from_mask(m, rim_w)
    img.paste(rim_col, (0, 0), rim)
    # светлое кольцо снаружи (для тёмных скинов)
    if ring_col:
        ring = ring_from_mask(shape_mask(
            lambda d: d.ellipse([0.005 * BIG, 0.005 * BIG, 0.995 * BIG, 0.995 * BIG], fill=255)), int(ring_w * S))
        img.paste(ring_col, (0, 0), ring)
    # блик: размытый белый эллипс сверху слева
    spec = Image.new("L", (BIG, BIG), 0)
    sd = ImageDraw.Draw(spec)
    sd.ellipse([0.16 * BIG, 0.10 * BIG, 0.62 * BIG, 0.38 * BIG], fill=110)
    spec = spec.filter(ImageFilter.GaussianBlur(18 * SS))
    white = Image.new("RGBA", (BIG, BIG), (255, 255, 255, 255))
    white.putalpha(spec)
    img = Image.alpha_composite(img, white)
    save(img, name)


# ---------------------------------------------------------------------------
# Капсула (START/SELECT) в квадратном холсте
# ---------------------------------------------------------------------------

def make_pill(name, top_col, bot_col, outline_col, outline_w):
    box = [0.04 * BIG, 0.30 * BIG, 0.96 * BIG, 0.70 * BIG]
    m = shape_mask(lambda d: d.rounded_rectangle(box, radius=(box[3] - box[1]) // 2, fill=255))
    grad = vgrad((BIG, BIG), top_col, bot_col)
    img = apply_mask(grad, m)
    ring = ring_from_mask(m, outline_w)
    img.paste(outline_col, (0, 0), ring)
    # верхний блик-полоска
    spec = Image.new("L", (BIG, BIG), 0)
    sd = ImageDraw.Draw(spec)
    sd.rounded_rectangle([box[0] + 40, box[1] + 26, box[2] - 40, box[1] + 78], radius=26 * SS, fill=70)
    spec = spec.filter(ImageFilter.GaussianBlur(8 * SS))
    white = Image.new("RGBA", (BIG, BIG), (255, 255, 255, 255))
    white.putalpha(spec)
    img = Image.alpha_composite(img, white)
    save(img, name)


# ---------------------------------------------------------------------------
# Панель-подложка блока A/B (Карбон): скруглённый квадрат с фактурой
# ---------------------------------------------------------------------------

def make_panel():
    box = [0.02 * BIG, 0.02 * BIG, 0.98 * BIG, 0.98 * BIG]
    m = shape_mask(lambda d: d.rounded_rectangle(box, radius=0.10 * BIG, fill=255))
    img = vgrad((BIG, BIG), (26, 26, 33, 235), (14, 14, 19, 235))
    img = apply_mask(img, m)
    # диагональная фактура: тонкие светлые линии, обрезанные по панели
    tex = Image.new("L", (BIG, BIG), 0)
    td = ImageDraw.Draw(tex)
    step = 26 * SS
    for i in range(-BIG, 2 * BIG, step):
        td.line([(i, 0), (i + BIG, BIG)], fill=26, width=3 * SS)
    lines = Image.new("RGBA", (BIG, BIG), (255, 255, 255, 0))
    lines.putalpha(tex)
    img = Image.alpha_composite(img, apply_mask(lines, m))
    # контур
    ring = ring_from_mask(m, 4)
    img.paste((142, 142, 154, 255), (0, 0), ring)
    save(img, "skin_carbon_panel.png")


# ---------------------------------------------------------------------------
# Аркадная крестовина: квадратная пластина + круглый центр
# ---------------------------------------------------------------------------

def make_arcade_dpad():
    box = [0.03 * BIG, 0.03 * BIG, 0.97 * BIG, 0.97 * BIG]
    m = shape_mask(lambda d: d.rounded_rectangle(box, radius=0.09 * BIG, fill=255))
    img = apply_mask(vgrad((BIG, BIG), (56, 56, 66, 255), (26, 26, 31, 255)), m)
    ring = ring_from_mask(m, 4)
    img.paste((110, 110, 120, 255), (0, 0), ring)
    # центральный круг-колпачок
    cm = shape_mask(lambda d: d.ellipse([0.33 * BIG, 0.33 * BIG, 0.67 * BIG, 0.67 * BIG], fill=255))
    cap = apply_mask(radial((BIG, BIG), (74, 74, 86), (40, 40, 47), span=1.3), cm)
    cring = ring_from_mask(cm, 3)
    cap.paste((96, 96, 108, 255), (0, 0), cring)
    img = Image.alpha_composite(img, cap)
    save(img, "skin_arcade_dpad.png")


# ===========================================================================
# Скины
# ===========================================================================

if __name__ == "__main__":
    # --- «Денди Классика»: чёрный крест + красные круглые A/B + тёмные капсулы
    make_cross("skin_dendy_dpad.png",
               top_col=(64, 64, 68, 255), bot_col=(14, 14, 16, 255),
               outline_col=(88, 88, 94, 255), outline_w=2)
    make_button("skin_dendy_btn.png",
                inner=(240, 92, 74), outer=(168, 24, 14),
                rim_col=(122, 14, 8, 255), rim_w=2.5)
    make_pill("skin_dendy_pill.png",
              top_col=(70, 70, 76, 255), bot_col=(34, 34, 38, 255),
              outline_col=(96, 96, 104, 255), outline_w=2)

    # --- «Карбон»: графитовые круги со светлым кольцом, крест со светлым контуром
    make_cross("skin_carbon_dpad.png",
               top_col=(52, 52, 62, 255), bot_col=(20, 20, 25, 255),
               outline_col=(158, 160, 172, 255), outline_w=3)
    make_button("skin_carbon_btn.png",
                inner=(62, 62, 74), outer=(27, 27, 33),
                rim_col=(18, 18, 22, 255), rim_w=2,
                ring_col=(158, 160, 172, 255), ring_w=0.022)
    make_pill("skin_carbon_pill.png",
              top_col=(52, 52, 62, 255), bot_col=(24, 24, 30, 255),
              outline_col=(158, 160, 172, 255), outline_w=2)
    make_panel()

    # --- «Аркада»: пластина с колпачком, выпуклые цветные кнопки, слоновая кость
    make_arcade_dpad()
    make_button("skin_arcade_btn_red.png",
                inner=(238, 84, 66), outer=(150, 24, 16),
                rim_col=(104, 14, 8, 255), rim_w=2)
    make_button("skin_arcade_btn_yellow.png",
                inner=(246, 208, 68), outer=(196, 140, 10),
                rim_col=(140, 96, 6, 255), rim_w=2)
    make_pill("skin_arcade_pill.png",
              top_col=(250, 244, 226, 255), bot_col=(214, 205, 182, 255),
              outline_col=(46, 46, 46, 255), outline_w=2)
