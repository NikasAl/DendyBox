# Правила R8/ProGuard для release-сборки DendyBox

# JNI-мост: методы вызываются из C++ по имени — не переименовывать
-keep class com.dendybox.app.Native { *; }
-keepclassmembers class com.dendybox.app.Native { *; }

# org.json — платформенный, не трогаем
-dontwarn org.json.**

# Общая страховка JNI: native-методы не переименовывать (на случай будущих мостов)
-keepclasseswithmembernames class * { native <methods>; }
