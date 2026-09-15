#!/usr/bin/env python3
"""Генератор тестовых UNROM-игр для мультикарта.

Собирает test_game.asm (asm6f) с заданным цветом фона и упаковывает в
полноценный iNES mapper 2 ROM: PRG 128КиБ (банки 0-6 = $FF, банк 7 = код).

Использование: python3 make_test_game.py out.nes $2A
"""
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).parent


def main():
    out = pathlib.Path(sys.argv[1])
    color = sys.argv[2]                       # например $2A
    src = (HERE / "test_game.asm").read_text(encoding="utf-8")
    src = src.replace("GAME_COLOR = $2A", f"GAME_COLOR = {color}", 1)
    tmp_src = HERE / "_test_game_tmp.asm"
    tmp_bin = HERE / "_test_game_tmp.bin"
    tmp_src.write_text(src, encoding="utf-8")
    asm6f = HERE.parent / "asm6f" / "asm6f"
    r = subprocess.run([str(asm6f), tmp_src.name, tmp_bin.name],
                       cwd=HERE, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout, r.stderr)
        sys.exit("asm6f failed")
    code = tmp_bin.read_bytes()               # $C000-$FFFF (16КиБ)
    tmp_src.unlink()
    tmp_bin.unlink(missing_ok=True)
    assert len(code) == 16384, len(code)

    prg = b"\xFF" * (112 * 1024) + code       # банки 0-6 + фиксированный банк
    h = bytearray(16)
    h[0:4] = b"NES\x1a"
    h[4] = 8                                  # PRG 128КиБ
    h[5] = 0                                  # CHR-RAM
    h[6] = 0x20                               # mapper 2, вертикальная зеркальность
    out.write_bytes(bytes(h) + prg)
    print(f"OK: {out} ({16 + len(prg)} байт, mapper 2, цвет {color})")


if __name__ == "__main__":
    main()
