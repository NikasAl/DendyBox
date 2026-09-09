#!/usr/bin/env python3
# ============================================================================
# Превращает экспортированный из приложения JSON раскладки в заводские
# дефолты LayoutStore.kt (что новому пользователю покажет при первом запуске).
#
#   python3 scripts/layout_to_defaults.py <layout.json> [путь/к/LayoutStore.kt]
#
# <layout.json> — файл, сохранённый кнопкой «Экспорт» в редакторе управления
# (тот же JSON, что хранится в SharedPreferences под ключом layout_v3).
# Координаты в нём нормализованные (доли экрана), поэтому подходят любому
# устройству. Значения зажимаются в те же пределы, что и в приложении
# (x,y: 0.02..0.98, scale: 0.5..2.0).
# ============================================================================
import json
import re
import sys
from pathlib import Path

XMIN, XMAX = 0.02, 0.98
SMIN, SMAX = 0.5, 2.0

DEFAULT_KT = (
    Path(__file__).resolve().parent.parent
    / "app/src/main/java/com/dendybox/app/ui/controls/LayoutStore.kt"
)


def fnum(v: float, lo: float, hi: float) -> str:
    v = max(lo, min(hi, float(v)))
    s = f"{round(v, 3)}"
    if s == "-0":
        s = "0"
    return s + "f"


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: layout_to_defaults.py <layout.json> [LayoutStore.kt]")
        sys.exit(1)
    src = Path(sys.argv[1])
    kt = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_KT

    try:
        data = json.loads(src.read_text(encoding="utf-8"))
    except Exception as e:
        sys.exit(f"ОШИБКА: не читаю {src}: {e}")

    try:
        text = kt.read_text(encoding="utf-8")
    except Exception as e:
        sys.exit(f"ОШИБКА: не читаю {kt}: {e}")

    for key, name in (("dpad", "DPAD"), ("ab", "AB"), ("meta", "META")):
        g = data.get(key)
        if not isinstance(g, dict) or not {"x", "y", "s"} <= set(g):
            sys.exit(f"ОШИБКА: в JSON нет группы '{key}' с полями x/y/s — "
                     f"это точно файл из кнопки «Экспорт»?")
        new = (f"val DEF_{name} = Group("
               f"{fnum(g['x'], XMIN, XMAX)}, "
               f"{fnum(g['y'], XMIN, XMAX)}, "
               f"{fnum(g['s'], SMIN, SMAX)})")
        text, n = re.subn(rf"val DEF_{name} = Group\([^)]*\)", new, text)
        if n != 1:
            sys.exit(f"ОШИБКА: DEF_{name} не найден (или найден {n} раз) в {kt}")
        print(new)

    kt.write_text(text, encoding="utf-8")
    print(f"OK: заводские дефолты обновлены -> {kt}")
    print("Дальше: пересоберите APK (scripts/build_release.sh <flavor>) и "
          "проверьте на чистом телефоне/после удаления приложения.")


if __name__ == "__main__":
    main()
