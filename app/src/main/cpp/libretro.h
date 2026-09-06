// libretro.h — минимальное подмножество официального заголовка libretro.h
// (https://github.com/libretro/libretro-common/blob/master/include/libretro.h),
// содержащее только то, что использует фронтенд nesoid.cpp.
// Смысл констант сверять с официальным заголовком при обновлении.
#ifndef LIBRETRO_H
#define LIBRETRO_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* --- RETRO_ENVIRONMENT (cmd для retro_set_environment колбэка) --- */
#define RETRO_ENVIRONMENT_GET_CAN_DUPE          3
#define RETRO_ENVIRONMENT_SET_MESSAGE            6
#define RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL  8
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY   9
#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT      10
#define RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS 11
#define RETRO_ENVIRONMENT_GET_VARIABLE          15
#define RETRO_ENVIRONMENT_SET_VARIABLES         16
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE     27
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY    31
#define RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO    32
#define RETRO_ENVIRONMENT_SET_GEOMETRY          37
#define RETRO_ENVIRONMENT_SHUTDOWN               7
#define RETRO_ENVIRONMENT_GET_GAME_INFO_EXT     66

/* --- Форматы пикселей --- */
#define RETRO_PIXEL_FORMAT_0RGB1555 0
#define RETRO_PIXEL_FORMAT_RGB565   1
#define RETRO_PIXEL_FORMAT_XRGB8888 2

/* --- Память консоли (значения ОФИЦИАЛЬНЫЕ — сверено с libretro.h!) --- */
#define RETRO_MEMORY_SAVE_RAM    0
#define RETRO_MEMORY_RTC         1
#define RETRO_MEMORY_SYSTEM_RAM  2
#define RETRO_MEMORY_VIDEO_RAM   3

/* --- Устройства ввода --- */
#define RETRO_DEVICE_NONE   0
#define RETRO_DEVICE_JOYPAD 1

#define RETRO_DEVICE_ID_JOYPAD_B      0
#define RETRO_DEVICE_ID_JOYPAD_Y      1
#define RETRO_DEVICE_ID_JOYPAD_SELECT 2
#define RETRO_DEVICE_ID_JOYPAD_START  3
#define RETRO_DEVICE_ID_JOYPAD_UP     4
#define RETRO_DEVICE_ID_JOYPAD_DOWN   5
#define RETRO_DEVICE_ID_JOYPAD_LEFT   6
#define RETRO_DEVICE_ID_JOYPAD_RIGHT  7
#define RETRO_DEVICE_ID_JOYPAD_A      8
#define RETRO_DEVICE_ID_JOYPAD_X      9
#define RETRO_DEVICE_ID_JOYPAD_L     10
#define RETRO_DEVICE_ID_JOYPAD_R     11

typedef struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float aspect_ratio;
} retro_game_geometry;

typedef struct retro_system_timing {
    double fps;
    double sample_rate;
} retro_system_timing;

typedef struct retro_system_av_info {
    retro_game_geometry geometry;
    retro_system_timing timing;
} retro_system_av_info;

typedef struct retro_variable {
    const char *key;
    const char *value;
} retro_variable;

typedef struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
} retro_game_info;

/* Расширенная информация о контенте (RETRO_ENVIRONMENT_GET_GAME_INFO_EXT).
 * Ночные сборки FCEUmm берут ROM ИСКЛЮЧИТЕЛЬНО отсюда: их fallback-ветка
 * по retro_game_info::data потеряна — без этой структуры ядро пытается
 * открыть файл с диска и отказывается грузить ROM из памяти.
 * Порядок полей сверять с официальным libretro.h! */
typedef struct retro_game_info_ext {
    const char *full_path;      /* путь; если !file_in_archive — обязан быть валидным */
    const char *archive_path;   /* NULL, если файл не внутри архива */
    const char *archive_file;
    const char *dir;
    const char *name;           /* базовое имя без расширения */
    const char *ext;            /* расширение в нижнем регистре */
    const char *meta;
    const void *data;           /* буфер с содержимым ROM */
    size_t size;
    bool file_in_archive;
    bool persistent_data;
} retro_game_info_ext;

/* Лог ядра (RETRO_ENVIRONMENT_GET_LOG_INTERFACE) */
typedef enum retro_log_level {
    RETRO_LOG_DEBUG = 0,
    RETRO_LOG_INFO,
    RETRO_LOG_WARN,
    RETRO_LOG_ERROR,
    RETRO_LOG_DUMMY = 0x7FFF
} retro_log_level;

typedef void (*retro_log_printf_t)(enum retro_log_level level, const char *fmt, ...);

typedef struct retro_log_callback {
    retro_log_printf_t log;
} retro_log_callback;

typedef struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool need_fullpath;
    bool block_extract;
} retro_system_info;

typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_video_refresh_t)(const void *data, unsigned width,
                                      unsigned height, size_t pitch);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data,
                                             size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device,
                                       unsigned index, unsigned id);

/* --- API ядра --- */
void retro_set_environment(retro_environment_t);
void retro_set_video_refresh(retro_video_refresh_t);
void retro_set_audio_sample(int16_t (*cb)(int16_t left, int16_t right));
void retro_set_audio_sample_batch(retro_audio_sample_batch_t);
void retro_set_input_poll(retro_input_poll_t);
void retro_set_input_state(retro_input_state_t);

void retro_init(void);
void retro_deinit(void);
unsigned retro_api_version(void);
void retro_get_system_info(retro_system_info *info);
void retro_get_system_av_info(retro_system_av_info *info);
void retro_set_controller_port_device(unsigned port, unsigned device);
void retro_reset(void);
void retro_run(void);
bool retro_load_game(const retro_game_info *game);
void retro_unload_game(void);
unsigned retro_get_region(void);
size_t retro_serialize_size(void);
bool retro_serialize(void *data, size_t size);
bool retro_unserialize(const void *data, size_t size);
void retro_cheat_reset(void);
void retro_cheat_set(unsigned index, bool enabled, const char *code);
size_t retro_get_memory_size(unsigned id);
void *retro_get_memory_data(unsigned id);

#ifdef __cplusplus
}
#endif

#endif /* LIBRETRO_H */
