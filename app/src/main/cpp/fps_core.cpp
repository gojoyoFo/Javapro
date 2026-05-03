#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <time.h>
#define SAMPLE_US       250000   
#define MAX_LINE        512
#define MAX_TIMESTAMPS  256
#define MIN_FPS         1.0f
#define MAX_FPS         400.0f

static float maxRefreshRate = 144.0f;

static float detectRefreshRate() {
    FILE* f = popen(
        "cat /sys/class/drm/card0-*/modes 2>/dev/null | grep -oE '[0-9]+$' | sort -n | tail -1",
        "r"
    );
    if (!f) return 60.0f;
    char buf[64] = {0};
    if (fgets(buf, sizeof(buf), f)) {
        fclose(f);
        float v = atof(buf);
        if (v >= 60.0f && v <= 360.0f) return v;
    } else {
        fclose(f);
    }
    return 60.0f;
}

static int resolveGameLayer(char* out, size_t outSize) {
    FILE* f = popen("dumpsys SurfaceFlinger --list 2>/dev/null", "r");
    if (!f) return 0;

    const char* skip[] = {
        "NavigationBar", "StatusBar", "ScreenDecor", "InputMethod",
        "WallpaperSurface", "pip-dismiss", "Splash Screen",
        "ShellDropTarget", "PointerLocation", "mouse pointer",
        "com.javapro", NULL
    };
    const char* priority[] = {
        "SurfaceView", "Sprite", "UnityMain", "GameActivity",
        "RenderThread", NULL
    };

    char line[MAX_LINE];
    char found[MAX_LINE] = {0};

    while (fgets(line, sizeof(line), f)) {
        // Strip newline
        size_t len = strlen(line);
        if (len > 0 && line[len-1] == '\n') line[len-1] = '\0';

        // Check skip
        int shouldSkip = 0;
        for (int i = 0; skip[i]; i++) {
            if (strstr(line, skip[i])) { shouldSkip = 1; break; }
        }
        if (shouldSkip) continue;

        // Check priority keywords
        for (int i = 0; priority[i]; i++) {
            if (strstr(line, priority[i])) {
                strncpy(found, line, sizeof(found) - 1);
                goto done;
            }
        }
    }
done:
    fclose(f);
    if (found[0]) {
        snprintf(out, outSize, "%s", found);
        return 1;
    }
    return 0;
}

// Baca timestamps dari --latency, hitung FPS via scene-delta
static float readSceneDeltaFps(const char* layerArg) {
    char cmd[512];
    snprintf(cmd, sizeof(cmd),
        "dumpsys SurfaceFlinger --latency-clear %s >/dev/null 2>&1;"
        " usleep %d 2>/dev/null || sleep 0;"
        " dumpsys SurfaceFlinger --latency %s 2>/dev/null"
        " | awk '{print $2}' | grep -v '^0$' | grep -v '^$'",
        layerArg, SAMPLE_US, layerArg
    );

    FILE* f = popen(cmd, "r");
    if (!f) return 0.0f;

    int64_t timestamps[MAX_TIMESTAMPS];
    int count = 0;
    char line[64];

    while (fgets(line, sizeof(line), f) && count < MAX_TIMESTAMPS) {
        int64_t ts = (int64_t)atoll(line);
        if (ts > 0 && ts != INT64_MAX) {
            timestamps[count++] = ts;
        }
    }
    fclose(f);

    if (count < 5) return 0.0f;

    // Scene-delta: FPS = (count-1) / (tMax - tMin) * 1e9
    int64_t tMin = timestamps[0];
    int64_t tMax = timestamps[count - 1];
    int64_t rangeNs = tMax - tMin;

    if (rangeNs <= 0) return 0.0f;

    float intervalNs = (float)rangeNs / (count - 1);
    float fps = 1000000000.0f / intervalNs;

    if (fps < MIN_FPS || fps > maxRefreshRate * 1.05f) return 0.0f;
    return fps;
}

