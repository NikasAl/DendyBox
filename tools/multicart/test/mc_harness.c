// mc_harness — libretro-хост для тестирования мультикарта DendyBox:
//   * скриптовое управление джойстиком (--press КАДР:кнопки)
//   * статистика кадра (средний RGB565 + hash) в заданных кадрах (--probe)
//   * дамп кадра в PPM (--ppm КАДР:файл)
//   * мягкий RESET ядра в заданном кадре (--reset КАДР)
//
// Использование:
//   mc_harness <rom.nes> <core.so> --frames 400 --press 100:down \
//       --press 112:none --press 120:start --press 132:none \
//       --probe 90,200,350 --reset 240 --ppm 90:/tmp/menu.ppm
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdbool.h>
#include "libretro.h"

#define MAX_PRESS 64
#define MAX_PROBE 64
#define MAX_PPM   16

struct Press { int frame; unsigned mask; };
struct Probe { int frame; int done; };
struct PpmDump { int frame; const char* path; int done; };

static uint8_t* rom_data = NULL;
static size_t rom_size = 0;
static char rom_path[512] = "rom.nes";

static struct Press presses[MAX_PRESS];
static int n_presses = 0;
static struct Probe probes[MAX_PROBE];
static int n_probes = 0;
static struct PpmDump ppms[MAX_PPM];
static int n_ppms = 0;
static int reset_frame = -1;
static int total_frames = 120;

static uint8_t* fb = NULL;          // сырой кадр (по строкам, pitch)
static size_t fb_cap = 0, fb_pitch = 0;
static unsigned fb_w = 0, fb_h = 0;
static unsigned fb_bpp = 0;         // 2 (RGB565) или 4 (XRGB8888)

static void core_log(enum retro_log_level level, const char* fmt, ...) {
    const char* tag[] = {"DBG", "INF", "WRN", "ERR"};
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[core %s] ", tag[level < 4 ? level : 1]);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
}

static bool env_cb(unsigned cmd, void* data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *(bool*)data = true; return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char**)data = "/tmp/fceumm_sys"; return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char**)data = "/tmp/fceumm_sav"; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        unsigned fmt = *(unsigned*)data;
        return fmt == RETRO_PIXEL_FORMAT_RGB565;
    }
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
        struct retro_log_callback* cb = (struct retro_log_callback*)data;
        cb->log = &core_log; return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable* v = (struct retro_variable*)data;
        if (v) v->value = NULL;
        return false;
    }
    default: return false;
    }
}

static unsigned current_mask = 0;

static int16_t input_cb(unsigned port, unsigned dev, unsigned idx, unsigned id) {
    (void)idx;
    if (port != 0 || dev != RETRO_DEVICE_JOYPAD) return 0;
    if (id >= 16) return 0;
    return (current_mask >> id) & 1;
}

static void video_cb(const void* data, unsigned w, unsigned h, size_t pitch) {
    if (!data) return;
    static int first = 1;
    fb_bpp = (unsigned)(pitch / (w ? w : 1));
    if (first) {
        first = 0;
        printf("VIDEO: %ux%u pitch=%zu bpp=%u\n", w, h, pitch, fb_bpp);
    }
    if (pitch * h > fb_cap) {
        fb = realloc(fb, pitch * h);
        fb_cap = pitch * h;
    }
    fb_w = w; fb_h = h; fb_pitch = pitch;
    memcpy(fb, data, pitch * h);
}

// Получить пиксель как (r,g,b) 0..255 с учётом формата кадра
static void get_px(unsigned x, unsigned y, uint8_t* r, uint8_t* g, uint8_t* b) {
    const uint8_t* row = fb + (size_t)y * fb_pitch;
    if (fb_bpp == 4) {
        const uint8_t* p = row + x * 4;   // XRGB8888 little-endian: B G R X
        *b = p[0]; *g = p[1]; *r = p[2];
    } else {
        uint16_t p = row[x * 2] | (row[x * 2 + 1] << 8);   // RGB565
        *r = ((p >> 11) & 31) * 255 / 31;
        *g = ((p >> 5) & 63) * 255 / 63;
        *b = (p & 31) * 255 / 31;
    }
}

static void poll_cb(void) {}
static size_t audio_cb(const int16_t* data, size_t frames) { (void)data; return frames; }

static void* (*mem_data)(unsigned) = NULL;
static size_t (*mem_size)(unsigned) = NULL;

