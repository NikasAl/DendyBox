#!/usr/bin/env bash
# Восстановление среды сборки в чистой песочнице:
#   JDK17 (Adoptium) -> /home/z/jdk17
#   Android SDK (platform-35, build-tools 35.0.0, NDK 27.0.12077973, cmake 3.22.1)
#     -> /home/z/android-sdk
#   local.properties в корне репозитория.
# Идемпотентно: уже установленные компоненты пропускаются.
set -euo pipefail

JDK_DIR=/home/z/jdk17
SDK=/home/z/android-sdk
CMDLINE_TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip
CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP

mkdir -p "$SDK"

# --- JDK 17 -------------------------------------------------------------
if [ -x "$JDK_DIR/bin/java" ]; then
  echo "JDK17 уже установлен: $JDK_DIR"
else
  echo ">>> Скачиваю JDK17 (Adoptium)…"
  T=$(mktemp -d)
  curl -sL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" -o "$T/jdk.tgz"
  mkdir -p "$JDK_DIR"
  tar xzf "$T/jdk.tgz" -C "$JDK_DIR" --strip-components=1
  rm -rf "$T"
  echo "JDK17: $($JDK_DIR/bin/java -version 2>&1 | head -1)"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# --- cmdline-tools ------------------------------------------------------
if [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "cmdline-tools уже установлен"
else
  echo ">>> Скачиваю cmdline-tools…"
  T=$(mktemp -d)
  curl -sL "$CMDLINE_TOOLS_URL" -o "$T/ct.zip"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$T/ct.zip" -d "$T/ct"
  mv "$T/ct/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$T"
  chmod +x "$SDK"/cmdline-tools/latest/bin/* || true
fi
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

# --- компоненты SDK -----------------------------------------------------
# Лицензии: принимаем один раз (yes умирает от SIGPIPE — это нормально,
# поэтому допускаем код 141; реальный сбой sdkmanager всё равно не скрыт)
yes | "$SDKMANAGER" --licenses > /dev/null || [ $? -eq 141 ] || true

for comp in "platforms;android-35" "build-tools;35.0.0" "ndk;27.0.12077973" "cmake;3.22.1"; do
  name=${comp#*;}
  present=""
  case "$comp" in
    platforms*) present="$SDK/platforms/$name" ;;
    build-tools*) present="$SDK/build-tools/$name" ;;
    ndk*) present="$SDK/ndk/$name" ;;
    cmake*) present="$SDK/cmake/$name" ;;
  esac
  if [ -e "$present" ]; then
    echo "$comp уже установлен"
  else
    echo ">>> Ставлю $comp…"
    "$SDKMANAGER" "$comp" > /dev/null
  fi
done

# --- local.properties ---------------------------------------------------
REPO="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$REPO/local.properties" ]; then
  echo "sdk.dir=$SDK" > "$REPO/local.properties"
  echo "Создан $REPO/local.properties"
fi

echo "Готово: JDK17 + SDK в $SDK"
