#!/usr/bin/env python3
# ============================================================================
# Генерация иконки приложения DendyBox из исходника metadata/<flavor>/icon.png
#
#   python3 scripts/make_icons.py robocop3 [выходной_res-каталог]
#
# Что получается:
#   1) adaptive-иконка лаунчера (Android 8+ — наш minSdk):
#        [выходной_res-каталог]/          (по умолчанию app/build/generated/icons/<flavor>/res)
#          mipmap-anydpi-v26/ic_launcher.xml   (переопределяет стандартную)
#          mipmap-xxxhdpi/ic_fg.webp           (передний слой)
#          mipmap-xxxhdpi/ic_bg.webp           (задний слой, режим полного залива)
#          values/ic_colors.xml                (цвет подложки)
#      Каталог подключается Gradle-задачей prepare<Flavor><Type>Icons
#      через addGeneratedSourceDirectory (см. app/build.gradle.kts).
#   2) metadata/<flavor>/icon512.png — иконка 512x512 для карточки RuStore.
#
# Режим выбирается автоматически по прозрачности углов исходника:
#   * углы прозрачные (готовая «иконка с рамкой», как у RoboCop 3)
#       -> арт целиком на подложке среднего цвета арта (полностью виден
#          под любой маской лаунчера: круг/сквиркл/квадрат);
#   * углы непрозрачные (квадратный арт без своей формы)
#       -> арт заливает весь холст (центрированная обрезка), маска
#          лаунчера сама режет края.
#
# Зависимость: Pillow (pip3 install --user pillow)
# ============================================================================
import shutil
import sys
from pathlib import Path

from PIL import Image, ImageOps

CANVAS = 432                    # слой adaptive-иконки: 108dp @ xxxhdpi (4x)
FG_RATIO = 0.62                 # доля холста под арт в режиме подложки
                                # (внутри safe zone лаунчеров)
ICON512 = 512                   # размер иконки для RuStore
WEBP_LOSSLESS_LIMIT = 160 * 1024  # крупнее — перекодируем с потерями

# Подпапки, которые скрипт создаёт в выходном res-каталоге и может стирать
GENERATED_SUBDIRS = ("mipmap-anydpi-v26", "mipmap-xxxhdpi", "values")

ADAPTIVE_XML_BG_COLOR = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ic_bg_color"/>\n'
    '    <foreground android:drawable="@mipmap/ic_fg"/>\n'
    '</adaptive-icon>\n'
)

ADAPTIVE_XML_BG_IMG = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@mipmap/ic_bg"/>\n'
    '    <foreground android:drawable="@mipmap/ic_fg"/>\n'
    '</adaptive-icon>\n'
)


def resample_for(target: int, src_width: int):
    """Вверх — NEAREST (чёткие пиксели для мелких исходников), вниз — LANCZOS."""
    return Image.NEAREST if src_width < target else Image.LANCZOS


def corners_transparent(im: Image.Image, frac: float = 0.06) -> bool:
    """True, если хотя бы в одном углу есть прозрачность (арт со своей формой)."""
    a = im.getchannel("A")
    p = max(1, round(min(im.size) * frac))
    boxes = [
        (0, 0, p, p),
        (im.width - p, 0, im.width, p),
        (0, im.height - p, p, im.height),
        (im.width - p, im.height - p, im.width, im.height),
    ]
    return any(a.crop(b).getextrema()[0] < 250 for b in boxes)


def dominant_color(im: Image.Image) -> str:
    """Средний цвет непрозрачных пикселей, 'RRGGBB' без решётки."""
    th = im.copy()
    th.thumbnail((64, 64))
    px = th.load()
    r = g = b = n = 0
    for y in range(th.height):
        for x in range(th.width):
            pr, pg, pb, pa = px[x, y]
            if pa >= 128:
                r += pr
                g += pg
                b += pb
                n += 1
    if n == 0:
        return "15151A"  # фирменный тёмный фон DendyBox
    return f"{r // n:02X}{g // n:02X}{b // n:02X}"


