// nesoid.cpp — минимальный фронтенд libretro для NES + JNI-мост.
//
// Архитектура:
//   Kotlin (EmulatorEngine) -> JNI -> этот файл -> ядро FCEUmm (libcore_nes.so, dlopen)
//
// Потокобезопасность:
//   * все retro_* вызовы идут из одного потока эмуляции;
//   * setInput/setCheats приходят из UI-потока -> std::atomic / std::mutex.

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "libretro.h"

// ---------------------------------------------------------------------------
// Указатели на функции ядра
// ---------------------------------------------------------------------------

static void* g_core = nullptr; // handle dlopen
static std::string g_rom_path = "rom.nes"; // имя ROM: ядро по нему определяет регион и расширение

static void (*f_set_environment)(retro_environment_t) = nullptr;
static void (*f_set_video_refresh)(retro_video_refresh_t) = nullptr;
static void (*f_set_audio_sample)(int16_t (*)(int16_t, int16_t)) = nullptr;
static void (*f_set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
static void (*f_set_input_poll)(retro_input_poll_t) = nullptr;
static void (*f_set_input_state)(retro_input_state_t) = nullptr;
static void (*f_init)(void) = nullptr;
static void (*f_deinit)(void) = nullptr;
static unsigned (*f_api_version)(void) = nullptr;
static void (*f_get_system_info)(retro_system_info*) = nullptr;
static void (*f_get_system_av_info)(retro_system_av_info*) = nullptr;
static void (*f_set_controller_port_device)(unsigned, unsigned) = nullptr;
static void (*f_reset)(void) = nullptr;
static void (*f_run)(void) = nullptr;
static bool (*f_load_game)(const retro_game_info*) = nullptr;
static void (*f_unload_game)(void) = nullptr;
static size_t (*f_serialize_size)(void) = nullptr;
static bool (*f_serialize)(void*, size_t) = nullptr;
static bool (*f_unserialize)(const void*, size_t) = nullptr;
static void* (*f_get_memory_data)(unsigned) = nullptr;
static size_t (*f_get_memory_size)(unsigned) = nullptr;
static void (*f_cheat_reset)(void) = nullptr;
static void (*f_cheat_set)(unsigned, bool, const char*) = nullptr;

// ---------------------------------------------------------------------------
// Состояние
// ---------------------------------------------------------------------------

static std::vector<uint8_t> g_rom;          // данные ROM (живут всё время сессии)
static std::string g_sys_dir;               // system directory
static std::string g_save_dir;              // save directory

static retro_system_av_info g_av{};         // fps / sample_rate / геометрия
static bool g_loaded = false;

// Кадровый буфер: прямой ByteBuffer из Kotlin (RGB565)
static uint16_t* g_pix = nullptr;
static size_t g_pix_bytes = 0;
static unsigned g_w = 256;
static unsigned g_h = 240;

// Ввод: биты соответствуют RETRO_DEVICE_ID_JOYPAD_* (см. InputState.kt)
static std::atomic<uint32_t> g_input[2] = {{0}, {0}};

// Звуковой кольцевой буфер (int16, стерео-чередование)
static std::vector<int16_t> g_audio;
static std::mutex g_audio_mtx;
static const size_t AUDIO_CAP = 48000 * 2; // ~1 сек стерео, защита от переполнения

// Читы: тройки [addr, value, cmp]; cmp = -1 -> сравнения нет
static std::vector<int32_t> g_cheats;
static std::mutex g_cheats_mtx;

// Указатель на RAM консоли (2 КБ) — для патчей памяти
static uint8_t* g_ram = nullptr;
static size_t g_ram_size = 0;

// ---------------------------------------------------------------------------
// Колбэки для ядра
// ---------------------------------------------------------------------------

// Лог ядра -> logcat (тег DendyBox): сюда попадают и ошибки загрузки ROM
// от самого FCEUmm («Not an iNES file!», «mapper is not supported» и т.п.).
static void core_log(enum retro_log_level level, const char* fmt, ...) {
    static const android_LogPriority prio[] = {
        ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR
    };
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    __android_log_print(level < 4 ? prio[level] : ANDROID_LOG_INFO,
                        "DendyBox", "%s", line);
}

// Расширенная инфа о контенте для ядра. Ночные сборки FCEUmm берут ROM
// ТОЛЬКО отсюда (fallback по retro_game_info::data в ядре потерян —
// он пытается открыть файл с диска и отвергает ROM из памяти).
// Вызывается только внутри retro_load_game, g_rom уже заполнен.
static retro_game_info_ext g_game_info_ext;

static bool env_cb(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *reinterpret_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *reinterpret_cast<const char**>(data) = g_sys_dir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *reinterpret_cast<const char**>(data) = g_save_dir.c_str();
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            // Держим RGB565: Bitmap.Config.RGB_565 в Android ждёт ровно этот формат.
            // Отказываемся от XRGB8888 -> ядро останется на RGB565 (значение по умолчанию).
            const unsigned fmt = *reinterpret_cast<unsigned*>(data);
            return fmt == RETRO_PIXEL_FORMAT_RGB565;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            const auto* g = reinterpret_cast<const retro_game_geometry*>(data);
            if (g) { g_w = g->base_width; g_h = g->base_height; }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            const auto* a = reinterpret_cast<const retro_system_av_info*>(data);
            if (a) { g_av = *a; g_w = a->geometry.base_width; g_h = a->geometry.base_height; }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            // Не задаём core options — ядро возьмёт значения по умолчанию.
            auto* v = reinterpret_cast<retro_variable*>(data);
            if (v) v->value = nullptr;
            return false;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            auto* cb = reinterpret_cast<retro_log_callback*>(data);
            if (!cb) return false;
            cb->log = &core_log;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_GAME_INFO_EXT: {
            auto* out = reinterpret_cast<const retro_game_info_ext**>(data);
            if (!out || g_rom.empty()) return false;
            g_game_info_ext = retro_game_info_ext{};
            g_game_info_ext.full_path = g_rom_path.c_str();
            g_game_info_ext.dir = ".";
            g_game_info_ext.name = "rom";
            g_game_info_ext.ext = "nes";
            g_game_info_ext.data = g_rom.data();
            g_game_info_ext.size = g_rom.size();
            g_game_info_ext.file_in_archive = false;
            g_game_info_ext.persistent_data = false;
            *out = &g_game_info_ext;
            return true;
        }
        default:
            return false;
    }
}

