#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Ядро FCEUmm для DendyBox -> app/src/main/jniLibs/<ABI>/libcore_nes.so
#
# Использование:
#   ./scripts/build_core_fceumm.sh [arm64-v8a|armeabi-v7a|x86_64|all]
#
# Сначала пробуем скачать готовое ядро с buildbot libretro (быстро, без NDK).
# Если не получилось — собираем из исходников (нужен Android NDK: ANDROID_NDK_HOME).
# ---------------------------------------------------------------------------
set -euo pipefail

ABIS="${1:-arm64-v8a}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/core"
mkdir -p "$BUILD"

download_prebuilt() {
  local abi="$1" out="$2"
  local url="https://buildbot.libretro.com/nightly/android/latest/$abi/cores/fceumm_libretro_android.so.zip"
  echo "==> Скачиваю готовое ядро: $url"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$BUILD/core.zip" "$url" || return 1
  else
    wget -qO "$BUILD/core.zip" "$url" || return 1
  fi
  rm -rf "$BUILD/unzip" && mkdir -p "$BUILD/unzip"
  unzip -oq "$BUILD/core.zip" -d "$BUILD/unzip"
  local so
  so="$(find "$BUILD/unzip" -name '*.so' | head -1 || true)"
  if [ -z "$so" ]; then return 1; fi
  cp "$so" "$out"
}

build_from_source() {
  local abi="$1" out="$2"
  echo "==> Собираю FCEUmm из исходников (ABI: $abi)"
  if [ ! -d "$BUILD/fceumm" ]; then
    git clone --depth 1 https://github.com/libretro/fceumm.git "$BUILD/fceumm"
  fi
  : "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME не задан — укажите путь к NDK, например:
export ANDROID_NDK_HOME=\$HOME/Android/Sdk/ndk/27.0.12077973}"
  ( cd "$BUILD/fceumm" && \
    make -f Makefile.libretro platform=android NDK_BUILD="$ANDROID_NDK_HOME/ndk-build" APP_ABI="$abi" )
  local so
  so="$(find "$BUILD/fceumm" -name 'fceumm_libretro*.so' | grep -i "$abi" | head -1 || true)"
  if [ -z "$so" ]; then
    so="$(find "$BUILD/fceumm" -name 'fceumm_libretro*.so' | head -1 || true)"
  fi
  if [ -z "$so" ]; then return 1; fi
  cp "$so" "$out"
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
  https://buildbot.libretro.com/nightly/android/latest/$abi/cores/fceumm_libretro_android.so.zip
и переименуйте в libcore_nes.so в каталоге $dir" >&2
    exit 1
  fi
  ls -la "$out"
}

if [ "$ABIS" = "all" ]; then
  for a in arm64-v8a armeabi-v7a x86_64; do
    install_abi "$a"
  done
else
  install_abi "$ABIS"
fi
