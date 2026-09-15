#!/usr/bin/env python3
"""Генератор 8x8 шрифта для меню мультикарта DendyBox.

Рисует глифы из TTF (DejaVu Sans Mono) в сетку 8x8 и выдаёт .inc с байтами
тайлов CHR (по 8 байт на тайл, 1 бит = 1 пиксель: 0 = цвет 0, 1 = цвет 1).

Набор: ASCII 0x20-0x5F (64 тайла), кириллица А-Я (32), а-я (32), Ё, ё и
стрелки вверх/вниз. Итого 132 тайла (1056 байт) — влезает в pattern table.

Запуск:  python3 font_gen.py font.inc
"""
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("Нужен Pillow: pip3 install --user pillow")

# --- карта символов: (символ, тайл) ---
CHARS = {}
for i in range(0x20, 0x60):          # ASCII: пробел ... '_' (включая цифры и '>')
    CHARS[chr(i)] = i - 0x20
for i, ch in enumerate("АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"):
    CHARS[ch] = 64 + i               # 64..95
for i, ch in enumerate("абвгдежзийклмнопрстуфхцчшщъыьэюя"):
    CHARS[ch] = 96 + i               # 96..127
CHARS["Ё"] = 128
CHARS["ё"] = 129
CHARS["↑"] = 130
CHARS["↓"] = 131

N_TILES = 132


def render_glyph(font, ch):
    """8x8 монохромная битовая маска глифа."""
    img = Image.new("1", (16, 16), 0)
    d = ImageDraw.Draw(img)
    d.text((4, 2), ch, fill=1, font=font)
    # обрезаем по факту и центрируем в 8x8
    bbox = img.getbbox()
    if not bbox:
        return [0] * 8
    glyph = img.crop(bbox)
    w, h = glyph.size
    canvas = Image.new("1", (8, 8), 0)
    ox = max(0, (8 - w) // 2)
    oy = max(0, (8 - h) // 2)
    canvas.paste(glyph, (ox, oy))
    rows = []
    for y in range(8):
        b = 0
        for x in range(8):
            b |= canvas.getpixel((x, y)) << (7 - x)
        rows.append(b)
    return rows


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "font.inc"
    font = ImageFont.truetype(
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", 10)
    tiles = [None] * N_TILES
    for ch, idx in CHARS.items():
        tiles[idx] = render_glyph(font, ch)
    for i in range(N_TILES):
        if tiles[i] is None:
            tiles[i] = [0] * 8

    with open(out, "w", encoding="utf-8") as f:
        f.write("; Шрифт 8x8 (DejaVu Sans Mono), сгенерирован font_gen.py\n")
        f.write(f"; тайлов: {N_TILES}, байт: {N_TILES * 8}\n")
        f.write("fontTiles:\n")
        for i in range(N_TILES):
            row = ", ".join(f"${b:02X}" for b in tiles[i])
            f.write(f"  .db {row}   ; tile {i}\n")
        f.write("fontTilesEnd:\n")
    print(f"OK: {out} ({N_TILES} тайлов, {N_TILES * 8} байт)")


if __name__ == "__main__":
    main()
