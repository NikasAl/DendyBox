#!/usr/bin/env bash
# Сборка ассемблера asm6f (форк loopy's asm6, public domain).
# Результат: tools/multicart/asm6f/asm6f (Linux; на других ОС — gcc asm6f.c)
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
gcc -O2 -o "$DIR/asm6f" "$DIR/asm6f.c" -lm
echo "OK: $DIR/asm6f"