def scaled_contain(im: Image.Image, size: int) -> Image.Image:
    """Вписать в квадрат size x size без обрезки (поля прозрачные)."""
    k = size / max(im.width, im.height)
    nw, nh = max(1, round(im.width * k)), max(1, round(im.height * k))
    small = im.resize((nw, nh), resample_for(size, im.width))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(small, ((size - nw) // 2, (size - nh) // 2))
    return out


def scaled_cover(im: Image.Image, size: int) -> Image.Image:
    """Заполнить квадрат size x size с центрированной обрезкой."""
    return ImageOps.fit(im, (size, size), method=resample_for(size, im.width))


def write_webp(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "WEBP", lossless=True, method=6)
    if path.stat().st_size > WEBP_LOSSLESS_LIMIT:
        img.save(path, "WEBP", quality=92, method=6)


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print("Использование: python3 scripts/make_icons.py <flavor> [выходной_res-каталог]")
        return 1
    flavor = sys.argv[1]
    root = Path(__file__).resolve().parent.parent
    src = root / "metadata" / flavor / "icon.png"
    if not src.exists():
        print(f"Нет исходника: {src}")
        return 1

    # Выходной res-каталог: пустой/не задан -> значение по умолчанию.
    # Gradle (addGeneratedSourceDirectory) передаёт свой путь вида
    # app/build/generated/res/prepare<Flavor><Type>Icons — это нормально.
    out_arg = (sys.argv[2] if len(sys.argv) == 3 else "").strip()
    if out_arg:
        out_res = Path(out_arg).resolve()
        # ЗАЩИТА: работаем только внутри дерева генерируемых каталогов сборки
        allowed_root = (root / "app" / "build" / "generated").resolve()
        if allowed_root not in out_res.parents:
            print(f"ОТКАЗ: выходной каталог вне app/build/generated: {out_res}")
            return 1
    else:
        out_res = root / "app" / "build" / "generated" / "icons" / flavor / "res"

    im = Image.open(src).convert("RGBA")
    print(f"Исходник: {src.relative_to(root)} ({im.width}x{im.height})")

    shaped = corners_transparent(im)

    # Чистим прошлую генерацию (режим мог поменяться) — ТОЛЬКО свои подпапки
    for sub in GENERATED_SUBDIRS:
        d = out_res / sub
        if d.exists():
            shutil.rmtree(d)

    if shaped:
        bg_color = dominant_color(im)
        fg = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
        art = scaled_contain(im, round(CANVAS * FG_RATIO))
        fg.alpha_composite(art, ((CANVAS - art.width) // 2, (CANVAS - art.height) // 2))
        write_webp(fg, out_res / "mipmap-xxxhdpi" / "ic_fg.webp")
        (out_res / "values").mkdir(parents=True, exist_ok=True)
        (out_res / "values" / "ic_colors.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            f'    <color name="ic_bg_color">#{bg_color}</color>\n'
            "</resources>\n",
            encoding="utf-8",
        )
        xml_dir = out_res / "mipmap-anydpi-v26"
        xml_dir.mkdir(parents=True, exist_ok=True)
        (xml_dir / "ic_launcher.xml").write_text(ADAPTIVE_XML_BG_COLOR, encoding="utf-8")
        print(f"Режим: арт на подложке #{bg_color} (у исходника прозрачные углы)")
    else:
        bg = scaled_cover(im, CANVAS)
        write_webp(bg, out_res / "mipmap-xxxhdpi" / "ic_bg.webp")
        fg = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
        write_webp(fg, out_res / "mipmap-xxxhdpi" / "ic_fg.webp")
        xml_dir = out_res / "mipmap-anydpi-v26"
        xml_dir.mkdir(parents=True, exist_ok=True)
        (xml_dir / "ic_launcher.xml").write_text(ADAPTIVE_XML_BG_IMG, encoding="utf-8")
        print("Режим: полный залив (арт обрезается маской лаунчера)")

    # Иконка 512x512 для карточки RuStore (с сохранением альфы, если она есть)
    icon512 = scaled_contain(im, ICON512) if shaped else scaled_cover(im, ICON512)
    p512 = root / "metadata" / flavor / "icon512.png"
    icon512.save(p512, "PNG", optimize=True)
    print(f"Готово: {p512.relative_to(root)} ({p512.stat().st_size // 1024} КБ)")
    print(f"Иконка варианта: {out_res}")
    for f in sorted(out_res.rglob("*")):
        if f.is_file():
            print(f"  {f.relative_to(out_res)}  ({f.stat().st_size // 1024} КБ)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
