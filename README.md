# DendyBox — каркас Android-эмулятора NES

Ретро-проект «для себя и друзей»: один встроенный ROM, удобный экранный
джойстик с плавающей крестовиной, турбо-кнопки, читы из FCEUX и сейв-стейты.
Каркас построен на **libretro-ядре FCEUmm** (C, загружается через dlopen) +
**Kotlin/Compose** UI + минимальный **JNI/C++ фронтенд**.

---

## Шаг 1. ROM

Положите ваш ROM в:

```
app/src/main/assets/rom.nes
```

(файл должен называться именно `rom.nes`; рядом лежит `README_ROM.txt`).

## Шаг 2. Ядро эмулятора

Приложение грузит ядро по имени `libcore_nes.so` из `jniLibs`. Получить его:

**Вариант А (быстрый)** — скрипт скачивает готовое ядро с buildbot libretro:

```bash
cd DendyBox
./scripts/build_core_fceumm.sh            # arm64-v8a (большинство современных телефонов)
./scripts/build_core_fceumm.sh all        # все ABI, включая x86_64 для эмулятора
```

**Вариант Б (вручную)** — скачать и распаковать:

```
https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/fceumm_libretro_android.so.zip
```

переименовать `.so` в `libcore_nes.so` и положить в
`app/src/main/jniLibs/arm64-v8a/`.

**Вариант В (из исходников)** — скрипт сам сделает, если скачивание не удалось
и задан `ANDROID_NDK_HOME` (Android Studio → SDK Manager → SDK Tools → NDK).

## Шаг 3. Сборка и запуск

1. Android Studio (JDK 17) → *Open* → папка `DendyBox`.
2. Если Studio ругается на Gradle wrapper — просто дайте ей синхронизироваться
   (wrapper-properties уже настроены на Gradle 8.10.2 / AGP 8.7.3 / Kotlin 2.0.21).
   Также убедитесь, что в SDK Manager установлены: **SDK 35, NDK, CMake 3.22.1**.
3. `Run` на устройстве или эмуляторе (для эмулятора нужно ядро `x86_64`).

> Это каркас: он написан под компиляцию «с листа», но в редких случаях возможны
> мелкие несовпадения версий — правятся в одну строку (обычно версия зависимости).

## Сборка и запуск без Android Studio (gradle + adb)

Всё можно делать из терминала — Studio не нужна.

**1. JDK 17 + Android SDK (command-line tools):**

```bash
# Fedora/RHEL:
sudo dnf install java-17-openjdk-devel
# Ubuntu/Debian:
sudo apt install openjdk-17-jdk

# Command-line tools (https://developer.android.com/studio#command-line-tools-only):
mkdir -p ~/Android/Sdk/cmdline-tools
unzip commandlinetools-*.zip -d ~/Android/Sdk/cmdline-tools/latest
export ANDROID_HOME=$HOME/Android/Sdk
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" \
           "build-tools;35.0.0" "cmake;3.22.1" "ndk;28.0.13039338"
```

**2. ROM и ядро** — Шаги 1–2 выше.

**3. Одной командой:**

```bash
./scripts/run.sh          # собрать (debug) + установить + запустить
./scripts/run.sh log      # то же + живой logcat приложения
./scripts/run.sh build    # только APK: app/build/outputs/apk/debug/app-debug.apk
./scripts/run.sh clean    # пересборка с нуля
```

