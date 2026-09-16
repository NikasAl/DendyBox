#!/usr/bin/env python3
"""Оптимизация постера меню сборника DendyBox перед упаковкой в APK.

Использование: optimize_background.py <исходник> <база-без-расширения>

Пересохраняет картинку в WebP (качество 88, длинная сторона не больше
2048px); если эта сборка Pillow без поддержки WebP — в JPEG (88).
Итоговое имя файла печатается строкой "RESULT <путь>" — её читает сборка
(app/build.gradle.kts) и кладёт в манифест games.json.

Картинки меньше 256 КиБ копируются как есть: перекодировка не даст
заметного выигрыша, а потеря качества ни к чему.

Скрипт вызывается сборкой автоматически; нужен python3 + Pillow (это те же
зависимости, что у scripts/make_icons.py для иконок).
"""
import os
import shutil
import sys

from PIL import Image, ImageOps

MAX_SIDE = 2048   # длинная сторона итоговой картинки
SMALL = 256 * 1024  # меньше этого размера — копируем без перекодирования
QUALITY = 88


def main() -> int:
    if len(sys.argv) != 3:
        print("использование: optimize_background.py <исходник> <база-без-расширения>")
        return 2
    src, base = sys.argv[1], sys.argv[2]
    if not os.path.isfile(src):
        print(f"нет файла: {src}")
        return 2
    os.makedirs(os.path.dirname(os.path.abspath(base)), exist_ok=True)

    # Компактный исходник — вставляем как есть, без потерь
    if os.path.getsize(src) <= SMALL:
        dst = base + os.path.splitext(src)[1].lower()
        shutil.copyfile(src, dst)
        print(f"постер уже компактный ({os.path.getsize(src) // 1024} КиБ) — копирую как есть")
        print(f"RESULT {dst}")
        return 0

    img = Image.open(src)
    img = ImageOps.exif_transpose(img)  # уважаем EXIF-поворот (фото с телефона)
    # Прозрачность кладём на чёрный — фон меню чёрный
    if img.mode != "RGB":
        rgba = img.convert("RGBA")
        flat = Image.new("RGB", rgba.size, (0, 0, 0))
        flat.paste(rgba, mask=rgba.split()[-1])
        img = flat

    w, h = img.size
    if max(w, h) > MAX_SIDE:
        k = MAX_SIDE / max(w, h)
        img = img.resize((round(w * k), round(h * k)), Image.LANCZOS)
        print(f"уменьшено: {w}x{h} -> {img.size[0]}x{img.size[1]}")

    try:
        from PIL import features
        if not features.check("webp"):
            raise RuntimeError("webp не поддерживается этой сборкой Pillow")
        dst = base + ".webp"
        img.save(dst, "WEBP", quality=QUALITY, method=6)
    except Exception as e:
        print(f"WebP недоступен ({e}) — сохраняю JPEG")
        dst = base + ".jpg"
        img.save(dst, "JPEG", quality=QUALITY, optimize=True, progressive=True)

    print(
        f"оптимизировано: {os.path.getsize(src) // 1024} КиБ -> "
        f"{os.path.getsize(dst) // 1024} КиБ"
    )
    print(f"RESULT {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
