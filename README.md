# DendyBox — Android-эмулятор NES (одна игра — одно приложение)

Ретро-проект «для себя и друзей»: в каждое приложение зашивается ОДИН ROM
(«одна игра — один картридж»), удобный экранный джойстик с постоянной
крестовиной, турбо-кнопки, читы из FCEUX и сейв-стейты. Каркас построен на
**libretro-ядре FCEUmm** (C, загружается через dlopen) + **Kotlin/Compose** UI +
минимальный **JNI/C++ фронтенд**.

---

## Шаг 1. ROM

ROM'ы лежат в папке `roms/` в корне проекта (в git НЕ коммитятся — только
`roms/README.md` и `roms/games.json`):

```
roms/
├── games.json      — описание сборок: файл ROM → flavor + название игры
└── robocop3.nes    — сам ROM (у вас локально)
```

`games.json`:

```json
{
  "robocop3.nes": { "flavor": "robocop3", "title": "RoboCop 3" }
}
```

Каждый ROM = отдельное приложение `com.dendybox.app.<flavor>` с названием
«DendyBox: <title>». Если записи в `games.json` нет — flavor создаётся
автоматически из имени файла. Подробности — `roms/README.md`.
Проверка формата: `./scripts/check_rom.sh roms/robocop3.nes`

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

**3. Одной командой (debug-вариант выбранной игры):**

```bash
./scripts/run.sh robocop3      # собрать (debug) + установить + запустить
./scripts/run.sh robocop3 log  # то же + живой logcat приложения
./scripts/run.sh robocop3 build # только APK
./scripts/run.sh clean         # пересборка с нуля
```

Скрипт сам найдёт SDK и adb, создаст `local.properties`, подскажет, если
устройство не видно. Вручную, по шагам, то же самое (вариант сборки
выбирается в Android Studio: Build Variants → `robocop3Debug`):

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew installRobocop3Debug  # сборка + установка на подключённое устройство
adb shell am start -n com.dendybox.app.robocop3/com.dendybox.app.MainActivity
adb logcat --pid=$(adb shell pidof -s com.dendybox.app.robocop3)
```

**4. Отладка по USB:** Настройки → «Для разработчиков» → «Отладка по USB»
(подтвердить запрос на телефоне). Беспроводно: `adb tcpip 5555 &&
adb connect <IP-телефона>:5555`. Несколько устройств —
`export ANDROID_SERIAL=<serial из adb devices>`.

---

## Релизные сборки под игру (подпись + оптимизация)

Одна игра — одно подписанное приложение `com.dendybox.app.<flavor>`.

```bash
./scripts/build_release.sh                 # список доступных игр (roms/)
./scripts/build_release.sh robocop3        # подписанный релиз
./scripts/build_release.sh robocop3 debug  # отладочная сборка этой же игры
```

Что делает сборка:

* **Подпись релиза**: ключ из `keystore/release.keystore` (создаётся один раз
  `./scripts/make_keystore.sh`, пароли — в `keystore/keystore.properties`).
  Папка `keystore/` в git не хранится — СДЕЛАЙТЕ ЕЁ БЭКАП: без того же ключа
  обновление в магазине не примут.
* **Оптимизация размера**: без x86/x86_64 (только `arm64-v8a` +
  `armeabi-v7a`), R8-минификация + сжатие ресурсов, локали только `ru`.
* **Имя APK**: `app/build/outputs/apk/<flavor>/release/DendyBox-<flavor>-<версия>-release.apk`.
* ROM не положен в `roms/` — сборка падает с понятной ошибкой (не молча).

### Скриншоты для магазина (adb)

```bash
./scripts/screenshots.sh robocop3 6 5   # 6 снимков раз в 5 секунд
```

Скрипт ставит разрешение экрана в **16:9 (1920x1080)**, устанавливает APK,
запускает игру и снимает экран в `screenshots/<flavor>/shot_NN.png`, затем
возвращает разрешение как было. Во время съёмки играйте на телефоне.

### Метаданные для RuStore

Тексты карточки — в `metadata/<flavor>/`: название, краткое/полное описание
(ностальгия по Dendy и 90-м), «что нового», категория, рейтинг. Чек-лист
публикации — `metadata/README.md`.

---

## Управление

| Действие | Как |
|---|---|
| **Крестовина** | Постоянно слева: касание в любом месте зоны крестовины сразу нажимает нужное направление (8 направлений, сектора по 45°, гистерезис). |
| **A / B** | Круглые кнопки справа (B слева — как на джойстике Dendy). |
| **Турбо B′ / A′** | Маленькие кнопки над A/B: удерживаете — идёт автоповтор (частота настраивается). |
| **Select / Start** | Отдельным блоком под крестовиной/кнопками. |
| **Квик-сейв / квик-лоад** | Иконки сверху. |
| **Меню** | Кнопка «Пауза» слева сверху. |
| **Редактор раскладки** | Шестерёнка в игровом топ-баре (или Пауза → «Настройки управления»): выбор блока (крестовина / A/B / Select-Start), перетаскивание, щипок или слайдер масштаба, сброс. |

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
