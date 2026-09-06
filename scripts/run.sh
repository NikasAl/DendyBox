#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# DendyBox: сборка, установка и запуск БЕЗ Android Studio (gradle + adb).
#
# Использование:
#   ./scripts/run.sh           собрать (debug), установить, запустить
#   ./scripts/run.sh build     только собрать APK (app/build/outputs/apk/debug/)
#   ./scripts/run.sh log       собрать/установить/запустить + поток logcat
#   ./scripts/run.sh clean     пересборка с нуля (clean + install + запуск)
#
# Требования: JDK 17 (JAVA_HOME или java в PATH), Android SDK.
# ANDROID_HOME берётся из окружения или local.properties (создаётся сам).
# Если подключено несколько устройств — экспортируйте ANDROID_SERIAL=<serial>.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-run}"
PKG="com.dendybox.app"

# --- java -----------------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
  echo "ОШИБКА: java не найдена. Нужен JDK 17:
  • Fedora/RHEL:  sudo dnf install java-17-openjdk-devel
  • Ubuntu/Debian: sudo apt install openjdk-17-jdk
  • или задайте JAVA_HOME на распакованный JDK." >&2
  exit 1
fi

# --- Android SDK ----------------------------------------------------------
# Определяем SDK и при необходимости создаём local.properties для gradle.
SDK=""
for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
  if [ -n "$c" ] && [ -d "$c/platform-tools" ]; then SDK="$c"; break; fi
done
if [ -z "$SDK" ]; then
  echo "ОШИБКА: Android SDK не найден. Установите command-line tools:
  https://developer.android.com/studio#command-line-tools-only
  и выполните:
  sdkmanager \"platform-tools\" \"platforms;android-35\" \"build-tools;35.0.0\" \"cmake;3.22.1\" \"ndk;28.0.13039338\"
  затем экспортируйте ANDROID_HOME=/путь/к/Sdk" >&2
  exit 1
fi
if [ ! -f "$ROOT/local.properties" ]; then
  printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/local.properties"
  echo "==> Создан local.properties: sdk.dir=$SDK"
fi

# --- adb ------------------------------------------------------------------
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  echo "ОШИБКА: adb не найден в $SDK/platform-tools ни в PATH." >&2
  exit 1
fi

need_device() {
  if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "ОШИБКА: устройство не найдено. Проверьте:" >&2
    echo "  • На телефоне включена «Отладка по USB» (Настройки → Для разработчиков);" >&2
    echo "  • Подтверждён запрос «Разрешить отладку» на телефоне;" >&2
    echo "  • ./$ADB devices  — устройство в статусе device (не unauthorized)." >&2
    echo "  • Беспроводной вариант: adb tcpip 5555 && adb connect <IP>:5555" >&2
    exit 1
  fi
}

cd "$ROOT"

# --- сборка ---------------------------------------------------------------
case "$MODE" in
  build) GRADLE_TASKS="assembleDebug" ;;
  clean) GRADLE_TASKS="clean installDebug" ;;
  *)     GRADLE_TASKS="installDebug" ;;
esac

echo "==> ./gradlew $GRADLE_TASKS"
./gradlew "$GRADLE_TASKS" --console=plain -q

# APK собран, но устройство не требуется (режим build)
if [ "$MODE" = "build" ]; then
  echo
  echo "Готово: $ROOT/app/build/outputs/apk/debug/app-debug.apk"
  exit 0
fi

# --- установка + запуск ----------------------------------------------------
need_device
echo "==> Установлено. Запускаю $PKG/.MainActivity"
"$ADB" shell am force-stop "$PKG" 2>/dev/null || true
"$ADB" shell am start -n "$PKG/.MainActivity"

# --- logcat ----------------------------------------------------------------
if [ "$MODE" = "log" ]; then
  sleep 1
  PID="$("$ADB" shell pidof -s "$PKG" | tr -d '\r' || true)"
  echo "==> logcat (PID=$PID, Ctrl+C — выход)"
  if [ -n "$PID" ]; then
    exec "$ADB" logcat --pid="$PID"
  else
    exec "$ADB" logcat | grep --line-buffered -i dendybox
  fi
fi

echo "Готово. Логи: ./scripts/run.sh log  или  adb logcat --pid=\$(adb shell pidof -s $PKG)"