Скрипт сам найдёт SDK и adb, создаст `local.properties`, подскажет, если
устройство не видно. Вручную, по шагам, то же самое:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew installDebug          # сборка + установка на подключённое устройство
adb shell am start -n com.dendybox.app/.MainActivity
adb logcat --pid=$(adb shell pidof -s com.dendybox.app)
```

**4. Отладка по USB:** Настройки → «Для разработчиков» → «Отладка по USB»
(подтвердить запрос на телефоне). Беспроводно: `adb tcpip 5555 &&
adb connect <IP-телефона>:5555`. Несколько устройств —
`export ANDROID_SERIAL=<serial из adb devices>`.

---

## Управление

| Действие | Как |
|---|---|
| **Крестовина** | Коснитесь левой трети экрана — крестовина появится под пальцем; смещение пальца = направление (8 направлений, сектора по 45°, с гистерезисом). Отпустили — исчезла. |
| **A / B** | Круглые кнопки справа. |
| **Турбо A′ / B′** | Маленькие кнопки: удерживаете — идёт автоповтор (частота настраивается, по умолчанию 14 Гц). |
| **Select / Start** | Внизу по центру. |
| **Квик-сейв / квик-лоад** | Иконки сверху. |
| **Меню** | Кнопка «Пауза» слева сверху. |
| **Редактор раскладки** | Пауза → «Раскладка кнопок»: перетаскивание любой кнопки, слайдер размера, прозрачность, сброс. |

Каждый контрол «прилипает» к своему пальцу: крестовина и огонь работают
одновременно (multi-touch).

## Читы из FCEUX

1. На компьютере в FCEUX найдите читы (Cheat Search) и добавьте их в список.
2. Cheats → Export → получите текстовый `.cht` (строки вида `0738:09`,
   возможны `0738+FF:09` с сравнением и коды Game Genie).
3. В приложении: Пауза → Читы → иконка импорта → выберите файл.
   Проверяйте читы переключателями; кнопка экспорта сохраняет список обратно в `.cht`.

Механика: RAM-читы применяются в память ядра каждый кадр до `retro_run()` —
это точная семантика FCEUX. Game Genie передаётся ядру через `retro_cheat_set`.

## Сейвы

- Квик-слот + слоты 1–10 с PNG-миниатюрами (Пауза → «Слоты сохранений»).
- Автосейв при сворачивании приложения/выходе в меню; при запуске игра
  автоматически продолжается с него.
- Файлы: `/data/data/com.dendybox.app/files/states/<hash ROM>/`.

## Архитектура (карта файлов)

```
app/src/main/
├── cpp/
│   ├── libretro.h          — минимальное подмножество libretro API
│   ├── nesoid.cpp          — фронтенд: dlopen ядра, колбэки видео/звука/ввода,
│   │                         патчи памяти, сейв-стейты, JNI
│   └── CMakeLists.txt
├── java/com/dendybox/app/
│   ├── Native.kt           — JNI-объявления
│   ├── emulator/EmulatorEngine.kt — поток эмуляции, AudioTrack, Canvas-blit,
│   │                                слоты сейвов, применение читов
│   ├── input/InputState.kt — битмаски кнопок + турбо на кадрах
│   ├── cheats/CheatRepository.kt  — парсер .cht + Game Genie, хранение по хэшу ROM
│   ├── saves/SaveManager.kt       — слоты/квик/автосейв + миниатюры
│   ├── settings/SettingsStore.kt  — настройки (частоты турбо, звук, вибро…)
│   └── ui/
│       ├── controls/       — плавающая крестовина, кнопки, редактор раскладки
│       └── screens/        — игра, пауза, слоты, читы, настройки
└── AndroidManifest.xml
```

Кадр: ядро (RGB565) → прямой ByteBuffer → `Bitmap` → `Canvas.drawBitmap`
с целочисленным масштабом. Звук: ядро → кольцевой буфер → `AudioTrack`
(частота берётся из av_info ядра). Тайтлинг потока — по fps ядра (60.0988).

## Что доработать дальше (по мере интереса)

- [ ] Заменить Canvas-blit на GLES-текстуру и AudioTrack на Oboe (latency, фильтры изображения).
- [ ] Скрытие «рamки» (overscan 8px) опцией.
- [ ] Второй джойстик на одном устройстве (порт 2 уже поддержан ядром — нужен UI).
- [ ] Netplay по Wi-Fi (lockstep: обмен 1 байтом кнопок на кадр, input delay 2–3 кадра,
      стартовая синхронизация через save state, контроль рассинхрона по хэшу RAM).
- [ ] Если кнопки A/B ощущаются перепутанными в конкретной игре — поменяйте местами
      биты в `InputState` (некоторые ядра трактуют B/A зеркально).

## Замечания

- Правовой статус ROM — на вашей совести (личное использование, без публикации).
- Турбо считается по номеру кадра — это заготовка под детерминированный netplay.
