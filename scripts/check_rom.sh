#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# check_rom.sh — декодер iNES/NES 2.0 заголовка ROM-файла.
#
# Использование:
#   ./scripts/check_rom.sh app/src/main/assets/rom.nes
#
# Показывает: магию, размер PRG/CHR, номер маппера, мирроринг, батарею,
# трейнер и сравнивает фактический размер файла с ожидаемым по заголовку.
# ---------------------------------------------------------------------------
set -euo pipefail

f="${1:-}"
if [ -z "$f" ] || [ ! -f "$f" ]; then
  echo "Использование: $0 <файл.nes>" >&2
  exit 2
fi

if command -v stat >/dev/null 2>&1; then
  fsize="$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f" 2>/dev/null || echo 0)"
else
  fsize=0
fi

# 16 байт заголовка как десятичные числа
set -- $(od -A n -t u1 -N 16 -- "$f")
b0=$1; b1=$2; b2=$3; b3=$4
prg=$5; chr=$6; f6=$7; f7=$8
f9=${10:-0}   # нужен только для NES 2.0

echo "Файл: $f ($fsize байт)"

if [ "$b0" = 78 ] && [ "$b1" = 69 ] && [ "$b2" = 83 ] && [ "$b3" = 26 ]; then
  echo "Магия:      NES\\x1A — iNES, OK"
else
  echo "МАГИЯ БИТА: первые байты $b0 $b1 $b2 $b3 (нужно 78 69 83 26 = 'NES'\\x1A)"
  echo "            Это точно не iNES-файл: возможно UNIF (.unf), NSF, FDS (.fds)"
  echo "            или файл скачан/скопирован с повреждением."
  exit 1
fi

mapper=$(( (f7 & 0xF0) | (f6 >> 4) ))
if [ $(( (f7 & 0x0C) == 8 )) -eq 1 ]; then
  nes2="да (NES 2.0)"
  mapper=$(( mapper | ( (f9 & 0x0F) << 8 ) ))
else
  nes2="нет (iNES 1.0)"
fi

case $(( f6 & 1 )) in
  0) mir="горизонтальная" ;;
  1) mir="вертикальная" ;;
esac
[ $(( f6 & 8 )) -ne 0 ] && mir="четырёхэкранная"

prg_kb=$(( prg * 16 ))
chr_kb=$(( chr * 8 ))

echo "PRG ROM:    $prg банков × 16 КиБ = $prg_kb КиБ"
echo "CHR ROM:    $chr банков × 8 КиБ = $chr_kb КиБ (0 = CHR-RAM)"
echo "Маппер:     $mapper  (формат: $nes2)"
echo "Мирроринг:  $mir"
[ $(( f6 & 2 )) -ne 0 ] && echo "Батарея:    да (SRAM)" || echo "Батарея:    нет"
[ $(( f6 & 4 )) -ne 0 ] && trainer=1 || trainer=0
[ "$trainer" = 1 ] && echo "Трейнер:    да (512 байт после заголовка)"

expected=$(( 16 + (trainer * 512) + prg * 16384 + chr * 8192 ))
if [ "$fsize" -eq "$expected" ]; then
  echo "Размер:     совпадает с заголовком ($expected байт) — файл цел"
else
  diff=$(( fsize - expected ))
  echo "Размер:     НЕ совпадает: фактический $fsize, ожидаемый $expected (разница $diff байт)"
  [ "$diff" -gt 0 ] && echo "            (лишние данные — возможно, файл не .nes или склеен)" \
                    || echo "            (файл обрезан!)"
fi

# Памятка по поддержке: FCEUmm поддерживает практически все NES-мапперы (1–499),
# включая MMC1 (маппер 1) и UxROM (2), CNROM (3).