static void video_refresh_cb(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (data == nullptr || g_pix == nullptr) return; // DUPE-кадр
    if (g_pix_bytes < static_cast<size_t>(g_w) * g_h * 2) return;
    const auto* src = static_cast<const uint8_t*>(data);
    const unsigned w = std::min(width, g_w);
    const unsigned h = std::min(height, g_h);
    for (unsigned y = 0; y < h; ++y) {
        std::memcpy(g_pix + static_cast<size_t>(y) * g_w,
                    src + static_cast<size_t>(y) * pitch,
                    static_cast<size_t>(w) * 2);
    }
}

static size_t audio_batch_cb(const int16_t* data, size_t frames) {
    if (data == nullptr || frames == 0) return 0;
    std::lock_guard<std::mutex> lock(g_audio_mtx);
    const size_t samples = frames * 2;
    if (g_audio.size() + samples > AUDIO_CAP) {
        g_audio.erase(g_audio.begin(),
                      g_audio.begin() + static_cast<long>(std::min(g_audio.size(), samples)));
    }
    g_audio.insert(g_audio.end(), data, data + samples);
    return frames;
}

static void input_poll_cb() {
    // не требуется
}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index; // индекс аналоговой оси не используется (только цифровой joypad)
    if (device != RETRO_DEVICE_JOYPAD || port > 1 || id > 15) return 0;
    return static_cast<int16_t>((g_input[port].load(std::memory_order_relaxed) >> id) & 1u);
}

