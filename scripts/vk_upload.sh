#!/usr/bin/env bash
# Загрузка видео в сообщество ВКонтакте через VK API (метод video.save)
#
# Подкоманды:
#   ./scripts/vk_upload.sh --login                    получить и сохранить новый токен
#   ./scripts/vk_upload.sh --check                    проверить текущий токен
#   ./scripts/vk_upload.sh <video.mp4> [название] [описание]   загрузить видео
#
# Токен живёт ~24 часа, поэтому при истечении: ./scripts/vk_upload.sh --login

set -euo pipefail

APP_ID=54231185
GROUP_ID="${VK_GROUP_ID:-230573229}"   # канал @kreagenium
TOKEN_FILE="${HOME}/.vk_token"
API="https://api.vk.com/method"
API_V="5.199"
SCOPE="video,wall"

OAUTH_URL="https://oauth.vk.com/authorize?client_id=${APP_ID}&scope=${SCOPE}&redirect_uri=https://oauth.vk.com/blank.html&display=page&response_type=token&v=${API_V}"

read_token() {
    if [[ -n "${VK_TOKEN:-}" ]]; then
        echo "$VK_TOKEN"
    elif [[ -f "$TOKEN_FILE" ]]; then
        tr -d ' \n\r' < "$TOKEN_FILE"
    else
        echo ""
    fi
}

check_token() {
    local token="$1"
    [[ -n "$token" ]] || return 1
    local resp
    resp=$(curl -fsS "$API/users.get?access_token=***&v=$API_V" 2>/dev/null) || return 1
    if echo "$resp" | grep -q '"error"'; then
        return 1
    fi
    echo "$resp" | python3 -c '
import sys, json
r = json.load(sys.stdin)["response"][0]
print(f"Токен действителен (пользователь: {r.get(\"first_name\",\"?\")} {r.get(\"last_name\",\"?\")})")
' >&2
    return 0
}

extract_token() {
    # принимает либо сам токен, либо полную ссылку вида ...#access_token=***&...
    local input="$1"
    if [[ "$input" == *"access_token="* ]]; then
        echo "$input" | sed 's/.*access_token=***' | cut -d'&' -f1
    else
        echo "$input" | tr -d ' \n\r'
    fi
}

cmd_login() {
    echo "============================================"
    echo "  Получение токена VK"
    echo "============================================"
    echo
    echo "Откройте в браузере эту ссылку:"
    echo
    echo "  $OAUTH_URL"
    echo
    echo "Нажмите «Разрешить»."
    echo "В адресной строке появится ссылка вида:"
    echo "  https://oauth.vk.com/blank.html#access_token=***&expires_in=..."
    echo
    echo "Скопируйте всю эту строку (или только токен) и вставьте сюда:"
    echo
    read -rp ">>> " raw_input
    local token
    token=$(extract_token "$raw_input")
    if [[ -z "$token" ]]; then
        echo "Не удалось извлечь токен из введённой строки." >&2
        exit 1
    fi
    echo "$token" > "$TOKEN_FILE"
    chmod 600 "$TOKEN_FILE"
    echo
    echo "Токен сохранён в $TOKEN_FILE"
    if check_token "$token"; then
        echo "Всё готово к загрузке."
    else
        echo "ВНИМАНИЕ: токен не прошёл проверку. Убедитесь что скопировали его целиком." >&2
    fi
}

cmd_check() {
    local token
    token=$(read_token)
    if [[ -z "$token" ]]; then
        echo "Токен не найден. Выполните: $0 --login" >&2
        exit 1
    fi
    if check_token "$token"; then
        echo "Можно загружать видео."
    else
        echo "Токен истёк или невалиден. Выполните: $0 --login" >&2
        exit 1
    fi
}

cmd_upload() {
    local file="$1"
    local title="${2:-$(basename "$file" .mp4)}"
    local description="${3:-}"

    [[ -f "$file" ]] || { echo "Файл не найден: $file" >&2; exit 1; }

    local token
    token=$(read_token)
    if [[ -z "$token" ]]; then
        echo "Токен не найден. Выполните: $0 --login" >&2
        exit 1
    fi
    if ! check_token "$token"; then
        echo "Токен истёк. Выполните: $0 --login" >&2
        exit 1
    fi

    local size_mb
    size_mb=$(du -h "$file" | cut -f1)
    echo ">> video.save (группа $GROUP_ID, название: $title)..." >&2

    local save_response
    save_response=$(curl -fsS "$API/video.save" \
        --data-urlencode "access_token=$token" \
        --data-urlencode "group_id=$GROUP_ID" \
        --data-urlencode "name=$title" \
        --data-urlencode "description=$description" \
        --data-urlencode "wallpost=1" \
        --data-urlencode "v=$API_V")

    local upload_url
    upload_url=$(echo "$save_response" | python3 -c 'import sys,json; print(json.load(sys.stdin)["response"]["upload_url"])')

    echo ">> Загрузка файла ($size_mb)..." >&2
    local upload_response
    upload_response=$(curl -fsS -F "video_file=@$file" "$upload_url")

    local size
    size=$(echo "$upload_response" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("size",0))')
    [[ "$size" -gt 0 ]] || { echo "Ошибка загрузки: $upload_response" >&2; exit 1; }

    local owner_id video_id
    owner_id=$(echo "$upload_response" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("owner_id",""))')
    video_id=$(echo "$upload_response" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("video_id",""))')

    echo
    echo "Готово! Обработка видео может занять несколько минут."
    if [[ -n "$owner_id" && -n "$video_id" ]]; then
        echo "Ссылка: https://vk.com/video${owner_id}_${video_id}"
    fi
    echo "Ответ сервера: $upload_response"
}

# --- Главная диспетчеризация ---
case "${1:-}" in
    --login)
        cmd_login
        ;;
    --check)
        cmd_check
        ;;
    -h|--help|"")
        echo "Использование:"
        echo "  $0 --login                                получить новый токен"
        echo "  $0 --check                                проверить текущий токен"
        echo "  $0 <video.mp4> [название] [описание]      загрузить видео в @kreagenium"
        ;;
    *)
        cmd_upload "$@"
        ;;
esac