#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Ядро FCEUmm для DendyBox -> app/src/main/jniLibs/<ABI>/libcore_nes.so
#
# Использование:
#   ./scripts/build_core_fceumm.sh [arm64-v8a|armeabi-v7a|x86_64|all]
#
# Порядок:
#   1) Скачиваем готовое ядро с buildbot libretro (быстро, NDK не нужен).
#   2) Если не вышло — собираем из исходников libretro/libretro-fceumm
#      через ndk-build (нужен Android NDK).
# ---------------------------------------------------------------------------
set -euo pipefail

KNOWN_ABIS="arm64-v8a armeabi-v7a x86_64"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/core"
mkdir -p "$BUILD"

valid_abi() {
  case " $KNOWN_ABIS " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}

download_prebuilt() {
  local abi="$1" out="$2"
  # ВАЖНО: файлы лежат прямо в каталоге ABI, подпапки cores/ нет
  local url="https://buildbot.libretro.com/nightly/android/latest/$abi/fceumm_libretro_android.so.zip"
  echo "==> Скачиваю готовое ядро: $url"
  if command -v curl >/dev/null 2>&1; then
    curl -fSL --retry 3 -o "$BUILD/core.zip" "$url" || return 1
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$BUILD/core.zip" "$url" || return 1
  else
    echo "    Нужен curl или wget." >&2
    return 1
  fi
  rm -rf "$BUILD/unzip" && mkdir -p "$BUILD/unzip"
  if command -v unzip >/dev/null 2>&1; then
    unzip -oq "$BUILD/core.zip" -d "$BUILD/unzip" || return 1
  else
    python3 -m zipfile -e "$BUILD/core.zip" "$BUILD/unzip" || return 1
  fi
  local so
  so="$(find "$BUILD/unzip" -name '*.so' -type f | head -1 || true)"
  if [ -z "$so" ]; then
    echo "    В архиве нет .so-файла." >&2
    return 1
  fi
  cp -f "$so" "$out"
}

find_ndk() {
  # 1) Явная переменная окружения
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -x "$ANDROID_NDK_HOME/ndk-build" ]; then
    printf '%s\n' "$ANDROID_NDK_HOME"
    return 0
  fi
  # 2) Типовые каталоги SDK (Linux и macOS), берём самую свежую версию
  local base latest
  for base in "${ANDROID_HOME:-/nonexistent}/ndk" \
              "${ANDROID_SDK_ROOT:-/nonexistent}/ndk" \
              "$HOME/Android/Sdk/ndk" \
              "$HOME/Library/Android/sdk/ndk"; do
    [ -d "$base" ] || continue
    latest="$(cd "$base" && ls -1d */ 2>/dev/null | tr -d '/' \
              | grep -E '^[0-9]+(\.[0-9]+)+' | sort -V | tail -1 || true)"
    if [ -n "$latest" ] && [ -x "$base/$latest/ndk-build" ]; then
      printf '%s\n' "$base/$latest"
      return 0
    fi
  done
  return 1
}

build_from_source() {
  local abi="$1" out="$2"
  echo "==> Собираю FCEUmm из исходников (ABI: $abi)"

  local ndk
  ndk="$(find_ndk)" || {
    echo "Android NDK не найден (ANDROID_NDK_HOME не задан, типовые пути пусты)." >&2
    echo "Как установить:" >&2
    echo "  • Android Studio → SDK Manager → SDK Tools → «NDK (Side by side)»;" >&2
    echo "  • или: sdkmanager \"ndk;28.0.13039338\"  (подойдёт любой r26+);" >&2
    echo "Затем либо задайте ANDROID_NDK_HOME, либо просто оставьте NDK в" >&2
    echo "\$HOME/Android/Sdk/ndk/<версия> — скрипт найдёт его сам." >&2
    return 1
  }
  echo "    NDK: $ndk"

  if [ ! -d "$BUILD/fceumm" ]; then
    git clone --depth 1 https://github.com/libretro/libretro-fceumm.git "$BUILD/fceumm"
  fi

  # Ядро собирается ndk-build'ом (jni/Android.mk), а не make platform=android
  ( cd "$BUILD/fceumm" && \
    "$ndk/ndk-build" -j"$(nproc 2>/dev/null || echo 4)" \
      NDK_PROJECT_PATH=. \
      APP_BUILD_SCRIPT=jni/Android.mk \
      NDK_APPLICATION_MK=jni/Application.mk \
      APP_ABI="$abi" )

  local so="$BUILD/fceumm/libs/$abi/libretro.so"
  if [ ! -f "$so" ]; then
    echo "    ndk-build не создал $so" >&2
    return 1
  fi
  cp -f "$so" "$out"
}

install_abi() {
  local abi="$1"
  local dir="$ROOT/app/src/main/jniLibs/$abi"
  mkdir -p "$dir"
  local out="$dir/libcore_nes.so"
  if download_prebuilt "$abi" "$out"; then
    echo "OK (prebuilt): $out"
  elif build_from_source "$abi" "$out"; then
    echo "OK (build): $out"
  else
    echo "НЕ УДАЛОСЬ получить ядро для $abi. Скачайте вручную:
  https://buildbot.libretro.com/nightly/android/latest/$abi/fceumm_libretro_android.so.zip
и распакуйте .so в файл $dir/libcore_nes.so" >&2
    exit 1
  fi
  ls -la "$out"
}

if [ "${1:-}" = "all" ]; then
  for a in $KNOWN_ABIS; do
    install_abi "$a"
  done
else
  abi="${1:-arm64-v8a}"
  if ! valid_abi "$abi"; then
    echo "Неизвестный ABI: «$abi». Доступны: $KNOWN_ABIS, all" >&2
    exit 2
  fi
  install_abi "$abi"
fi

echo
echo "Ядро установлено. Пересоберите приложение, чтобы AGP упаковал его в APK."