// ---------------------------------------------------------------------------
// Вспомогательное
// ---------------------------------------------------------------------------

static void apply_cheats() {
    if (g_ram == nullptr || g_ram_size == 0) return;
    std::lock_guard<std::mutex> lock(g_cheats_mtx);
    for (size_t i = 0; i + 2 < g_cheats.size(); i += 3) {
        const int32_t addr = g_cheats[i];
        const int32_t val = g_cheats[i + 1];
        const int32_t cmp = g_cheats[i + 2];
        // RAM NES — 2 КБ с зеркалированием до $1FFF, как в FCEUX
        const uint32_t a = static_cast<uint32_t>(addr) & 0x7FFu;
        if (a >= g_ram_size) continue;
        if (cmp >= 0 && g_ram[a] != static_cast<uint8_t>(cmp)) continue;
        g_ram[a] = static_cast<uint8_t>(val);
    }
}

template <typename T>
static bool sym(const char* name, T& out) {
    void* p = dlsym(g_core, name);
    if (p == nullptr) return false;
    out = reinterpret_cast<T>(p);
    return true;
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_dendybox_app_Native_loadCore(JNIEnv* env, jobject /*thiz*/,
                                      jstring jpath, jstring jsys, jstring jsave) {
    if (g_core != nullptr) return JNI_TRUE; // уже загружено

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    const char* sys = env->GetStringUTFChars(jsys, nullptr);
    const char* save = env->GetStringUTFChars(jsave, nullptr);
    g_sys_dir = sys ? sys : "/data/local";
    g_save_dir = save ? save : g_sys_dir;

    g_core = path ? dlopen(path, RTLD_NOW | RTLD_LOCAL) : nullptr;

    if (path) env->ReleaseStringUTFChars(jpath, path);
    if (sys) env->ReleaseStringUTFChars(jsys, sys);
    if (save) env->ReleaseStringUTFChars(jsave, save);
    if (g_core == nullptr) return JNI_FALSE;

    bool ok = true;
    ok &= sym("retro_set_environment", f_set_environment);
    ok &= sym("retro_set_video_refresh", f_set_video_refresh);
    ok &= sym("retro_set_audio_sample", f_set_audio_sample);
    ok &= sym("retro_set_audio_sample_batch", f_set_audio_sample_batch);
    ok &= sym("retro_set_input_poll", f_set_input_poll);
    ok &= sym("retro_set_input_state", f_set_input_state);
    ok &= sym("retro_init", f_init);
    ok &= sym("retro_deinit", f_deinit);
    ok &= sym("retro_api_version", f_api_version);
    ok &= sym("retro_get_system_info", f_get_system_info);
    ok &= sym("retro_get_system_av_info", f_get_system_av_info);
    ok &= sym("retro_set_controller_port_device", f_set_controller_port_device);
    ok &= sym("retro_reset", f_reset);
    ok &= sym("retro_run", f_run);
    ok &= sym("retro_load_game", f_load_game);
    ok &= sym("retro_unload_game", f_unload_game);
    ok &= sym("retro_serialize_size", f_serialize_size);
    ok &= sym("retro_serialize", f_serialize);
    ok &= sym("retro_unserialize", f_unserialize);
    ok &= sym("retro_get_memory_data", f_get_memory_data);
    ok &= sym("retro_get_memory_size", f_get_memory_size);
    // Cheat API не обязателен (нет в старых ядрах) — просто пробуем
    sym("retro_cheat_reset", f_cheat_reset);
    sym("retro_cheat_set", f_cheat_set);
    if (!ok) { dlclose(g_core); g_core = nullptr; return JNI_FALSE; }

    f_set_environment(&env_cb);
    f_set_video_refresh(&video_refresh_cb);
    f_set_audio_sample(nullptr);
    f_set_audio_sample_batch(&audio_batch_cb);
    f_set_input_poll(&input_poll_cb);
    f_set_input_state(&input_state_cb);
    f_init();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_dendybox_app_Native_loadRom(JNIEnv* env, jobject /*thiz*/, jbyteArray jrom) {
    if (g_core == nullptr) return JNI_FALSE;

    const jsize len = env->GetArrayLength(jrom);
    auto* elems = env->GetByteArrayElements(jrom, nullptr);
    g_rom.assign(reinterpret_cast<const uint8_t*>(elems),
                 reinterpret_cast<const uint8_t*>(elems) + len);
    env->ReleaseByteArrayElements(jrom, elems, JNI_ABORT);

    retro_game_info info{};
    info.path = g_rom_path.c_str(); // расширение нужно ядру для определения маппера
    info.data = g_rom.data();
    info.size = g_rom.size();
    info.meta = nullptr;

    g_loaded = f_load_game(&info);
    if (!g_loaded) return JNI_FALSE;

    f_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    f_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);

    f_get_system_av_info(&g_av);
    g_w = g_av.geometry.base_width ? g_av.geometry.base_width : 256;
    g_h = g_av.geometry.base_height ? g_av.geometry.base_height : 240;

    g_ram = static_cast<uint8_t*>(f_get_memory_data(RETRO_MEMORY_SYSTEM_RAM));
    g_ram_size = f_get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
    if (g_ram_size < 0x800) { g_ram = nullptr; g_ram_size = 0; }

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_dendybox_app_Native_runFrame(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_loaded) return JNI_FALSE;
    apply_cheats();
    f_run();
    return JNI_TRUE;
}

// Кнопка RESET консоли: ядро перезапускает игру с нуля (retro_reset).
// Вызывается только из потока эмуляции, когда кадры не идут (пауза).
JNIEXPORT jboolean JNICALL
Java_com_dendybox_app_Native_reset(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_loaded) return JNI_FALSE;
    f_reset();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_dendybox_app_Native_setInput(JNIEnv* /*env*/, jobject /*thiz*/,
                                      jint p1, jint p2) {
    g_input[0].store(static_cast<uint32_t>(p1), std::memory_order_relaxed);
    g_input[1].store(static_cast<uint32_t>(p2), std::memory_order_relaxed);
}

// FNV-1a (32 бита) по системной RAM консоли — контроль рассинхрона в netplay.
// Обе стороны считают его по одинаковому состоянию; расхождение = десинк.
JNIEXPORT jint JNICALL
Java_com_dendybox_app_Native_ramCrc(JNIEnv* /*env*/, jobject /*thiz*/) {
    uint32_t h = 2166136261u;
    if (g_ram != nullptr && g_ram_size > 0) {
        for (size_t i = 0; i < g_ram_size; ++i) {
            h ^= static_cast<uint32_t>(g_ram[i]);
            h *= 16777619u;
        }
    }
    return static_cast<jint>(h);
}

JNIEXPORT void JNICALL
Java_com_dendybox_app_Native_setPixelBuffer(JNIEnv* env, jobject /*thiz*/, jobject buf) {
    g_pix = static_cast<uint16_t*>(env->GetDirectBufferAddress(buf));
    const jlong cap = env->GetDirectBufferCapacity(buf);
    g_pix_bytes = cap > 0 ? static_cast<size_t>(cap) : 0;
}

JNIEXPORT jint JNICALL
Java_com_dendybox_app_Native_drainAudio(JNIEnv* env, jobject /*thiz*/, jobject buf) {
    auto* dst = static_cast<int16_t*>(env->GetDirectBufferAddress(buf));
    const jlong capBytes = env->GetDirectBufferCapacity(buf);
    if (dst == nullptr || capBytes <= 0) return 0;
    const size_t maxShorts = static_cast<size_t>(capBytes) / 2;

    std::lock_guard<std::mutex> lock(g_audio_mtx);
    const size_t n = std::min(g_audio.size(), maxShorts);
    if (n > 0) {
        std::memcpy(dst, g_audio.data(), n * sizeof(int16_t));
        g_audio.erase(g_audio.begin(), g_audio.begin() + static_cast<long>(n));
    }
    return static_cast<jint>(n);
}

JNIEXPORT void JNICALL
Java_com_dendybox_app_Native_clearAudio(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_audio_mtx);
    g_audio.clear();
}