static void dump_ppm(const char* path) {
    FILE* f = fopen(path, "wb");
    if (!f) { perror("ppm"); return; }
    fprintf(f, "P6\n%u %u\n255\n", fb_w, fb_h);
    for (unsigned y = 0; y < fb_h; y++) {
        for (unsigned x = 0; x < fb_w; x++) {
            uint8_t r, g, b;
            get_px(x, y, &r, &g, &b);
            fwrite(&r, 1, 1, f); fwrite(&g, 1, 1, f); fwrite(&b, 1, 1, f);
        }
    }
    fclose(f);
    printf("  ppm: %s\n", path);
}

static void frame_stats(void) {
    size_t n = (size_t)fb_w * fb_h;
    if (!n || !fb) { printf("  no frame\n"); return; }
    printf("  raw[0..15]:");
    for (int i = 0; i < 16; i++) printf(" %02x", fb[i]);
    printf("\n");
    double sr = 0, sg = 0, sb = 0;
    for (unsigned y = 0; y < fb_h; y++) {
        for (unsigned x = 0; x < fb_w; x++) {
            uint8_t r, g, b;
            get_px(x, y, &r, &g, &b);
            sr += r; sg += g; sb += b;
        }
    }
    sr /= n; sg /= n; sb /= n;
    uint32_t hash = 2166136261u;
    for (unsigned y = 0; y < fb_h; y++) {
        for (unsigned x = 0; x < fb_w; x++) {
            uint8_t r, g, b;
            get_px(x, y, &r, &g, &b);
            uint32_t v = r * 65536 + g * 256 + b;
            hash ^= v; hash *= 16777619u;
        }
    }
    printf("  frame: avg=(%.0f,%.0f,%.0f) hash=%08x\n", sr, sg, sb, hash);
}

