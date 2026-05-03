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

// Sysfs FPS paths (dari Scene bytecode — priority order)
static const char* SYSFS_FPS_PATHS[] = {
    "/sys/class/drm/sde-crtc-0/measured_fps",
    "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-sde-crtc-0/measured_fps",
    "/sys/class/graphics/fb0/measured_fps",
    "/sys/class/drm/card0-DSI-1/measured_fps",
    "/sys/class/drm/card0/measured_fps",
    NULL
};

static char activeSysfsPath[512] = {0};
static int  sysfsScanned = 0;

static float readSysfsFps() {
    if (!sysfsScanned) {
        sysfsScanned = 1;
        for (int i = 0; SYSFS_FPS_PATHS[i]; i++) {
            FILE* f = fopen(SYSFS_FPS_PATHS[i], "r");
            if (!f) continue;
            char buf[64] = {0};
            fgets(buf, sizeof(buf), f);
            fclose(f);
            float v = atof(buf);
            if (v > 0.0f) {
                strncpy(activeSysfsPath, SYSFS_FPS_PATHS[i], sizeof(activeSysfsPath)-1);
                break;
            }
        }
    }
    if (!activeSysfsPath[0]) return 0.0f;
    FILE* f = fopen(activeSysfsPath, "r");
    if (!f) { activeSysfsPath[0] = '\0'; sysfsScanned = 0; return 0.0f; }
    char buf[64] = {0};
    fgets(buf, sizeof(buf), f);
    fclose(f);
    float v = atof(buf);
    if (v >= MIN_FPS && v <= maxRefreshRate * 1.05f) return v;
    return 0.0f;
}

// fpsgo_status (MTK) — parse kolom "currentFPS"
static float readFpsgoStatus() {
    FILE* f = popen("head -2 /sys/kernel/fpsgo/fstb/fpsgo_status 2>/dev/null", "r");
    if (!f) return 0.0f;
    char header[512] = {0};
    char data[512]   = {0};
    fgets(header, sizeof(header), f);
    fgets(data,   sizeof(data),   f);
    fclose(f);
    if (!header[0] || !data[0]) return 0.0f;

    // Cari index kolom "currentFPS" di header
    char* col = strstr(header, "currentFPS");
    if (!col) return 0.0f;

    // Hitung index kolom (split by whitespace)
    int colIdx = 0;
    char* p = header;
    while (p < col) {
        while (*p == ' ' || *p == '\t') p++;
        if (p >= col) break;
        colIdx++;
        while (*p && *p != ' ' && *p != '\t') p++;
    }

    // Ambil nilai di kolom yang sama dari baris data
    char* d = data;
    for (int i = 0; i < colIdx; i++) {
        while (*d == ' ' || *d == '\t') d++;
        while (*d && *d != ' ' && *d != '\t') d++;
    }
    while (*d == ' ' || *d == '\t') d++;
    float v = atof(d);
    if (v >= MIN_FPS && v <= maxRefreshRate * 1.05f) return v;
    return 0.0f;
}

// service call SurfaceFlinger 1013
// Dari bytecode Scene: output adalah FRAME COUNTER (integer), bukan IEEE-754 float.
// FPS = delta_frameCount * 1000 / elapsed_ms
static int64_t svc1013PrevCount = -1;
static int64_t svc1013PrevMs    = 0;

static float readServiceCall1013() {
    FILE* f = popen("service call SurfaceFlinger 1013 2>/dev/null", "r");
    if (!f) return 0.0f;

    char buf[256] = {0};
    char tmp[64];
    size_t total = 0;
    while (fgets(tmp, sizeof(tmp), f) && total < sizeof(buf) - 1) {
        strncat(buf, tmp, sizeof(buf) - total - 1);
        total += strlen(tmp);
    }
    fclose(f);

    if (!strstr(buf, "Parcel")) return 0.0f;
    if (strstr(buf, "error") || strstr(buf, "Error")) return 0.0f;

    // Cari '(' lalu parse hex token pertama non-zero
    char* p = strchr(buf, '(');
    if (!p) return 0.0f;
    p++;

    int64_t frameCount = -1;
    while (*p) {
        while (*p && !((*p>='0'&&*p<='9')||(*p>='a'&&*p<='f')||(*p>='A'&&*p<='F'))) p++;
        if (!*p) break;
        // Cek 8 hex chars
        int ok = 1;
        for (int i = 0; i < 8; i++) {
            char c = p[i];
            if (!((c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F'))) { ok=0; break; }
        }
        if (!ok) { p++; continue; }
        char hex[9] = {0};
        strncpy(hex, p, 8);
        p += 8;
        if (strcmp(hex, "00000000") == 0) continue;
        frameCount = (int64_t)strtoul(hex, NULL, 16);
        break;
    }

    if (frameCount <= 0) return 0.0f;

    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t nowMs = ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;

    if (svc1013PrevCount < 0 || svc1013PrevMs == 0) {
        svc1013PrevCount = frameCount;
        svc1013PrevMs    = nowMs;
        return 0.0f;
    }

    int64_t elapsed = nowMs - svc1013PrevMs;
    int64_t delta   = frameCount - svc1013PrevCount;

    svc1013PrevCount = frameCount;
    svc1013PrevMs    = nowMs;

    if (elapsed <= 0 || delta <= 0) return 0.0f;

    // fps = delta * 1000 / elapsed_ms  (persis rumus Scene line 472-482)
    float fps = (float)delta * 1000.0f / (float)elapsed;
    if (fps >= MIN_FPS && fps <= maxRefreshRate * 1.05f) return fps;
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
    setvbuf(stdout, NULL, _IOLBF, 0);

    maxRefreshRate = detectRefreshRate();

    char gameLayer[MAX_LINE] = {0};
    long layerCacheMs = 0;

    while (1) {
        struct timespec tick;
        clock_gettime(CLOCK_MONOTONIC, &tick);
        long nowMs = tick.tv_sec * 1000L + tick.tv_nsec / 1000000L;

        float fps = 0.0f;

        // L0: Sysfs measured_fps (paling cepat, tanpa shell overhead)
        fps = readSysfsFps();

        // L0b: fpsgo_status (MTK)
        if (fps <= 0.0f) fps = readFpsgoStatus();

        // L1: Scene-delta via --latency (include usleep 250ms di dalam)
        if (fps <= 0.0f) {
            if (nowMs - layerCacheMs > 3000L) {
                resolveGameLayer(gameLayer, sizeof(gameLayer));
                layerCacheMs = nowMs;
            }
            char layerArg[MAX_LINE + 2] = {0};
            if (gameLayer[0]) snprintf(layerArg, sizeof(layerArg), "\"%s\"", gameLayer);
            fps = readSceneDeltaFps(layerArg);
        }

        // L2: service call 1013 (frame counter delta, Scene approach)
        if (fps <= 0.0f) {
            fps = readServiceCall1013();
            if (fps > 0.0f) usleep(SAMPLE_US);
        }

        if (fps > 0.0f) {
            printf("%.1f\n", applySma(fps));
        } else {
            printf("0\n");
        }
    }

    return 0;
}
