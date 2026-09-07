#!/usr/bin/env bash
# ============================================================================
# Скриншоты для карточки магазина — свой набор для каждой сборки.
#
#   ./scripts/screenshots.sh <flavor> [количество] [интервал_сек]
#   ./scripts/screenshots.sh robocop3 6 5
#   SKIP_INSTALL=1 ./scripts/screenshots.sh robocop3   # без переустановки APK
#
# Перед съёмкой разрешение экрана телефона ставится в 16:9 (1920x1080),
# по завершении — восстанавливается прежнее. Результат:
#   screenshots/<flavor>/shot_01.png ... shot_NN.png
#
# Во время съёмки ИГРАЙТЕ на телефоне: каждые <интервал> секунд снимается
# всё, что на экране (геймплей, пауза, редактор раскладки — магазин любит
# разнообразие: первый скриншот — самый эффектный игровой момент).
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FLAVOR="${1:-}"
COUNT="${2:-6}"
INTERVAL="${3:-5}"

if [ -z "$FLAVOR" ]; then
  echo "Usage: $0 <flavor> [count] [interval]"
  echo "Пример: $0 robocop3 6 5"
  exit 1
fi
PKG="com.dendybox.app.$FLAVOR"

# --- adb ---
ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB=adb
  elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
  elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
    ADB="$HOME/Android/Sdk/platform-tools/adb"
  else
    echo "ОШИБКА: adb не найден. Установите platform-tools или задайте ADB=/путь/к/adb"
    exit 1
  fi
fi

# --- APK сборки: сначала release, потом debug ---
APK="$(ls -t "$ROOT"/app/build/outputs/apk/$FLAVOR/release/*.apk 2>/dev/null | head -n 1 || true)"
if [ -z "${APK:-}" ]; then
  APK="$(ls -t "$ROOT"/app/build/outputs/apk/$FLAVOR/debug/*.apk 2>/dev/null | head -n 1 || true)"
fi
if [ -z "${APK:-}" ]; then
  echo "ОШИБКА: APK для '$FLAVOR' не собран. Выполните: ./scripts/build_release.sh $FLAVOR"
  exit 1
fi

echo "Подключите телефон (USB debugging) и разрешите отладку..."
"$ADB" wait-for-device

# --- сохранить текущее переопределение разрешения (для восстановления) ---
ORIG="$("$ADB" shell wm size | grep -i 'Override size:' | sed 's/.*Override size: //' | tr -d '\r' || true)"
restore() {
  if [ -n "${ORIG:-}" ]; then
    "$ADB" shell wm size "$ORIG" >/dev/null 2>&1 || true
    echo "Разрешение экрана восстановлено: $ORIG"
  else
    "$ADB" shell wm size reset >/dev/null 2>&1 || true
    echo "Разрешение экрана сброшено к физическому."
  fi
  if [ -n "${ORIG_DENS:-}" ]; then
    "$ADB" shell wm density "$ORIG_DENS" >/dev/null 2>&1 || true
    echo "Плотность пикселей восстановлена: $ORIG_DENS"
  fi
}
trap restore EXIT

# --- установка ---
if [ "${SKIP_INSTALL:-0}" != "1" ]; then
  echo "Установка: $(basename "$APK")"
  "$ADB" install -r "$APK"
fi

# --- 16:9 ---
"$ADB" shell wm size reset >/dev/null 2>&1 || true
echo "Разрешение экрана → 16:9 (1920x1080)"
"$ADB" shell wm size 1920x1080
if [ -n "${DENSITY:-}" ]; then
  ORIG_DENS="$("$ADB" shell wm density | grep -i 'Override density:' | sed 's/.*Override density: //' | tr -d '\r' || true)"
  echo "Плотность пикселей → $DENSITY"
  "$ADB" shell wm density "$DENSITY"
fi
sleep 1

# --- запуск игры ---
echo "Запуск $PKG ..."
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || {
  "$ADB" shell am start -n "$PKG/com.dendybox.app.MainActivity"
}
sleep 3

# --- съёмка ---
OUT="$ROOT/screenshots/$FLAVOR"
mkdir -p "$OUT"
N_SHOTS="${COUNT}"
echo ""
echo "Снимаю $N_SHOTS скриншотов с интервалом ${INTERVAL} с → screenshots/$FLAVOR/"
echo ">>> ИГРАЙТЕ на телефоне во время съёмки! <<<"
for i in $(seq 1 "$N_SHOTS"); do
  N="$(printf '%02d' "$i")"
  "$ADB" exec-out screencap -p > "$OUT/shot_$N.png"
  echo "  shot_$N.png"
  [ "$i" -lt "$N_SHOTS" ] && sleep "$INTERVAL"
done

echo ""
echo "Готово: $(ls "$OUT"/shot_*.png 2>/dev/null | wc -l) файлов в screenshots/$FLAVOR/"
echo "Для метаданных: положите лучшие в metadata/$FLAVOR/ (см. metadata/README.md)"