static void dump_ram(void) {
    if (!mem_data) return;
    const uint8_t* ram = (const uint8_t*)mem_data(RETRO_MEMORY_SYSTEM_RAM);
    if (!ram) return;
    printf("  ram[0..7]:");
    for (int i = 0; i < 8; i++) printf(" %02x", ram[i]);
    printf("\n");
    const uint8_t* vram = (const uint8_t*)mem_data(RETRO_MEMORY_VIDEO_RAM);
    if (!vram) { printf("  vram: NULL\n"); return; }
    size_t vsz = mem_size ? mem_size(RETRO_MEMORY_VIDEO_RAM) : 0;
    printf("  vram size=%zu chr[0..15]:", vsz);
    for (int i = 0; i < 16; i++) printf(" %02x", vram[i]);
    printf("\n  nt[0..15]:");
    for (int i = 0; i < 16; i++) printf(" %02x", vram[0x2000 + i]);
    printf("\n  pal[0..15]:");
    for (int i = 0; i < 16; i++) printf(" %02x", vram[0x3F00 + i]);
    printf("\n");
}

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr,
            "usage: %s <rom> <core.so> [--frames N] [--press F:btns] [--reset F]\n"
            "         [--probe F,F,...] [--ppm F:path]\n"
            "buttons: a,b,c? нет: b,y,select,start,up,down,left,right,a,x,l,r,none\n", argv[0]);
        return 2;
    }

    // --- аргументы ---
    for (int i = 3; i < argc; i++) {
        if (!strcmp(argv[i], "--frames") && i + 1 < argc) {
            total_frames = atoi(argv[++i]);
        } else if (!strcmp(argv[i], "--press") && i + 1 < argc) {
            char* arg = argv[++i];
            char* colon = strchr(arg, ':');
            if (!colon) { fprintf(stderr, "bad --press: %s\n", arg); return 2; }
            *colon = 0;
            unsigned mask = 0;
            char* tok = strtok(colon + 1, "+");
            while (tok) {
                if (!strcmp(tok, "b")) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_B;
                else if (!strcmp(tok, "y")) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_Y;
                else if (!strcmp(tok, "select")) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_SELECT;
                else if (!strcmp(tok, "start")) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_START;
                else if (!strcmp(tok, "up")) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_UP;
                else if (!strcmp(tok, "down")) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_DOWN;
                else if (!strcmp(tok, "left")) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_LEFT;
                else if (!strcmp(tok, "right")) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_RIGHT;
                else if (!strcmp(tok, "a")) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_A;
                else if (!strcmp(tok, "none")) mask = 0;
                else { fprintf(stderr, "unknown button: %s\n", tok); return 2; }
                tok = strtok(NULL, "+");
            }
            presses[n_presses].frame = atoi(arg);
            presses[n_presses].mask = mask;
            n_presses++;
        } else if (!strcmp(argv[i], "--reset") && i + 1 < argc) {
            reset_frame = atoi(argv[++i]);
        } else if (!strcmp(argv[i], "--probe") && i + 1 < argc) {
            char* tok = strtok(argv[++i], ",");
            while (tok) {
                probes[n_probes].frame = atoi(tok);
                probes[n_probes].done = 0;
                n_probes++;
                tok = strtok(NULL, ",");
            }
        } else if (!strcmp(argv[i], "--ppm") && i + 1 < argc) {
            char* arg = argv[++i];
            char* colon = strchr(arg, ':');
            if (!colon) { fprintf(stderr, "bad --ppm: %s\n", arg); return 2; }
            *colon = 0;
            ppms[n_ppms].frame = atoi(arg);
            ppms[n_ppms].path = colon + 1;
            ppms[n_ppms].done = 0;
            n_ppms++;
        } else {
            fprintf(stderr, "unknown arg: %s\n", argv[i]);
            return 2;
        }
    }

    // --- загрузка ROM ---
    FILE* f = fopen(argv[1], "rb");
    if (!f) { perror("rom open"); return 2; }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    rom_data = malloc(sz);
    if (fread(rom_data, 1, sz, f) != (size_t)sz) { perror("fread"); return 2; }
    fclose(f);
    rom_size = sz;
    snprintf(rom_path, sizeof(rom_path), "%s", argv[1]);

    // --- ядро ---
    void* core = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
    if (!core) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 2; }
    void (*set_env)(retro_environment_t) = dlsym(core, "retro_set_environment");
    void (*set_video)(retro_video_refresh_t) = dlsym(core, "retro_set_video_refresh");
    void (*set_audio_batch)(retro_audio_sample_batch_t) = dlsym(core, "retro_set_audio_sample_batch");
    void (*set_poll)(retro_input_poll_t) = dlsym(core, "retro_set_input_poll");
    void (*set_input)(retro_input_state_t) = dlsym(core, "retro_set_input_state");
    void (*init)(void) = dlsym(core, "retro_init");
    void (*deinit)(void) = dlsym(core, "retro_deinit");
    bool (*load_game)(const struct retro_game_info*) = dlsym(core, "retro_load_game");
    void (*run)(void) = dlsym(core, "retro_run");
    void (*do_reset)(void) = dlsym(core, "retro_reset");
    void* (*mem_data_fn)(unsigned) = dlsym(core, "retro_get_memory_data");
    mem_data = mem_data_fn;
    if (!set_env || !load_game || !run || !do_reset) { fprintf(stderr, "dlsym fail\n"); return 2; }

    set_env(env_cb);
    set_video(video_cb);
    set_audio_batch(audio_cb);
    set_poll(poll_cb);
    set_input(input_cb);
    init();

    struct retro_game_info info;
    memset(&info, 0, sizeof(info));
    info.path = rom_path;
    info.data = rom_data;
    info.size = rom_size;
    if (!load_game(&info)) {
        fprintf(stderr, "ОШИБКА: ядро не загрузило ROM (смотрите лог выше)\n");
        return 1;
    }
    printf("LOADED: %s (%zu байт)\n", rom_path, rom_size);

    for (int fr = 0; fr < total_frames; fr++) {
        if (fr == reset_frame) {
            printf("RESET @ %d\n", fr);
            do_reset();
        }
        // активный ввод = последняя запись с frame <= fr
        current_mask = 0;
        for (int p = 0; p < n_presses; p++)
            if (presses[p].frame <= fr) current_mask = presses[p].mask;
        run();
        for (int p = 0; p < n_probes; p++) {
            if (!probes[p].done && probes[p].frame == fr) {
                printf("PROBE @ %d\n", fr);
                frame_stats();
                dump_ram();
                probes[p].done = 1;
            }
        }
        for (int p = 0; p < n_ppms; p++) {
            if (!ppms[p].done && ppms[p].frame == fr) {
                printf("PPM @ %d\n", fr);
                dump_ppm(ppms[p].path);
                ppms[p].done = 1;
            }
        }
    }

    printf("DONE\n");
    if (deinit) deinit();
    return 0;
}
