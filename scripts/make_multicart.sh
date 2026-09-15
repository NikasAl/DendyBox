#!/usr/bin/env bash
# Сборка многоигрового ROM (сборника) для DendyBox из нескольких UNROM-игр.
#
#   ./scripts/make_multicart.sh -o kontra.nes -t "КОНТРА" -t "SUPER C" \
#       roms/kontra1.nes roms/superc.nes
#
# Подробнее: tools/multicart/README.md
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$ROOT/tools/multicart/make_multicart.py" "$@"
