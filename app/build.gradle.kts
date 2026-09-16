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
// Имя flavor'а, заголовок приложения, версия и ROM-файл задаются в
// roms/games.json:
//
// Кроме того, flavor может быть СБОРНИКОМ («X игр в 1»): вместо одного ROM
// в записи задаётся массив games — тогда в APK кладутся ВСЕ указанные ROM
// (assets/games/0.nes, 1.nes, …) + манифест assets/games/games.json, а при
// запуске приложение показывает меню выбора игры (см. ui/screens/CollectionMenu):
//
//   "kontra8in1.nes": {
//     "flavor": "kontra8in1", "title": "Контра: Сборник",
//     "games": [
//       { "file": "25_Contra.nes",       "title": "Контра" },
//       { "file": "24_Super_Contra.nes", "title": "Супер Контра" }
//     ]
//   }
//
// В сборник входят ROMы с ЛЮБЫМИ мапперами (ядро FCEUmm определяет сам),
// размер и количество ограничены только разумным размером APK.
//
// Фон меню сборника (постер): metadata/<flavor>/background.png|jpg|webp —
// кладёте вы, сборка копирует его в assets/games/background.* и пишет имя
// файла в манифест games.json (поле "background"); CollectionMenu рисует
// постер на весь экран за списком игр. Файл опционален: нет — чёрный фон.
//
// Обычная запись (одиночная игра) выглядит так:
//
//   {
//     "robocop3.nes": {
//       "flavor": "robocop3", "title": "RoboCop 3",
//       "versionName": "1.1", "versionCode": 2
//     }
//   }
//
// Версия (versionName/versionCode) — ПО ФЛАВОРАМ: обновление в магазине
// требует увеличить versionCode той игры, которую обновляете, остальные
// сборки продолжают собираться с версией по умолчанию (1.0 / 1).
// Удобно: ./scripts/bump_version.sh <flavor> <новая_версия>
//
// Если games.json нет, flavor создаётся автоматически из имени файла
// («RoboCop 3.nes» → flavor robocop3). ROM'ы в git НЕ коммитятся (публичный
// репозиторий) — папка roms/ живёт только у вас, см. roms/README.md.
// ============================================================================

val romsDir = rootProject.file("roms")
val gamesJsonFile = romsDir.resolve("games.json")

data class CollectionGame(val file: File, val title: String)

data class GameSpec(
    val fileName: String,
    val file: File?,                      // одиночный ROM (null у сборника)
    val flavor: String,
    val title: String,
    val versionCode: Int,
    val versionName: String,
    val collection: List<CollectionGame>? // != null — flavor-сборник «X in 1»
)

// Массив "games" в записи games.json — сборник. Каждый элемент: {"file":
// "имя.rom в roms/", "title": "Название в меню"} (title необязателен).
// Минимум 2 игры: для одной есть обычный flavor. Ошибки — GradleException,
// чтобы сборка упала сразу с понятным текстом, а не на старте приложения.
private fun parseCollection(o: Map<*, *>, name: String): List<CollectionGame> {
    val raw = o["games"] ?: return emptyList()
    if (raw !is List<*>) {
        throw GradleException("roms/games.json [$name]: games должен быть массивом [{\"file\": \"...\", \"title\": \"...\"}, ...]")
    }
    val romExts = setOf("nes", "unf", "unif", "fds")
    val games = raw.mapIndexed { i, item ->
        if (item !is Map<*, *>) {
            throw GradleException("roms/games.json [$name]: games[$i] должен быть объектом {\"file\": \"...\", \"title\": \"...\"}")
        }
        val f = (item["file"] as? String)?.trim()
        if (f.isNullOrEmpty()) {
            throw GradleException("roms/games.json [$name]: games[$i] — не задан file (имя ROM-файла в roms/)")
        }
        val src = romsDir.resolve(f)
        if (!src.exists()) {
            throw GradleException("roms/games.json [$name]: games[$i] — ROM не найден: roms/$f")
        }
        if (src.extension.lowercase() !in romExts) {
            throw GradleException("roms/games.json [$name]: games[$i] — ожидается ROM (.nes/.unf/.unif/.fds), получено: $f")
        }
        val t = (item["title"] as? String)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: src.nameWithoutExtension
        CollectionGame(src, t)
    }
    if (games.size < 2) {
        throw GradleException("roms/games.json [$name]: в сборнике games должно быть минимум 2 игры (для одной соберите обычный flavor)")
    }
    return games
}

