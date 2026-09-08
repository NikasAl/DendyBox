#!/usr/bin/env bash
# ============================================================================
# Сборка подписанного релиза DendyBox под конкретный ROM.
#
#   ./scripts/build_release.sh                — показать доступные игры
#   ./scripts/build_release.sh robocop3       — релиз (подпись из keystore/)
#   ./scripts/build_release.sh robocop3 debug — отладочная сборка
#
# Результат: app/build/outputs/apk/<flavor>/release/DendyBox-<flavor>-<ver>-release.apk
# Первый запуск релиза сам создаст ключ: scripts/make_keystore.sh
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

list_games() {
  echo "Доступные сборки (одна игра — одно приложение):"
  if [ -f roms/games.json ]; then
    echo "--- roms/games.json ---"
    sed 's/^/  /' roms/games.json
  fi
  shopt -s nullglob
  local found=0
  for f in roms/*.nes roms/*.unf roms/*.unif roms/*.fds; do
    local stem; stem="$(basename "${f%.*}")"
    echo "  ROM: $(basename "$f")  → flavor: $(echo "$stem" | sed 's/[^a-zA-Z0-9]//g' | sed 's/^[0-9]*/&/' | awk '{print tolower($0)}' | sed 's/^\([0-9]\)/game\1/')"
    found=1
  done
  shopt -u nullglob
  [ "$found" = "0" ] || true
  echo ""
  echo "Собирать так:  ./scripts/build_release.sh <flavor> [release|debug]"
}

if [ $# -lt 1 ]; then
  list_games
  exit 0
fi

FLAVOR="$1"
TYPE="${2:-release}"
case "$TYPE" in
  release|debug) ;;
  *) echo "Неизвестный тип сборки: $TYPE (release|debug)"; exit 1 ;;
esac

# --- keystore для релиза ---
if [ "$TYPE" = "release" ] && { [ ! -f keystore/release.keystore ] || [ ! -f keystore/keystore.properties ]; }; then
  echo "Релизный keystore не найден — генерирую (scripts/make_keystore.sh)..."
  ./scripts/make_keystore.sh
fi

# --- Android SDK (local.properties, как в run.sh) ---
if [ ! -f local.properties ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  if [ ! -d "$SDK" ]; then
    echo "ОШИБКА: Android SDK не найден. Задайте ANDROID_HOME или создайте local.properties со строкой sdk.dir=/путь/к/sdk"
    exit 1
  fi
  echo "sdk.dir=$SDK" > local.properties
fi

# --- Java ---
if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
  echo "ОШИБКА: Java не найдена. Установите JDK 17 или задайте JAVA_HOME."
  exit 1
fi

# --- ядро эмулятора для релизных ABI ---
MISSING_CORE=""
for ABI in arm64-v8a armeabi-v7a; do
  [ -f "app/src/main/jniLibs/$ABI/libcore_nes.so" ] || MISSING_CORE="$MISSING_CORE $ABI"
done
if [ -n "$MISSING_CORE" ]; then
  if [ "${CORE_CHECKED:-0}" != "1" ]; then
    echo "ВНИМАНИЕ: нет ядра libcore_nes.so для:$MISSING_CORE"
    echo "Без него APK соберётся, но на этих устройствах не установится/не запустится."
    echo "Скачать:  ./scripts/build_core_fceumm.sh all"
    echo ""
    read -r -p "Продолжить сборку без ядра? [y/N] " ANSWER
    case "$ANSWER" in
      [yY]|[yY][eE][sS]) export CORE_CHECKED=1 ;;
      *) ./scripts/build_core_fceumm.sh all || exit 1 ;;
    esac
  fi
fi

# --- ROM присутствует? ---
if [ -f roms/games.json ]; then
  ROM_EXPECTED="$(python3 - "$FLAVOR" <<'PY'
import json, sys
try:
    cfg = json.load(open('roms/games.json'))
except Exception:
    sys.exit(0)
want = sys.argv[1]
for fname, meta in cfg.items():
    fl = (meta or {}).get('flavor') or fname.rsplit('.', 1)[0].lower()
    if fl == want:
        print(fname)
        break
PY
)" || ROM_EXPECTED=""
else
  ROM_EXPECTED=""
fi
if [ -n "$ROM_EXPECTED" ] && [ ! -f "roms/$ROM_EXPECTED" ]; then
  echo "ОШИБКА: ROM не найден: roms/$ROM_EXPECTED"
  echo "Положите файл в папку roms/ (см. roms/README.md) и повторите."
  exit 1
fi

# --- иконка варианта (из metadata/<flavor>/icon.png) ---
# Генерируем ДО gradle, чтобы отсутствие Pillow было видно сразу,
# а не после трёх минут сборки (в gradle задача prepare<Flavor><Type>Icons
# вызовет тот же скрипт ещё раз при необходимости).
if [ -f "metadata/$FLAVOR/icon.png" ]; then
  echo ""
  echo "=== иконка варианта ($FLAVOR) ==="
  ./scripts/make_icons.sh "$FLAVOR"
else
  echo ""
  echo "Иконка metadata/$FLAVOR/icon.png не найдена — собираем со стандартной иконкой DendyBox."
  echo "(Положите PNG в metadata/$FLAVOR/icon.png и пересоберите, чтобы задать свою иконку.)"
fi

CAP="$(echo "$FLAVOR" | awk '{print toupper(substr($0,1,1)) substr($0,2)}')"
TYPE_CAP="$(echo "$TYPE" | awk '{print toupper(substr($0,1,1)) substr($0,2)}')"
echo ""
echo "=== gradlew assemble${CAP}${TYPE_CAP} (flavor: $FLAVOR) ==="
./gradlew "assemble${CAP}${TYPE_CAP}"

APK="$(ls -t app/build/outputs/apk/$FLAVOR/$TYPE/*.apk 2>/dev/null | head -n 1 || true)"
if [ -z "$APK" ]; then
  echo "ОШИБКА: APK не найден — смотрите вывод gradle выше."
  exit 1
fi
echo ""
echo "=== Готово ==="
ls -lh "$APK" | awk '{print "APK: " $NF "  (" $5 ")"}'
command -v sha256sum >/dev/null 2>&1 && sha256sum "$APK" | awk '{print "SHA256: " $1}'
echo ""
echo "Установить на телефон:  adb install -r \"$APK\""
echo "Скриншоты для магазина:  ./scripts/screenshots.sh $FLAVOR"
