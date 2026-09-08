#!/usr/bin/env bash
# ============================================================================
# Видеозапись геймплея с телефона в реальном времени — через scrcpy.
#
#   ./scripts/screencast.sh <flavor> [длительность_сек] [файл.mp4]
#   ./scripts/screencast.sh robocop3 90
#   SKIP_INSTALL=1 ./scripts/screencast.sh robocop3 60  # без переустановки APK
#   SHOW=1 ./scripts/screencast.sh robocop3             # показывать зеркало на ПК
#   CROP16x9=1 ./scripts/screencast.sh robocop3         # кроп кадра до 16:9
#
# Установка утилиты (Arch Linux):  sudo pacman -S scrcpy
# (adb входит в зависимости scrcpy; требуется scrcpy >= 2.0)
#
# Как это работает:
#   * ставится APK (как в screenshots.sh), игра запускается и сама
#     разворачивается в альбомную ориентацию — скрипт дожидается её;
#   * звук пишется со смартфона (звук игры) на Android 11+; на старых
#     версиях запишется только видео;
#   * звук дублируется на колонки ПК — сразу слышно, что запись идёт со звуком;
#   * запись идёт без окна на ПК; во время записи ИГРАЙТЕ на телефоне;
#   * прервать можно Ctrl+C в любой момент — файл закроется корректно.
#
# Результат: videos/<flavor>/gameplay_ГГГГММДД_ЧЧММСС.mp4 (60 к/с, H.264+AAC)
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FLAVOR="${1:-}"
DUR="${2:-60}"
OUTFILE="${3:-}"
PKG="com.dendybox.app.$FLAVOR"

if [ -z "$FLAVOR" ]; then
  echo "Usage: $0 <flavor> [duration_sec] [out.mp4]"
  echo "Пример: $0 robocop3 90"
  exit 1
fi

# --- scrcpy ---
if ! command -v scrcpy >/dev/null 2>&1; then
  echo "ОШИБКА: scrcpy не найден. Установите: sudo pacman -S scrcpy"
  exit 1
fi
SCRCPY_VER_STR="$(scrcpy --version 2>/dev/null | head -n1 || true)"
SCRCPY_MAJOR="$(printf '%s' "$SCRCPY_VER_STR" | grep -oE '[0-9]+' | head -n1 || true)"
if [ -z "$SCRCPY_MAJOR" ] || [ "$SCRCPY_MAJOR" -lt 2 ]; then
  echo "ОШИБКА: нужен scrcpy >= 2.0 (обнаружен: ${SCRCPY_VER_STR:-неизвестно})."
  echo "Обновите пакет: sudo pacman -Syu scrcpy"
  exit 1
fi

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

# --- APK (если собран; иначе возьмём уже установленный) ---
APK="$(ls -t "$ROOT"/app/build/outputs/apk/$FLAVOR/release/*.apk 2>/dev/null | head -n 1 || true)"
if [ -z "${APK:-}" ]; then
  APK="$(ls -t "$ROOT"/app/build/outputs/apk/$FLAVOR/debug/*.apk 2>/dev/null | head -n 1 || true)"
fi

echo "Подключите телефон (USB debugging), разблокируйте экран..."
"$ADB" wait-for-device

if [ "${SKIP_INSTALL:-0}" != "1" ] && [ -n "${APK:-}" ]; then
  echo "Установка: $(basename "$APK")"
  "$ADB" install -r "$APK"
fi

if ! "$ADB" shell pm list packages 2>/dev/null | grep -q "package:$PKG"; then
  echo "ОШИБКА: $PKG не установлен на телефоне и APK не найден."
  echo "Соберите: ./scripts/build_release.sh $FLAVOR"
  exit 1
fi

# --- запуск игры; ориентацию задаёт сама игра, ничего не переопределяем ---
echo "Запуск $PKG ..."
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || {
  "$ADB" shell am start -n "$PKG/com.dendybox.app.MainActivity"
}

