import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import groovy.json.JsonSlurper
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ============================================================================
// Игры (ROM → отдельное приложение)
//
// Каждый ROM из папки roms/ превращается в product flavor — самостоятельное
// приложение com.dendybox.app.<flavor> с ОДНИМ ROM внутри (assets/rom.nes).
// Имя flavor'а, заголовок приложения и ROM-файл задаются в roms/games.json:
//
//   {
//     "robocop3.nes": { "flavor": "robocop3", "title": "RoboCop 3" }
//   }
//
// Если games.json нет, flavor создаётся автоматически из имени файла
// («RoboCop 3.nes» → flavor robocop3). ROM'ы в git НЕ коммитятся (публичный
// репозиторий) — папка roms/ живёт только у вас, см. roms/README.md.
// ============================================================================

val romsDir = rootProject.file("roms")
val gamesJsonFile = romsDir.resolve("games.json")

data class GameSpec(val fileName: String, val file: File?, val flavor: String, val title: String)

fun sanitizeFlavorName(raw: String): String {
    val cleaned = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    // applicationId-сегмент не может начинаться с цифры и не может быть пустым
    return if (cleaned.isEmpty() || cleaned[0] !in 'a'..'z') "game$cleaned" else cleaned
}

val gameSpecs: List<GameSpec> = run {
    val overrides = linkedMapOf<String, Map<*, *>>()
    if (gamesJsonFile.exists()) {
        try {
            val parsed = JsonSlurper().parse(gamesJsonFile)
            if (parsed is Map<*, *>) {
                parsed.forEach { (k, v) -> if (v is Map<*, *>) overrides[k.toString()] = v }
            } else {
                throw GradleException("roms/games.json: ожидается объект { \"файл.nes\": {...} }")
            }
        } catch (e: GradleException) {
            throw e
        } catch (e: Exception) {
            throw GradleException("roms/games.json не читается: ${e.message}")
        }
    }
    val romExts = setOf("nes", "unf", "unif", "fds")
    val romFiles = romsDir.listFiles { f -> f.isFile && f.extension.lowercase() in romExts }
        ?.sortedBy { it.name } ?: emptyList()
    val names = (romFiles.map { it.name } + overrides.keys).distinct().sorted()
    val specs = names.map { name ->
        val o: Map<*, *> = overrides[name] ?: emptyMap<String, Any>()
        val flavorRaw = (o["flavor"] as? String) ?: File(name).nameWithoutExtension
        val title = (o["title"] as? String) ?: File(name).nameWithoutExtension
        GameSpec(name, romFiles.firstOrNull { it.name == name }, sanitizeFlavorName(flavorRaw), title)
    }
    val dup = specs.groupBy { it.flavor }.filterValues { it.size > 1 }.keys
    if (dup.isNotEmpty()) {
        throw GradleException("Конфликт имён flavor в roms/games.json: $dup — задайте уникальные flavor")
    }
    if (specs.isEmpty()) {
        println("DendyBox: в roms/ нет ROM-файлов и нет roms/games.json — product flavors не созданы")
    }
    specs
}

// ============================================================================
// Иконка варианта: metadata/<flavor>/icon.png -> adaptive-иконка лаунчера
// (генерирует scripts/make_icons.py) + metadata/<flavor>/icon512.png (RuStore).
// Задача подключается через addGeneratedSourceDirectory — AGP сам ставит её
// раньше всех задач, читающих ресурсы варианта.
// Иконка ОПЦИОНАЛЬНА: нет icon.png — собираемся со стандартной иконкой DendyBox.
// ============================================================================
abstract class PrepareIconsTask : DefaultTask() {
    @get:InputFile
    abstract val iconSrc: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val flavorName: Property<String>

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun generate() {
        execOps.exec {
            workingDir = projectRoot.get().asFile
            // Второй аргумент — res-каталог, куда положить иконку ЭТОГО варианта
            commandLine(
                "bash", "scripts/make_icons.sh", flavorName.get(),
                outputDir.get().asFile.absolutePath
            )
        }
    }
}

// ============================================================================
// Подпись релиза
//
// keystore/ НЕ хранится в git (репозиторий публичный). Один раз выполните:
//   ./scripts/make_keystore.sh
// — он создаст keystore/release.keystore + keystore/keystore.properties.
// ОБЯЗАТЕЛЬНО сделайте резервную копию папки keystore/: обновления приложения
// в магазине должны быть подписаны тем же ключом.
// Без keystore release будет подписан debug-ключом (только для локальных тестов).
// ============================================================================

