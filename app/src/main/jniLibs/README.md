# jniLibs — сюда кладётся ядро эмулятора

Скрипт `scripts/build_core_fceumm.sh` скачает/соберёт ядро FCEUmm и положит его сюда:

```
app/src/main/jniLibs/arm64-v8a/libcore_nes.so
app/src/main/jniLibs/armeabi-v7a/libcore_nes.so   (опционально)
app/src/main/jniLibs/x86_64/libcore_nes.so        (для эмулятора Android Studio)
```

Имя файла должно быть ровно `libcore_nes.so` — приложение грузит его через dlopen
из каталога `applicationInfo.nativeLibraryDir`.

Быстрый вариант (без сборки): скачать готовое ядро с buildbot libretro:
https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/cores/fceumm_libretro_android.so.zip
— распаковать, переименовать .so в libcore_nes.so и положить в нужный каталог ABI.