fun sanitizeFlavorName(raw: String): String {
    val cleaned = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    // applicationId-сегмент не может начинаться с цифры и не может быть пустым
    return if (cleaned.isEmpty() || cleaned[0] !in 'a'..'z') "game$cleaned" else cleaned
}

// Версия flavor'а из его записи в games.json: { "versionName": "1.1",
// "versionCode": 2 }. Не указано — дефолт 1.0 / 1 (новые игры стартуют с 1).
// versionCode должен строго расти для обновлений в магазине (RuStore/Play),
// поэтому ошибки в этих полях — всегда GradleException, а не молчаливый дефолт.
private val DEFAULT_VERSION_NAME = "1.0"
private val DEFAULT_VERSION_CODE = 1

private fun parseVersionName(o: Map<*, *>, flavor: String): String {
    val raw = o["versionName"] ?: return DEFAULT_VERSION_NAME
    if (raw !is String || raw.isBlank()) {
        throw GradleException("roms/games.json [$flavor]: versionName должен быть непустой строкой, получено: $raw")
    }
    if (raw.length > 32 || !raw.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
        throw GradleException("roms/games.json [$flavor]: versionName \"$raw\" — допустимы латиница/цифры/./_/- (начало с буквы или цифры, до 32 символов)")
    }
    return raw
}

private fun parseVersionCode(o: Map<*, *>, flavor: String): Int {
    val raw = o["versionCode"] ?: return DEFAULT_VERSION_CODE
    if (raw !is Number) {
        throw GradleException("roms/games.json [$flavor]: versionCode должен быть целым числом, получено: $raw")
    }
    if (raw.toDouble().rem(1.0) != 0.0) {
        throw GradleException("roms/games.json [$flavor]: versionCode должен быть целым числом, получено: $raw")
    }
    val code = raw.toInt()
    if (code < 1 || code > 2_100_000_000) {
        throw GradleException("roms/games.json [$flavor]: versionCode $code вне допустимого диапазона 1..2100000000")
    }
    return code
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
        val flavor = sanitizeFlavorName(flavorRaw)
        val collection = parseCollection(o, name)
        val romFile = romFiles.firstOrNull { it.name == name }
        if (collection.isNotEmpty() && romFile != null) {
            println("DendyBox: [$name] задан массив games — это сборник, отдельный ROM-файл с тем же именем игнорируется")
        }
        GameSpec(
            name, if (collection.isEmpty()) romFile else null, flavor, title,
            parseVersionCode(o, name), parseVersionName(o, name),
            if (collection.isEmpty()) null else collection
        )
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

// Резолв версий печатается при каждой конфигурации — сразу видно, какая
// версия уйдёт в APK (проверка перед выпуском обновления в магазин).
if (gameSpecs.isNotEmpty()) {
    println(
        "DendyBox: версии — " + gameSpecs.joinToString(", ") {
            "${it.flavor} ${it.versionName} (${it.versionCode})"
        }
    )
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
                // Версия из games.json (версия по flavor'ам; см. шапку файла)
                versionCode = spec.versionCode
                versionName = spec.versionName
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

    // Понятные имена файлов: DendyBox-robocop3-1.1-release.apk (версия из games.json)
    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "DendyBox-${flavorName}-${versionName}-${buildType.name}.apk"
        }
    }
}

// Фон меню сборника: первый найденный metadata/<flavor>/background.*
// (приоритет png → jpg → jpeg → webp; расширение сохраняется при копировании).
private fun backgroundSource(flavor: String): File? =
    listOf("png", "jpg", "jpeg", "webp")
        .map { rootProject.file("metadata/$flavor/background.$it") }
        .firstOrNull { it.exists() }