JNIEXPORT jint JNICALL
Java_com_dendybox_app_Native_audioLevel(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_audio_mtx);
    return static_cast<jint>(g_audio.size());
}

JNIEXPORT jdouble JNICALL
Java_com_dendybox_app_Native_avFps(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_av.timing.fps > 20.0 ? g_av.timing.fps : 60.0988;
}

JNIEXPORT jdouble JNICALL
Java_com_dendybox_app_Native_avSampleRate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_av.timing.sample_rate > 1000.0 ? g_av.timing.sample_rate : 48000.0;
}

JNIEXPORT jintArray JNICALL
Java_com_dendybox_app_Native_videoDims(JNIEnv* env, jobject /*thiz*/) {
    jint arr[2] = {static_cast<jint>(g_w), static_cast<jint>(g_h)};
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, arr);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_dendybox_app_Native_stateSize(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_loaded ? static_cast<jint>(f_serialize_size()) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_dendybox_app_Native_saveState(JNIEnv* env, jobject /*thiz*/, jobject buf) {
    if (!g_loaded) return JNI_FALSE;
    auto* p = static_cast<void*>(env->GetDirectBufferAddress(buf));
    const jlong cap = env->GetDirectBufferCapacity(buf);
    if (p == nullptr || cap <= 0) return JNI_FALSE;
    return f_serialize(p, static_cast<size_t>(cap)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_dendybox_app_Native_loadState(JNIEnv* env, jobject /*thiz*/, jobject buf) {
    if (!g_loaded) return JNI_FALSE;
    auto* p = static_cast<const void*>(env->GetDirectBufferAddress(buf));
    const jlong cap = env->GetDirectBufferCapacity(buf);
    if (p == nullptr || cap <= 0) return JNI_FALSE;
    return f_unserialize(p, static_cast<size_t>(cap)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_dendybox_app_Native_setCheats(JNIEnv* env, jobject /*thiz*/, jintArray data) {
    const jsize len = env->GetArrayLength(data);
    auto* elems = env->GetIntArrayElements(data, nullptr);
    std::lock_guard<std::mutex> lock(g_cheats_mtx);
    g_cheats.assign(elems, elems + len);
    env->ReleaseIntArrayElements(data, elems, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_dendybox_app_Native_setGgCheats(JNIEnv* env, jobject /*thiz*/, jobjectArray codes) {
    if (f_cheat_reset == nullptr || f_cheat_set == nullptr) return;
    f_cheat_reset();
    const jsize n = env->GetArrayLength(codes);
    for (jsize i = 0; i < n; ++i) {
        auto* js = static_cast<jstring>(env->GetObjectArrayElement(codes, i));
        if (js == nullptr) continue;
        const char* s = env->GetStringUTFChars(js, nullptr);
        f_cheat_set(static_cast<unsigned>(i), true, s);
        env->ReleaseStringUTFChars(js, s);
    }
}

JNIEXPORT void JNICALL
Java_com_dendybox_app_Native_unload(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_core == nullptr) return;
    if (g_loaded) { f_unload_game(); g_loaded = false; }
    f_deinit();
    dlclose(g_core);
    g_core = nullptr;
    g_ram = nullptr;
    g_ram_size = 0;
    g_pix = nullptr;
    g_pix_bytes = 0;
    g_w = 256; g_h = 240;
    g_rom.clear();
    { std::lock_guard<std::mutex> l(g_audio_mtx); g_audio.clear(); }
    { std::lock_guard<std::mutex> l(g_cheats_mtx); g_cheats.clear(); }
}

} // extern "C"
