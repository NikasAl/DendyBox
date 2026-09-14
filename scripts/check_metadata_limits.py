#!/usr/bin/env python3
"""Проверка лимитов RuStore для текстов карточек metadata/<flavor>/."""
import sys

LIMITS = {
    "title.txt": 30,
    "short_description.txt": 80,
    "full_description.txt": 4000,
    "release_notes.txt": 500,
}

ok = True
for flavor in sys.argv[1:]:
    print(f"=== {flavor} ===")
    for name, limit in LIMITS.items():
        try:
            text = open(f"metadata/{flavor}/{name}", encoding="utf-8").read().strip()
        except FileNotFoundError:
            continue
        n = len(text)
        mark = "OK " if n <= limit else "ПРЕВЫШЕНИЕ!"
        if n > limit:
            ok = False
        print(f"  {name:<24} {n:>5}/{limit}  {mark}")
print("ВСЁ В НОРМЕ" if ok else "ЕСТЬ ПРЕВЫШЕНИЯ")
sys.exit(0 if ok else 1)