// ================= Копирование ROM(ов) в assets варианта =================
// «Одна игра — один картридж»: в каждый APK попадает ровно один ROM (rom.nes)
// либо СБОРНИК: все ROMы списка games → assets/games/0.nes, 1.nes, …
// + манифест assets/games/games.json (его читает CollectionMenu).
// Опционально копируется и конфиг игры roms/<flavor>.json → assets/game.json
// (сейчас — байт урона для вибро-отклика; см. roms/README.md).
gameSpecs.forEach { spec ->
    val cap = spec.flavor.replaceFirstChar { it.uppercaseChar() }
    val outDir = layout.buildDirectory.dir("generated/rom/${spec.flavor}")
    val cfgSrc = romsDir.resolve("${spec.flavor}.json")
    val bgSrc = backgroundSource(spec.flavor)
    tasks.register("prepare${cap}Rom") {
        group = "dendybox"
        description = if (spec.collection != null) {
            "Копирует ${spec.collection.size} ROM(ов) сборника в assets/games/ варианта ${spec.flavor}"
        } else {
            "Копирует roms/${spec.fileName} в assets варианта ${spec.flavor} (как rom.nes)"
        }
        if (spec.collection != null) {
            spec.collection.forEach { inputs.file(it.file) }
            if (bgSrc != null) {
                inputs.file(bgSrc)
                outputs.file(outDir.map { it.file("games/background.${bgSrc.extension.lowercase()}") })
            }
            outputs.dir(outDir.map { it.dir("games") })
        } else {
            val src = spec.file
            if (src != null) inputs.file(src)
            outputs.file(outDir.map { it.file("rom.nes") })
            if (bgSrc != null) {
                println(
                    "DendyBox: [${spec.flavor}] metadata/${spec.flavor}/background.* — " +
                    "фон меню бывает только у сборников, файл игнорируется"
                )
            }
        }
        if (cfgSrc.exists()) inputs.file(cfgSrc)
        if (cfgSrc.exists()) outputs.file(outDir.map { it.file("game.json") })
        doLast {
            val dir = outDir.get().asFile
            dir.mkdirs()
            if (spec.collection != null) {
                val gamesDir = dir.resolve("games")
                gamesDir.mkdirs()
                // Фон меню: убрать устаревший (удалили/сменили расширение)
                // и скопировать текущий; имя файла уходит в манифест
                gamesDir.listFiles { f -> f.name.startsWith("background.") }?.forEach { it.delete() }
                val bgName = bgSrc?.let { "background.${it.extension.lowercase()}" }
                if (bgSrc != null && bgName != null) {
                    bgSrc.copyTo(gamesDir.resolve(bgName), overwrite = true)
                    println(
                        "DendyBox: [${spec.flavor}] фон меню сборника: metadata/${spec.flavor}/$bgName " +
                        "(${bgSrc.length() / 1024} КиБ)"
                    )
                    if (bgSrc.length() > 12L * 1024 * 1024) {
                        println("DendyBox: [${spec.flavor}] постер больше 12 МБ — проверьте размер APK")
                    }
                }
                val esc: (String) -> String = { s ->
                    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", " ")
                }
                val manifest = StringBuilder("{")
                if (!spec.title.isNullOrBlank()) manifest.append("\"title\":\"${esc(spec.title)}\",")
                if (bgName != null) manifest.append("\"background\":\"$bgName\",")
                manifest.append("\"games\":[")
                spec.collection.forEachIndexed { i, g ->
                    val dst = gamesDir.resolve("$i.${g.file.extension.lowercase()}")
                    g.file.copyTo(dst, overwrite = true)
                    if (i > 0) manifest.append(",")
                    manifest.append("{\"file\":\"${dst.name}\",\"title\":\"${esc(g.title)}\"}")
                }
                manifest.append("]}")
                dir.resolve("games/games.json").writeText(manifest.toString())
            } else {
                val src = spec.file
                if (src == null || !src.exists()) {
                    throw GradleException(
                        "ROM не найден: roms/${spec.fileName}\n" +
                        "Положите файл в папку roms/ под именем ${spec.fileName}\n" +
                        "(или поправьте roms/games.json) и повторите сборку."
                    )
                }
                copy {
                    from(src)
                    rename { "rom.nes" }
                    into(dir)
                }
            }
            if (cfgSrc.exists()) {
                copy {
                    from(cfgSrc)
                    rename { "game.json" }
                    into(dir)
                }
            } else {
                // конфиг могли убрать — не оставляем устаревший в сборке
                dir.resolve("game.json").delete()
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
