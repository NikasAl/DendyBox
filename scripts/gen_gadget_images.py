#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ассеты пакет-скина «Гаджет» (корпус консоли, референс Dandy Starper).

Переиспользует генераторы gen_skin_images.py (тот же стиль и качество:
квадрат 512x512, суперсэмплинг x4, Lanczos, подписи НЕ запечены —
их рисует приложение). Три файла в app/src/main/res/drawable-nodpi:

  skin_gadget_dpad.png — крестовина-крест: тёмно-графитовая на светлом теле;
  skin_gadget_btn.png  — круглая кнопка B/A/TURBO: ярко-красная с тёмным ободком;
  skin_gadget_pill.png — пилюля SELECT/START: светло-серая с серым контуром.

Запуск из корня репозитория: python3 scripts/gen_gadget_images.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_skin_images import make_button, make_cross, make_pill  # noqa: E402

if __name__ == "__main__":
    # Крестовина: тёмный графит — читается на белом теле корпуса
    make_cross("skin_gadget_dpad.png",
               top_col=(74, 74, 82, 255), bot_col=(26, 26, 31, 255),
               outline_col=(104, 104, 112, 255), outline_w=2)
    # Кнопки B/A (и турбо): ярко-красные, как на референсе
    make_button("skin_gadget_btn.png",
                inner=(243, 97, 76), outer=(171, 27, 16),
                rim_col=(125, 16, 10, 255), rim_w=2.5)
    # Пилюли SELECT/START: светло-серые с заметным контуром на белом теле
    make_pill("skin_gadget_pill.png",
              top_col=(240, 238, 230, 255), bot_col=(206, 203, 192, 255),
              outline_col=(126, 124, 116, 255), outline_w=2)