val keystoreDir = rootProject.file("keystore")
val ksProps = Properties().apply {
    val f = keystoreDir.resolve("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val ksStoreFile = ksProps.getProperty("store.file")?.let { rootProject.file(it) }
val releaseSigningReady = ksStoreFile?.exists() == true &&
    ksProps.getProperty("store.password")?.isNotBlank() == true &&
    ksProps.getProperty("key.alias")?.isNotBlank() == true
val releaseSignConfigName = if (releaseSigningReady) "release" else "debug"

android {
    namespace = "com.dendybox.app"
    compileSdk = 35

    defaultConfig {
        // Базовый applicationId переопределяется каждым flavor'ом:
        // com.dendybox.app.<flavor>
        applicationId = "com.dendybox.app"
        minSdk = 26        // Android 8.0
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Локали ресурсов AndroidX: только русская (уменьшение размера APK)
        resourceConfigurations += listOf("ru")

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = ksStoreFile
                storePassword = ksProps.getProperty("store.password")
                keyAlias = ksProps.getProperty("key.alias")
                keyPassword = ksProps.getProperty("key.password")
                    ?: ksProps.getProperty("store.password")
            }
        }
    }

    buildTypes {
        debug {
            // x86_64 — для запуска на эмуляторе Android Studio
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Релиз: только реальные телефоны (ARM), без x86/x86_64 — экономия размера
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            signingConfig = signingConfigs.getByName(releaseSignConfigName)
        }
    }

    // ================= Игровые flavor'ы (по одному на ROM) =================
    flavorDimensions += listOf("game")
    productFlavors {
        gameSpecs.forEach { spec ->
            create(spec.flavor) {
                dimension = "game"
                applicationId = "com.dendybox.app.${spec.flavor}"
                resValue("string", "app_name", "DendyBox: ${spec.title}")
            }
        }
    }

    // ROM копируется из roms/ в assets варианта задачей prepare<Flavor>Rom (ниже)
    sourceSets {
        gameSpecs.forEach { spec ->
            getByName(spec.flavor) {
                assets.srcDir(layout.buildDirectory.dir("generated/rom/${spec.flavor}"))
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // .so ядра кладётся в jniLibs вручную/скриптом — пусть распаковывается как есть
        jniLibs {
            useLegacyPackaging = true
        }
    }
    lint {
        // Личный проект: lint не должен блокировать релизную сборку
        abortOnError = false
        checkReleaseBuilds = false
    }

    // Понятные имена файлов: DendyBox-robocop3-1.0-release.apk
    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "DendyBox-${flavorName}-${versionName}-${buildType.name}.apk"
        }
    }
}

// ================= Копирование выбранного ROM в assets варианта =================
// «Одна игра — один картридж»: в каждый APK попадает ровно один ROM (rom.nes).
gameSpecs.forEach { spec ->
    val cap = spec.flavor.replaceFirstChar { it.uppercaseChar() }
    val outDir = layout.buildDirectory.dir("generated/rom/${spec.flavor}")
    tasks.register("prepare${cap}Rom") {
        group = "dendybox"
        description = "Копирует roms/${spec.fileName} в assets варианта ${spec.flavor} (как rom.nes)"
        val src = spec.file
        if (src != null) inputs.file(src)
        outputs.file(outDir.map { it.file("rom.nes") })
        doLast {
            if (src == null || !src.exists()) {
                throw GradleException(
                    "ROM не найден: roms/${spec.fileName}\n" +
                    "Положите файл в папку roms/ под именем ${spec.fileName}\n" +
                    "(или поправьте roms/games.json) и повторите сборку."
                )
            }
            val dir = outDir.get().asFile
            dir.mkdirs()
            copy {
                from(src)
                rename { "rom.nes" }
                into(dir)
            }
        }
    }
    tasks.whenTaskAdded {
        if (name.startsWith("merge$cap") && name.endsWith("Assets")) {
            dependsOn("prepare${cap}Rom")
        }
    }
}

// ================= Генерация иконки варианта =================
// Регистрируется на КАЖДЫЙ VARIANT (robocop3Release/robocop3Debug): у каждого
// свой выходной каталог, AGP сам провязывает зависимости всех потребителей ресурсов.
androidComponents {
    onVariants { variant ->
        val flavor = variant.flavorName ?: return@onVariants
        val iconSrc = rootProject.file("metadata/$flavor/icon.png")
        if (!iconSrc.exists()) {
            println("DendyBox: metadata/$flavor/icon.png нет — собираем со стандартной иконкой DendyBox")
            return@onVariants
        }
        val cap = variant.name.replaceFirstChar { it.uppercaseChar() }
        val iconsTask = tasks.register("prepare${cap}Icons", PrepareIconsTask::class.java) {
            group = "dendybox"
            description = "Генерирует иконку варианта $flavor из metadata/$flavor/icon.png"
            this.iconSrc.set(iconSrc)
            this.flavorName.set(flavor)
            this.projectRoot.set(rootDir)
            // outputDir назначает сам AGP (addGeneratedSourceDirectory):
            // app/build/generated/res/prepare<Cap>Icons — скрипту путь передаётся аргументом
        }
        variant.sources.res?.addGeneratedSourceDirectory(iconsTask, PrepareIconsTask::outputDir)
    }
}

if (!releaseSigningReady) {
    println(
        "DendyBox: релизный keystore не найден — release будет подписан debug-ключом.\n" +
        "Для публикации в магазине выполните: ./scripts/make_keystore.sh"
    )
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
