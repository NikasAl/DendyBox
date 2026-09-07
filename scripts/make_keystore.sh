#!/usr/bin/env bash
# ============================================================================
# Генерация релизного ключа DendyBox (выполняется ОДИН раз на машине).
#
#   ./scripts/make_keystore.sh
#   DENDYBOX_KS_PASS="мойпароль" ./scripts/make_keystore.sh   # свой пароль
#
# Создаёт:
#   keystore/release.keystore      — ключ подписи релиза (PKCS12, RSA 2048)
#   keystore/keystore.properties   — пароли (его читает app/build.gradle.kts)
#
# ВАЖНО: папка keystore/ не хранится в git (репозиторий публичный).
# Сделайте резервную копию в надёжное место: без этого ключа вы не сможете
# публиковать ОБНОВЛЕНИЯ приложения — подпись каждой версии должна совпадать.
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KSDIR="$ROOT/keystore"
KS="$KSDIR/release.keystore"
PROPS="$KSDIR/keystore.properties"

if [ -f "$KS" ] && [ -f "$PROPS" ]; then
  echo "keystore уже существует: $KS"
  echo "Пароли: $PROPS"
  echo "Если ключ утерян — восстановите его из резервной копии, не создавайте новый."
  exit 0
fi

# --- поиск keytool (JDK 17 или любой JDK 8+) ---
find_keytool() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
    echo "$JAVA_HOME/bin/keytool"; return
  fi
  if command -v keytool >/dev/null 2>&1; then command -v keytool; return; fi
  for c in \
    /usr/lib/jvm/*/bin/keytool \
    /opt/java/*/bin/keytool \
    "$HOME"/.jdks/*/bin/keytool \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"; do
    for f in $c; do
      [ -x "$f" ] && { echo "$f"; return; }
    done
  done
  echo ""
}
KEYTOOL="$(find_keytool)"
if [ -z "$KEYTOOL" ]; then
  echo "ОШИБКА: keytool не найден."
  echo "Установите JDK 17 (dnf install java-17-openjdk-devel / apt install openjdk-17-jdk)"
  echo "или задайте JAVA_HOME и повторите."
  exit 1
fi

# --- пароль: случайный 32 hex-символа, либо из DENDYBOX_KS_PASS ---
PASS="${DENDYBOX_KS_PASS:-$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')}"

mkdir -p "$KSDIR"
echo "Генерирую ключ (alias: dendybox, RSA 2048, срок 30 лет)..."
"$KEYTOOL" -genkeypair -v \
  -keystore "$KS" -storetype PKCS12 \
  -alias dendybox -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=DendyBox, OU=Games, O=DendyBox, C=RU" > /dev/null

cat > "$PROPS" <<EOF
# Подпись релиза DendyBox (читается app/build.gradle.kts). НЕ коммитить!
store.file=keystore/release.keystore
store.password=$PASS
key.alias=dendybox
key.password=$PASS
EOF
chmod 600 "$PROPS"

echo ""
echo "Готово:"
echo "  ключ:     $KS  (alias dendybox)"
echo "  пароли:   $PROPS"
echo ""
echo "Пароль keystore: $PASS"
echo ""
echo ">>> СКОПИРУЙТЕ ПАПКУ keystore/ В НАДЁЖНОЕ МЕСТО (пароль + .keystore) <<<"
echo ">>> Без неё обновления приложения в магазине будут невозможны. <<<"
