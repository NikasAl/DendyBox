// Корневой build-файл. Версии подобраны совместимыми:
// AGP 8.7.3 требует Gradle 8.9+ (wrapper: 8.10.2) и JDK 17.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
