#!/usr/bin/env bash
# ============================================================================
# Скриншоты для карточки магазина — свой набор для каждой сборки.
#
#   ./scripts/screenshots.sh <flavor> [количество] [интервал_сек]
#   ./scripts/screenshots.sh robocop3 15 20
#   SKIP_INSTALL=1 ./scripts/screenshots.sh robocop3   # без переустановки APK
#
# Разрешение и поворот экрана НЕ трогаются: игра сама разворачивает экран
# в альбомную ориентацию (sensorLandscape), скрипт лишь дожидается её и
# снимает экран в родном разрешении телефона:
#   screenshots/<flavor>/shot_01.png ...       — родные снимки (напр. 20:9)
#   screenshots/<flavor>/shot_01_16x9.png ...  — кадр 16:9 (1920x1080) под
#     карточку RuStore (нужен python3 + Pillow, как для make_icons):
#     размытый фон из того же кадра + снимок целиком по центру.
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
  echo "Пример: $0 robocop3 15 20"
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

# --- установка ---
if [ "${SKIP_INSTALL:-0}" != "1" ]; then
  echo "Установка: $(basename "$APK")"
  "$ADB" install -r "$APK"
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
  echo "Ориентация альбомная — игровое поле на весь экран, снимаем."
else
  echo "ПРЕДУПРЕЖДЕНИЕ: альбомная ориентация не определилась — снимаю как есть."
fi

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
  if [ "$i" -lt "$N_SHOTS" ]; then
    sleep "$INTERVAL"
  fi
done

# --- кадры 16:9 под карточку магазина (соотношение сторон у RuStore <= 2:1) ---
if command -v python3 >/dev/null 2>&1 && python3 -c 'import PIL' >/dev/null 2>&1; then
  echo ""
  echo "Готовлю кадры 16:9 для карточки (shot_NN_16x9.png)..."
  python3 - "$OUT" <<'PY'
import glob
import os
import sys

from PIL import Image, ImageEnhance, ImageFilter

out_dir = sys.argv[1]
targets = sorted(
    p for p in glob.glob(os.path.join(out_dir, "shot_*.png"))
    if not p.endswith("_16x9.png")
)
if not targets:
    print("  (нет снимков для обработки)")
    sys.exit(0)

for src in targets:
    im = Image.open(src)
    w, h = im.size
    # альбомный снимок -> кадр 1920x1080, портретный -> 1080x1920
    cw, ch = (1920, 1080) if w >= h else (1080, 1920)

    if (w, h) == (cw, ch):
        dst = src[:-4] + "_16x9.png"
        im.convert("RGB").save(dst, optimize=True)
        print(f"  {os.path.basename(dst)}  ({cw}x{ch}, без изменений)")
        continue

    # фон: тот же кадр, растянутый с заполнением, размытый и затемнённый
    cover = max(cw / w, ch / h)
    bw, bh = max(cw, round(w * cover)), max(ch, round(h * cover))
    bg = im.convert("RGB").resize((bw, bh), Image.LANCZOS)
    left, top = (bw - cw) // 2, (bh - ch) // 2
    bg = bg.crop((left, top, left + cw, top + ch))
    bg = bg.filter(ImageFilter.GaussianBlur(28))
    bg = ImageEnhance.Brightness(bg).enhance(0.55)

    # чёткий снимок целиком, по центру кадра
    fit = min(cw / w, ch / h)
    fw, fh = max(1, round(w * fit)), max(1, round(h * fit))
    sharp = im.convert("RGB").resize((fw, fh), Image.LANCZOS)
    bg.paste(sharp, ((cw - fw) // 2, (ch - fh) // 2))

    dst = src[:-4] + "_16x9.png"
    bg.save(dst, optimize=True)
    print(f"  {os.path.basename(dst)}  ({cw}x{ch})")
PY
else
  echo ""
  echo "python3/Pillow не найдены — кадры 16:9 пропущены."
  echo "Установите: pip3 install --user pillow  (или sudo pacman -S python-pillow)"
fi

echo ""
N_OK=0
for f in "$OUT"/shot_*.png; do
  case "${f##*/}" in
    *_16x9.png) ;;                 # кадры для карточки не считаем
    shot_[0-9]*.png) N_OK=$((N_OK+1)) ;;
  esac
done
echo "Готово: $N_OK снимков в screenshots/$FLAVOR/"
echo "Для карточки RuStore берите shot_NN_16x9.png (16:9); родные снимки — для соцсетей."
