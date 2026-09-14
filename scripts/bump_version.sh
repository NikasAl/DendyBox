#!/usr/bin/env bash
# ============================================================================
# Повышение версии игры (flavor'а) в roms/games.json.
#
#   ./scripts/bump_version.sh robocop3 1.2        — версия 1.2, versionCode +1
#   ./scripts/bump_version.sh robocop3 1.2 7      — версия 1.2, versionCode 7
#   ./scripts/bump_version.sh robocop3            — показать текущую версию
#
# Версия хранится в roms/games.json (поля versionName/versionCode) и при
# сборке пробрасывается в манифест APK. Для обновления в магазине
# (RuStore и др.) должен строго расти versionCode.
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GAMES="$ROOT/roms/games.json"

if [ $# -lt 1 ]; then
  echo "Использование: $0 <flavor> [новая_версия] [versionCode]"
  echo ""
  echo "Примеры:"
  echo "  $0 robocop3 1.2      — versionName=1.2, versionCode +1 (авто)"
  echo "  $0 robocop3 1.2 7    — versionName=1.2, versionCode=7 (явно)"
  echo "  $0 robocop3          — показать текущую версию"
  echo ""
  echo "Текущие версии:"
  python3 - "$GAMES" <<'PY'
import json, sys
try:
    cfg = json.load(open(sys.argv[1], encoding='utf-8'))
except Exception:
    sys.exit(0)
for fname, meta in cfg.items():
    if isinstance(meta, dict):
        vn = meta.get('versionName', '1.0')
        vc = meta.get('versionCode', 1)
        print(f"  {meta.get('flavor', fname)}: {vn} (versionCode {vc})")
PY
  exit 0
fi

FLAVOR="$1"
NEW_NAME="${2:-}"
NEW_CODE="${3:-}"

python3 - "$GAMES" "$FLAVOR" "$NEW_NAME" "$NEW_CODE" <<'PY'
import json, re, sys

path, flavor, new_name, new_code = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
cfg = json.load(open(path, encoding='utf-8'))

# Найти запись по flavor (или по имени файла, если flavor не указан явно)
key = None
for fname, meta in cfg.items():
    if not isinstance(meta, dict):
        continue
    fl = meta.get('flavor') or fname.rsplit('.', 1)[0].lower()
    if fl == flavor:
        key = fname
        break
if key is None:
    print(f"ОШИБКА: flavor «{flavor}» не найден в roms/games.json", file=sys.stderr)
    print("Есть:", ", ".join((m.get('flavor') if isinstance(m, dict) else '') or f for f, m in cfg.items()), file=sys.stderr)
    sys.exit(1)

meta = cfg[key]
old_name = str(meta.get('versionName', '1.0'))
old_code = int(meta.get('versionCode', 1))

if not new_name:
    print(f"{flavor}: {old_name} (versionCode {old_code})")
    sys.exit(0)

if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,31}', new_name):
    print(f"ОШИБКА: versionName «{new_name}» — допустимы латиница/цифры/./_/- (до 32 символов)", file=sys.stderr)
    sys.exit(1)

if new_code:
    if not new_code.isdigit() or not (1 <= int(new_code) <= 2_100_000_000):
        print(f"ОШИБКА: versionCode {new_code} — должно быть целым числом 1..2100000000", file=sys.stderr)
        sys.exit(1)
    code = int(new_code)
else:
    code = old_code + 1

meta['versionName'] = new_name
meta['versionCode'] = code

with open(path, 'w', encoding='utf-8') as f:
    json.dump(cfg, f, ensure_ascii=False, indent=2)
    f.write('\n')

print(f"{flavor}: {old_name} ({old_code}) -> {new_name} ({code})")
if code <= old_code:
    print(f"ВНИМАНИЕ: versionCode {code} не вырос ({old_code}) — обновление в магазине может не принять такой APK", file=sys.stderr)
PY