# Текущая ориентация дисплея: 0/2 — портрет, 1/3 — альбомная, пусто — неизвестно
current_rotation() {
  local r="" line
  r="$("$ADB" shell "dumpsys input 2>/dev/null | grep -m1 'SurfaceOrientation'" 2>/dev/null | grep -oE '[0-9]+' | tail -n1 | tr -d '\r')" || true
  if [ -z "$r" ]; then
    line="$("$ADB" shell "dumpsys window displays 2>/dev/null | grep -m1 -iE 'mCurrentRotation|mDisplayRotation'" 2>/dev/null | tr -d '\r')" || true
    case "$line" in
      *ROTATION_0*|*=0*)   r=0 ;;
      *ROTATION_90*|*=1*)  r=1 ;;
      *ROTATION_180*|*=2*) r=2 ;;
      *ROTATION_270*|*=3*) r=3 ;;
    esac
  fi
  printf '%s' "$r"
}

echo ""
echo "Ожидаю альбомную ориентацию игры (sensorLandscape)..."
ORIENT_OK=0
for _ in $(seq 1 30); do
  R="$(current_rotation)"
  if [ "$R" = "1" ] || [ "$R" = "3" ]; then
    ORIENT_OK=1
    break
  fi
  sleep 0.5
done
if [ "$ORIENT_OK" = "1" ]; then
  echo "Ориентация альбомная — пишем весь экран."
else
  echo "ПРЕДУПРЕЖДЕНИЕ: альбомная ориентация не определилась — пишу как есть."
fi

# --- файл результата ---
OUT="$OUTFILE"
if [ -z "$OUT" ]; then
  OUT="$ROOT/videos/$FLAVOR/gameplay_$(date +%Y%m%d_%H%M%S).mp4"
elif [ "${OUT#/}" = "$OUT" ]; then
  OUT="$ROOT/$OUT"
fi
mkdir -p "$(dirname "$OUT")"

# --- параметры scrcpy ---
ARGS=(--record="$OUT" --stay-awake --max-fps=60 --video-bit-rate=16M)
if [ -z "${SHOW:-}" ]; then
  ARGS+=(--no-window)
fi

SDK_NUM="$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -dc '0-9' || true)"
if [ -z "$SDK_NUM" ]; then
  SDK_NUM=0
fi
if [ "$SDK_NUM" -lt 30 ]; then
  echo "Android < 11 — захват звука недоступен, пишу только видео."
  ARGS+=(--no-audio)
fi

if [ -n "${CROP16x9:-}" ]; then
  # кроп до 16:9 по центру, по фактическим (альбомным) размерам дисплея
  PHYS="$("$ADB" shell wm size 2>/dev/null | grep -i 'Physical size' | grep -oE '[0-9]+x[0-9]+' | head -n1 | tr -d '\r')" || true
  if [ -n "$PHYS" ]; then
    PW="${PHYS%x*}"
    PH="${PHYS#*x}"
    DW="$PW"; DH="$PH"
    if [ "$DW" -lt "$DH" ]; then DW="$PH"; DH="$PW"; fi
    CW=$(( DH * 16 / 9 )); CW=$(( CW / 2 * 2 ))
    CX=$(( (DW - CW) / 2 )); CX=$(( CX / 2 * 2 ))
    if [ "$CX" -lt 0 ]; then CX=0; CW="$DW"; fi
    echo "Кроп 16:9: ${CW}x${DH}+${CX}+0 (дисплей ${DW}x${DH})"
    ARGS+=(--crop="$CW:$DH:$CX:0")
  else
    echo "ПРЕДУПРЕЖДЕНИЕ: не узнал размер дисплея — пишу без кропа."
  fi
fi

# --- запись ---
echo ""
echo "Пишу видео ${DUR} с → ${OUT#"$ROOT"/}"
echo ">>> ИГРАЙТЕ на телефоне во время записи! <<<"
RC=0
timeout --signal=INT --kill-after=15 "$DUR" scrcpy "${ARGS[@]}" || RC=$?
# 124 — время вышло (штатно), 130 — остановлено Ctrl+C (тоже штатно)
if [ "$RC" -ne 0 ] && [ "$RC" -ne 124 ] && [ "$RC" -ne 130 ]; then
  echo "ПРЕДУПРЕЖДЕНИЕ: scrcpy завершился с кодом $RC — проверьте файл."
fi

if [ -s "$OUT" ]; then
  echo ""
  echo "Готово: $OUT ($(du -h "$OUT" | cut -f1))"
  echo "Прервать можно было и Ctrl+C — файл закрывается корректно."
else
  echo "ОШИБКА: файл не создан или пуст."
  exit 1
fi
