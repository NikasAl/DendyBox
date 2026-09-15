#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""make_multicart.py — сборка многоигрового ROM для DendyBox.

Объединяет несколько UNROM-игр (iNES mapper 2, PRG 128КиБ, CHR-RAM) в один
ROM с меню выбора — по образу классических пиратских мультикартов Денди.

Схема: NES 2.0 Mapper 324 (FARID_UNROM_8-IN-1) — 8 «окон» по 128КиБ; внутри
окна игра работает как на обычном UNROM (свой фиксированный банк), поэтому
игры НЕ требуют патчей. Окно 0 — меню, окна 1..7 — игры.

Использование:
  python3 make_multicart.py -o kontra.nes -t "КОНТРА" -t "SUPER C" \
      kontra.nes superc.nes

Требования к играм: mapper 2 (UNROM), PRG ровно 128КиБ, CHR 0 (CHR-RAM),
без тренера и батарейки, одинаковая зеркальность у всех игр.
"""
import argparse
import pathlib
import sys

PRG_GAME = 128 * 1024           # PRG одной игры (8 банков по 16КиБ)
WINDOW = 128 * 1024             # размер окна маппера
MENU_BIN = pathlib.Path(__file__).parent / "menu" / "multicart_menu.bin"
MAX_GAMES = 7

MAPPER = 324                    # FARID_UNROM_8-IN-1 (NES 2.0)

# Кодировка текста в тайлы — должна совпадать с font_gen.py
CYR_UP = "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
CYR_LOW = "абвгдежзийклмнопрстуфхцчшщъыьэюя"
ENC = {chr(i): i - 0x20 for i in range(0x20, 0x60)}
ENC.update({ch: 64 + i for i, ch in enumerate(CYR_UP)})
ENC.update({ch: 96 + i for i, ch in enumerate(CYR_LOW)})
ENC["Ё"] = 128
ENC["ё"] = 129
ENC["↑"] = 130
ENC["↓"] = 131
UNKNOWN = ENC["?"]

# Смещения внутри menu/multicart_menu.bin (32КиБ, адрес = $8000 + смещение)
OFF_SCREEN = 0x0800             # 960 байт nametable
OFF_PALETTE = 0x0FC0            # 32 байта
OFF_COUNT = 0x4800              # число игр ($C800)

TITLE = "DENDBOX MULTIGAME"
HINT = "↑↓ ВЫБОР   СТАРТ/A ЗАПУСК"


class BuildError(Exception):
    pass


class Game:
    def __init__(self, path: pathlib.Path):
        self.path = path
        data = path.read_bytes()
        if len(data) < 16 or data[:4] != b"NES\x1a":
            raise BuildError(f"{path.name}: это не iNES ROM (нет заголовка NES\\x1a)")
        f6, f7, f8, f9 = data[6], data[7], data[8], data[9]
        self.nes2 = (f7 & 0x0C) == 0x08
        mapper = ((f6 >> 4) | (f7 & 0xF0))
        if self.nes2:
            mapper |= (f8 & 0x0F) << 8
        self.mapper = mapper
        prg_banks = data[4]
        chr_banks = data[5]
        if self.nes2:
            prg_banks |= (f9 & 0x0F) << 8
            chr_banks |= (f9 >> 4) << 8
        self.prg_size = prg_banks * 16 * 1024
        self.chr_size = chr_banks * 8 * 1024
        self.trainer = 512 if (f6 & 0x04) else 0
        self.battery = bool(f6 & 0x02)
        self.mirror_bit = f6 & 0x01   # 0 = vertical, 1 = horizontal
        expected = 16 + self.trainer + self.prg_size + self.chr_size
        if len(data) != expected:
            raise BuildError(
                f"{path.name}: размер файла {len(data)} не совпадает с заголовком "
                f"(ожидалось {expected}) — файл повреждён или имеет нестандартный дамп")
        self.prg = data[16 + self.trainer:16 + self.trainer + self.prg_size]


def check_game(g: Game) -> None:
    if g.mapper != 2:
        raise BuildError(
            f"{g.path.name}: mapper {g.mapper}, а нужен 2 (UNROM). "
            f"Игры с другими mapper'ами в мультикарт без ручной конвертации не входят")
    if g.prg_size != PRG_GAME:
        raise BuildError(
            f"{g.path.name}: PRG {g.prg_size // 1024}КиБ, а для окна маппера нужно ровно "
            f"128КиБ (UOROM/256КиБ не поддержаны)")
    if g.chr_size != 0:
        raise BuildError(
            f"{g.path.name}: в ROM зашита CHR-{g.chr_size // 1024}КиБ, а схема рассчитана "
            f"на CHR-RAM (как у Contra). Такие игры конвертировать нужно отдельно")
    if g.trainer:
        raise BuildError(f"{g.path.name}: тренер не поддержан")
    if g.battery:
        raise BuildError(f"{g.path.name}: батарейное питание (SAV) не поддержано платой 324")


def enc_text(s: str) -> list:
    out = []
    for ch in s.upper():
        out.append(ENC.get(ch, UNKNOWN))
    return out


def compose_screen(titles: list) -> bytes:
    """32x30 тайлов nametable: заголовок, список игр, подсказка."""
    scr = [0] * (32 * 30)

    def put(row, col, text):
        for i, tile in enumerate(enc_text(text)):
            if col + i < 32:
                scr[row * 32 + col + i] = tile

    put(1, (32 - len(TITLE)) // 2, TITLE)
    for i, t in enumerate(titles):
        put(5 + i, 3, f"{i + 1} {t}")
    put(27, (32 - len(HINT)) // 2, HINT)
    return bytes(scr)


PALETTE = bytes([0x0F, 0x30, 0x10, 0x00] * 8)   # фон чёрный, текст белый


def patch_menu(menu: bytearray, titles: list) -> None:
    menu[OFF_COUNT] = len(titles)
    menu[OFF_SCREEN:OFF_SCREEN + 960] = compose_screen(titles)
    menu[OFF_PALETTE:OFF_PALETTE + 32] = PALETTE


def ines20_header(games: int, mirror_bit: int) -> bytes:
    h = bytearray(16)
    h[0:4] = b"NES\x1a"
    h[4] = (games + 1) * 8            # банки PRG по 16КиБ (окно = 8 банков)
    h[5] = 0                          # CHR-RAM (0 + CHR-RAM на плате)
    h[6] = 0x40 | (mirror_bit & 1)    # mapper 324, биты 0-3 = 4
    h[7] = 0x08 | 0x40                # NES 2.0 + mapper биты 4-7 = 4
    h[8] = 0x01                       # mapper биты 8-11 = 1
    # h[9..15] = 0: без PRG/CHR-RAM, регион NTSC
    assert (h[6] >> 4) | (h[7] & 0xF0) | ((h[8] & 0x0F) << 8) == MAPPER
    return bytes(h)


def main() -> int:
    ap = argparse.ArgumentParser(description="Сборка мультирома DendyBox (mapper 324)")
    ap.add_argument("roms", nargs="+", type=pathlib.Path, help="UNROM-игры (.nes, 128КиБ, CHR-RAM)")
    ap.add_argument("-o", "--output", required=True, type=pathlib.Path, help="выходной файл .nes")
    ap.add_argument("-t", "--title", action="append", default=[], help="название игры в меню (по порядку)")
    args = ap.parse_args()

    if not MENU_BIN.exists():
        raise BuildError(f"нет {MENU_BIN} — соберите меню: asm6f menu/menu.asm menu/multicart_menu.bin")
    if not (1 <= len(args.roms) <= MAX_GAMES):
        raise BuildError(f"игр должно быть от 1 до {MAX_GAMES}, получено {len(args.roms)}")

    games = [Game(p) for p in args.roms]
    for g in games:
        check_game(g)

    mirrors = {g.mirror_bit for g in games}
    if len(mirrors) > 1:
        names = ", ".join(f"{g.path.name}: {'гориз.' if g.mirror_bit else 'верт.'}" for g in games)
        raise BuildError(
            "разная зеркальность у игр, а плата мультикарта жёстко разводит одну:\n  " +
            names + "\n  (приведите ROM'ы к одной зеркальности или пропатчите игры)")

    titles = []
    for i, g in enumerate(games):
        t = args.title[i] if i < len(args.title) else g.path.stem
        t = t.upper().strip()
        bad = {ch for ch in t if ch not in ENC}
        if bad:
            print(f"ВНИМАНИЕ: нет глифов для {''.join(sorted(bad))} в «{t}» — заменены на '?'")
            t = "".join(ch if ch in ENC else "?" for ch in t)
        if len(t) > 21:
            print(f"ВНИМАНИЕ: название «{t}» длиннее 21 символа — обрезано")
            t = t[:21]
        titles.append(t)

    menu = bytearray(MENU_BIN.read_bytes())
    patch_menu(menu, titles)

    out = bytearray()
    out += ines20_header(len(games), games[0].mirror_bit)
    out += menu[0x0000:0x4000]            # окно 0, банк 0: шрифт/экран/палитра
    out += b"\xFF" * (WINDOW - 0x8000)    # окно 0, банки 1-6: заполнитель
    out += menu[0x4000:0x8000]            # окно 0, банк 7: код меню + векторы
    for g in games:
        out += g.prg                      # окно i: полный PRG игры

    args.output.write_bytes(bytes(out))
    mb = len(out) / 1024
    print(f"Готово: {args.output} ({mb:.0f}КиБ, mapper {MAPPER}, "
          f"зеркальность {'горизонтальная' if games[0].mirror_bit else 'вертикальная'})")
    print(f"Игр: {len(games)}")
    for i, t in enumerate(titles):
        print(f"  {i + 1} {t}  <- {games[i].path.name}")
    print("Проверка: сборник = обычный ROM для DendyBox/эмуляторов с mapper 324 "
          "(FCEUmm, Mesen). RESET возвращает в меню.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BuildError as e:
        print(f"ОШИБКА: {e}", file=sys.stderr)
        sys.exit(1)
