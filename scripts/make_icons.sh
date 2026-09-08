#!/usr/bin/env bash
# ============================================================================
# Генерация иконки сборки <flavor> из metadata/<flavor>/icon.png:
#   * adaptive-иконка лаунчера (подхватывается сборкой APK автоматически);
#   * metadata/<flavor>/icon512.png — для карточки RuStore.
#
#   ./scripts/make_icons.sh robocop3 [выходной_res-каталог]
#
# Вызывается автоматически из build_release.sh и из gradle-задачи
# prepare<Flavor><Type>Icons; отдельно — чтобы просто обновить иконку.
# Зависимость: python3 + Pillow (pip3 install --user pillow)
# ============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FLAVOR="${1:-}"
if [ -z "$FLAVOR" ]; then
  echo "Использование: ./scripts/make_icons.sh <flavor> [выходной_res-каталог]"
  echo "Например:      ./scripts/make_icons.sh robocop3"
  exit 1
fi
if [ ! -f "metadata/$FLAVOR/icon.png" ]; then
  echo "Нет исходника: metadata/$FLAVOR/icon.png"
  echo "Положите PNG исходной иконки в метаданные игры и повторите."
  exit 1
fi
command -v python3 >/dev/null 2>&1 || { echo "ОШИБКА: python3 не найден."; exit 1; }
python3 -c 'import PIL' >/dev/null 2>&1 || {
  echo "ОШИБКА: для генерации иконки нужен Pillow."
  echo "Установите:  pip3 install --user pillow"
  exit 1
}
# Второй аргумент (res-каталог варианта) передаём только если он не пуст,
# иначе python примет пустую строку за путь.
if [ -n "${2:-}" ]; then
  exec python3 scripts/make_icons.py "$FLAVOR" "$2"
else
  exec python3 scripts/make_icons.py "$FLAVOR"
fi
