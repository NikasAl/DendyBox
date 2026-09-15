#!/usr/bin/env bash
# Пересборка меню мультикарта: tools/multicart/menu/multicart_menu.bin.
# Нужна только при изменении menu/menu.asm или font_gen.py — в репозитории
# лежит готовый bin, make_multicart.py использует его.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

# ассемблер
if [ ! -x "asm6f/asm6f" ]; then
  echo "==> собираю asm6f"
  ./asm6f/build.sh
fi

# шрифт (генерируется только если нет — нужен python3 + Pillow)
if [ ! -f "menu/font.inc" ]; then
  echo "==> генерирую шрифт"
  python3 font_gen.py menu/font.inc
fi

echo "==> сборка меню"
( cd menu && ../asm6f/asm6f menu.asm multicart_menu.bin )
echo "OK: menu/multicart_menu.bin"
