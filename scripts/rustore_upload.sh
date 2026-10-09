#!/usr/bin/env bash
# ============================================================================
# Загрузка сборки DendyBox в RuStore одним запуском:
# релизная сборка → черновик версии → APK → иконка → скриншоты → модерация.
#
#   ./scripts/rustore_upload.sh <flavor> [опции]
#
# Опции:
#   --submit              после загрузки сразу отправить на модерацию
#   --no-build            не собирать (взять готовый release-APK)
#   --dry-run             показать план, ничего не загружая
#   --publish-type T      MANUAL | INSTANTLY | DELAYED (по умолчанию MANUAL)
#
# Примеры:
#   ./scripts/rustore_upload.sh contra3in1 --dry-run   # репетиция
#   ./scripts/rustore_upload.sh contra3in1             # залить черновик
#   ./scripts/rustore_upload.sh contra3in1 --submit    # залить и на модерацию
#
# Настройка ключей и конфига: scripts/rustore/README.md
# Конфиг: ~/.config/rustore/config.json (или $RUSTORE_CONFIG).
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

usage() {
  grep '^#' "$0" | sed 's/^# \{0,2\}//' | tail -n +2
  exit "${1:-0}"
}

FLAVOR=""
SUBMIT=0
NO_BUILD=0
DRY=0
PTYPE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --submit)       SUBMIT=1 ;;
    --no-build)     NO_BUILD=1 ;;
    --dry-run)      DRY=1 ;;
    --publish-type) [ $# -ge 2 ] || usage 1; PTYPE="$2"; shift ;;
    -h|--help)      usage 0 ;;
    -*)             echo "Неизвестная опция: $1"; usage 1 ;;
    *)              [ -z "$FLAVOR" ] || { echo "Лишний аргумент: $1"; exit 1; }; FLAVOR="$1" ;;
  esac
  shift
done

if [ -z "$FLAVOR" ]; then
  echo "Использование: ./scripts/rustore_upload.sh <flavor> [--submit] [--no-build] [--dry-run] [--publish-type MANUAL|INSTANTLY|DELAYED]"
  echo ""
  echo "Доступные flavor'ы — см. roms/games.json:"
  python3 - <<'PY'
import json, os
p = 'roms/games.json'
if os.path.isfile(p):
    data = json.load(open(p, encoding='utf-8'))
    flavors = []
    for fname, meta in data.items():
        meta = meta or {}
        fl = str(meta.get('flavor') or fname.rsplit('.', 1)[0].lower())
        fl = ''.join(c for c in fl.lower() if c.isalnum())
        if not fl or not fl[0].isalpha():
            fl = 'game' + fl
        flavors.append(f"  {fl:<14} {meta.get('title', fname)}")
    print('\n'.join(flavors) or '  (пусто)')
PY
  exit 1
fi

if [ "$DRY" = "0" ] && [ ! -f ~/.config/rustore/config.json ] && [ -z "${RUSTORE_CONFIG:-}" ] && [ ! -f scripts/rustore/config.local.json ]; then
  echo "Конфиг RuStore не найден — см. scripts/rustore/README.md:"
  echo "  mkdir -p ~/.config/rustore && cp scripts/rustore/config.example.json ~/.config/rustore/config.json"
  exit 1
fi

# --- релизная сборка (keystore создастся автоматически при первом запуске) ---
if [ "$NO_BUILD" = "0" ] && [ "$DRY" = "0" ]; then
  echo "=== Сборка релиза ($FLAVOR) ==="
  ./scripts/build_release.sh "$FLAVOR" release
fi

APK="$(ls -t app/build/outputs/apk/$FLAVOR/release/*.apk 2>/dev/null | head -n 1 || true)"
if [ -z "$APK" ] && [ "$DRY" = "0" ]; then
  echo "ОШИБКА: release-APK не найден (app/build/outputs/apk/$FLAVOR/release/)"
  exit 1
fi
if [ -n "$APK" ]; then
  echo ""
  echo "=== Загрузка в RuStore: $FLAVOR → $(basename "$APK") ==="
else
  echo ""
  echo "=== Загрузка в RuStore: $FLAVOR (dry-run, APK ещё не собран) ==="
fi

ARGS=(upload --flavor "$FLAVOR")
[ -n "$APK" ] && ARGS+=(--apk "$APK")
[ "$SUBMIT" = "1" ] && ARGS+=(--submit)
[ "$DRY" = "1" ] && ARGS+=(--dry-run)
[ -n "$PTYPE" ] && ARGS+=(--publish-type "$PTYPE")

exec python3 scripts/rustore/rustore_api.py "${ARGS[@]}"