// Baca via service call SurfaceFlinger 1013 (Hex Parcel → IEEE-754)
static float readServiceCall1013() {
    FILE* f = popen("service call SurfaceFlinger 1013 2>/dev/null", "r");
    if (!f) return 0.0f;

    char buf[256] = {0};
    size_t total = 0;
    char tmp[64];
    while (fgets(tmp, sizeof(tmp), f) && total < sizeof(buf) - 1) {
        strncat(buf, tmp, sizeof(buf) - total - 1);
        total += strlen(tmp);
    }
    fclose(f);

    // Parse hex groups: "00000000 42740000 ..."
    char* p = buf;
    while (*p) {
        while (*p && !( (*p >= '0' && *p <= '9') || (*p >= 'a' && *p <= 'f') || (*p >= 'A' && *p <= 'F') )) p++;
        if (!*p) break;

        // Cek apakah 8 hex chars berturut
        int isHex = 1;
        for (int i = 0; i < 8; i++) {
            char c = p[i];
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                isHex = 0; break;
            }
        }
        char after = p[8];
        if (!isHex || (after != ' ' && after != '\n' && after != '\0' && after != '\'' && after != ')')) {
            p++; continue;
        }

        char hex[9] = {0};
        strncpy(hex, p, 8);
        p += 8;

        // Skip 00000000 (status)
        if (strcmp(hex, "00000000") == 0) continue;

        uint32_t bits = (uint32_t)strtoul(hex, NULL, 16);
        float val;
        memcpy(&val, &bits, 4);

        if (val >= MIN_FPS && val <= maxRefreshRate * 1.05f) {
            return val;
        }
    }
    return 0.0f;
}

// Simple moving average, window=8
#define SMA_SIZE 8
static float smaBuffer[SMA_SIZE] = {0};
static int   smaIdx   = 0;
static int   smaCount = 0;

static float applySma(float val) {
    smaBuffer[smaIdx] = val;
    smaIdx = (smaIdx + 1) % SMA_SIZE;
    if (smaCount < SMA_SIZE) smaCount++;
    float sum = 0.0f;
    for (int i = 0; i < smaCount; i++) sum += smaBuffer[i];
    return sum / smaCount;
}

int main(int argc, char** argv) {
    // Disable stdout buffering agar Kotlin bisa baca line-by-line
    setvbuf(stdout, NULL, _IOLBF, 0);

    maxRefreshRate = detectRefreshRate();

    char gameLayer[MAX_LINE] = {0};
    long layerCacheMs = 0;

    while (1) {
        struct timespec tick;
        clock_gettime(CLOCK_MONOTONIC, &tick);

        // Refresh game layer setiap 3 detik
        long nowMs = tick.tv_sec * 1000L + tick.tv_nsec / 1000000L;
        if (nowMs - layerCacheMs > 3000L) {
            resolveGameLayer(gameLayer, sizeof(gameLayer));
            layerCacheMs = nowMs;
        }

        char layerArg[MAX_LINE + 2] = {0};
        if (gameLayer[0]) snprintf(layerArg, sizeof(layerArg), "\"%s\"", gameLayer);

        float fps = 0.0f;

        // L1: scene-delta via --latency (ini sudah include usleep 250ms di dalamnya)
        fps = readSceneDeltaFps(layerArg);

        // L2: service call 1013 sebagai fallback
        if (fps <= 0.0f) {
            fps = readServiceCall1013();
            if (fps > 0.0f) {
                usleep(SAMPLE_US);
            }
        }

        // Output ke stdout (dibaca Kotlin via stream)
        if (fps > 0.0f) {
            float smoothed = applySma(fps);
            printf("%.1f\n", smoothed);
        } else {
            printf("0\n");
        }

        // Pastikan total interval ~250ms (scene-delta sudah include usleep)
        // Kalau L2 yang jalan, sudah di-sleep di atas
    }

    return 0;
}
