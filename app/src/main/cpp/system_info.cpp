#include <jni.h>
#include <string>
#include <fstream>
#include <sstream>
#include <vector>
#include <dirent.h>
#include <sys/stat.h>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "SystemInfoNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// ── helpers ──────────────────────────────────────────────────────────────────

static std::string readFile(const std::string& path) {
    std::ifstream f(path);
    if (!f.is_open()) return "";
    std::string line;
    std::getline(f, line);
    return line;
}

static bool fileReadable(const std::string& path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0;
}

static long parseLong(const std::string& s, long def = 0L) {
    if (s.empty()) return def;
    try { return std::stol(s); } catch (...) { return def; }
}

static float parseFloat(const std::string& s, float def = 0.f) {
    if (s.empty()) return def;
    try { return std::stof(s); } catch (...) { return def; }
}

// ── CPU Usage (/proc/stat) ────────────────────────────────────────────────────

static long g_prevTotal = -1L;
static long g_prevIdle  = -1L;

extern "C" JNIEXPORT jfloat JNICALL
Java_com_javapro_utils_SystemInfoNative_getCpuUsage(JNIEnv*, jobject) {
    std::ifstream f("/proc/stat");
    if (!f.is_open()) return 0.f;

    std::string line;
    std::getline(f, line);
    f.close();

    if (line.rfind("cpu ", 0) != 0) return 0.f;

    std::istringstream ss(line);
    std::string label;
    ss >> label;

    long user=0,nice=0,system=0,idle=0,iowait=0,irq=0,softirq=0,steal=0;
    ss >> user >> nice >> system >> idle >> iowait >> irq >> softirq >> steal;

    long total = user + nice + system + idle + iowait + irq + softirq + steal;

    if (g_prevTotal == -1L) {
        g_prevTotal = total;
        g_prevIdle  = idle;
        return 0.f;
    }

    long dTotal = total - g_prevTotal;
    long dIdle  = idle  - g_prevIdle;
    g_prevTotal = total;
    g_prevIdle  = idle;

    if (dTotal <= 0L) return 0.f;
    float pct = 100.f * (dTotal - dIdle) / (float)dTotal;
    return pct < 0.f ? 0.f : pct > 100.f ? 100.f : pct;
}

// ── CPU Cluster Freq (/sys/devices/system/cpu/cpu*/cpufreq/) ──────────────────
// Returns flat int array: [cur0, max0, cur1, max1, ...] in kHz

extern "C" JNIEXPORT jintArray JNICALL
Java_com_javapro_utils_SystemInfoNative_getCpuFreqs(JNIEnv* env, jobject, jint coreCount) {
    int n = (int)coreCount;
    std::vector<jint> result(n * 2, 0);

    for (int i = 0; i < n; i++) {
        std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/";
        std::string curStr = readFile(base + "scaling_cur_freq");
        std::string maxStr = readFile(base + "cpuinfo_max_freq");
        result[i * 2]     = (jint)parseLong(curStr);
        result[i * 2 + 1] = (jint)parseLong(maxStr);
    }

    jintArray arr = env->NewIntArray(n * 2);
    env->SetIntArrayRegion(arr, 0, n * 2, result.data());
    return arr;
}

// ── CPU Policy clusters (/sys/devices/system/cpu/cpufreq/policy*/) ───────────
// Returns comma-separated string "0,1,2|3,4,5|6,7" — each group = cluster

extern "C" JNIEXPORT jstring JNICALL
Java_com_javapro_utils_SystemInfoNative_getCpuPolicyClusters(JNIEnv* env, jobject) {
    const std::string base = "/sys/devices/system/cpu/cpufreq";
    DIR* dir = opendir(base.c_str());
    if (!dir) return env->NewStringUTF("");

    std::vector<std::pair<int,std::string>> policies; // policyIndex → cores string
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        std::string name(entry->d_name);
        if (name.rfind("policy", 0) != 0) continue;
        int pIdx = 0;
        try { pIdx = std::stoi(name.substr(6)); } catch (...) { continue; }
        std::string cpusPath = base + "/" + name + "/related_cpus";
        std::string cores = readFile(cpusPath);
        if (!cores.empty()) policies.push_back({pIdx, cores});
    }
    closedir(dir);

    // Sort by policy index
    std::sort(policies.begin(), policies.end(),
              [](auto& a, auto& b){ return a.first < b.first; });

    std::string result;
    for (size_t i = 0; i < policies.size(); i++) {
        if (i > 0) result += "|";
        // cores string is space-separated, convert to comma
        std::string& c = policies[i].second;
        for (char& ch : c) if (ch == ' ' || ch == '\t') ch = ',';
        // trim trailing commas
        while (!c.empty() && c.back() == ',') c.pop_back();
        result += c;
    }
    return env->NewStringUTF(result.c_str());
}

// ── CPU Temperature ───────────────────────────────────────────────────────────

static std::string g_cpuTempPath;
static int         g_cpuTempDiv = 1000;
static bool        g_cpuTempDetected = false;

static void detectCpuTempPath() {
    const char* baseDirs[] = {
        "/sys/class/thermal",
        "/sys/devices/virtual/thermal"
    };
    const char* priority[] = {"cpu_therm","cpuss","cpu","cluster0"};

    for (const char* baseDir : baseDirs) {
        DIR* dir = opendir(baseDir);
        if (!dir) continue;
        struct dirent* e;
        while ((e = readdir(dir)) != nullptr) {
            if (e->d_name[0] == '.') continue;
            std::string zone = std::string(baseDir) + "/" + e->d_name;
            std::string typeFile = zone + "/type";
            std::string tempFile = zone + "/temp";
            if (!fileReadable(tempFile)) continue;
            std::string type = readFile(typeFile);
            // lowercase
            for (char& c : type) c = (char)tolower(c);
            for (const char* p : priority) {
                if (type.find(p) != std::string::npos) {
                    std::string raw = readFile(tempFile);
                    long v = parseLong(raw);
                    if (v > 0) {
                        g_cpuTempPath = tempFile;
                        g_cpuTempDiv  = (v > 1000) ? 1000 : 1;
                        closedir(dir);
                        return;
                    }
                }
            }
        }
        closedir(dir);
    }

    // Fallbacks
    const char* fallbacks[] = {
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/hwmon/hwmon0/temp1_input",
        "/proc/mtktscpu/mtktscpu"
    };
    for (const char* fb : fallbacks) {
        if (fileReadable(fb)) {
            g_cpuTempPath = fb;
            g_cpuTempDiv  = 1000;
            return;
        }
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_javapro_utils_SystemInfoNative_getCpuTemp(JNIEnv*, jobject) {
    if (!g_cpuTempDetected) {
        detectCpuTempPath();
        g_cpuTempDetected = true;
    }
    if (g_cpuTempPath.empty()) return 0.f;
    std::string raw = readFile(g_cpuTempPath);
    float v = parseFloat(raw);
    if (v == 0.f) return 0.f;
    float celsius = v / (float)g_cpuTempDiv;
    return (celsius >= 1.f && celsius <= 150.f) ? celsius : 0.f;
}

// ── GPU Usage ─────────────────────────────────────────────────────────────────

static std::string g_gpuUsagePath;
static bool        g_gpuUsageDetected = false;

static float tryReadGpuUsage(const std::string& path) {
    std::string line = readFile(path);
    if (line.empty()) return -1.f;

    // ged hal: "gpu_loading=XX" or first number token
    if (path.find("ged/hal/gpu_utilization") != std::string::npos ||
        path.find("gpu_busy_percentage")     != std::string::npos ||
        path.find("gpu_busy")               != std::string::npos ||
        path.find("utilization")            != std::string::npos) {
        // strip non-numeric prefix
        size_t pos = 0;
        while (pos < line.size() && !isdigit(line[pos])) pos++;
        if (pos == line.size()) return -1.f;
        std::string num;
        while (pos < line.size() && (isdigit(line[pos]) || line[pos] == '.')) num += line[pos++];
        float v = parseFloat(num, -1.f);
        return (v >= 0.f && v <= 100.f) ? v : -1.f;
    }

    // kgsl gpubusy: "numerator denominator"
    if (path.find("kgsl") != std::string::npos && path.find("gpubusy") != std::string::npos) {
        std::istringstream ss(line);
        long num = 0, den = 0;
        ss >> num >> den;
        if (den > 0) {
            float pct = (float)num / (float)den * 100.f;
            return (pct >= 0.f && pct <= 100.f) ? pct : -1.f;
        }
        return -1.f;
    }

    // generic
    float v = parseFloat(line, -1.f);
    return (v >= 0.f && v <= 100.f) ? v : -1.f;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_javapro_utils_SystemInfoNative_getGpuUsage(JNIEnv*, jobject) {
    if (g_gpuUsageDetected && !g_gpuUsagePath.empty()) {
        float r = tryReadGpuUsage(g_gpuUsagePath);
        if (r >= 0.f) return r;
        g_gpuUsageDetected = false; // retry scan
    }

    const char* candidates[] = {
        "/sys/kernel/ged/hal/gpu_utilization",
        "/proc/mtk_mali/utilization",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/devices/platform/gpu/gpubusy",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/class/misc/mali0/device/utilization_pp",
        "/sys/kernel/debug/mali/utilization_gp_pp",
        "/sys/devices/platform/13000000.mali/utilization_gp_pp",
        nullptr
    };

    for (int i = 0; candidates[i]; i++) {
        float r = tryReadGpuUsage(candidates[i]);
        if (r >= 0.f) {
            g_gpuUsagePath     = candidates[i];
            g_gpuUsageDetected = true;
            return r;
        }
    }

    // devfreq scan
    DIR* dir = opendir("/sys/class/devfreq");
    if (dir) {
        struct dirent* e;
        while ((e = readdir(dir)) != nullptr) {
            std::string n(e->d_name);
            std::string nl = n;
            for (char& c : nl) c = (char)tolower(c);
            if (nl.find("gpu") == std::string::npos &&
                nl.find("kgsl") == std::string::npos &&
                nl.find("mali") == std::string::npos) continue;
            std::string path = std::string("/sys/class/devfreq/") + n + "/gpu_busy_percentage";
            float r = tryReadGpuUsage(path);
            if (r >= 0.f) {
                g_gpuUsagePath     = path;
                g_gpuUsageDetected = true;
                closedir(dir);
                return r;
            }
        }
        closedir(dir);
    }

    g_gpuUsageDetected = true; // mark done even if not found
    return 0.f;
}

// ── GPU Temp ──────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jfloat JNICALL
Java_com_javapro_utils_SystemInfoNative_getGpuTemp(JNIEnv*, jobject) {
    // Thermal zone scan
    DIR* dir = opendir("/sys/class/thermal");
    if (dir) {
        struct dirent* e;
        while ((e = readdir(dir)) != nullptr) {
            if (e->d_name[0] == '.') continue;
            std::string zone = std::string("/sys/class/thermal/") + e->d_name;
            std::string type = readFile(zone + "/type");
            for (char& c : type) c = (char)tolower(c);
            if (type.find("gpu")  != std::string::npos ||
                type.find("mali") != std::string::npos ||
                type.find("mfg")  != std::string::npos) {
                float raw = parseFloat(readFile(zone + "/temp"));
                float c = raw / 1000.f;
                if (c >= 1.f && c <= 150.f) { closedir(dir); return c; }
            }
        }
        closedir(dir);
    }

    // kgsl
    float kgsl = parseFloat(readFile("/sys/class/kgsl/kgsl-3d0/temp"));
    if (kgsl > 0.f) { float c = kgsl / 1000.f; if (c >= 1.f && c <= 150.f) return c; }

    return 0.f;
}

// ── RAM (/proc/meminfo) ───────────────────────────────────────────────────────
// Returns [usedMb, totalMb]

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_javapro_utils_SystemInfoNative_getRamMb(JNIEnv* env, jobject) {
    jlong result[2] = {0L, 0L};
    std::ifstream f("/proc/meminfo");
    if (!f.is_open()) {
        jlongArray arr = env->NewLongArray(2);
        env->SetLongArrayRegion(arr, 0, 2, result);
        return arr;
    }

    long memTotal = 0, memAvail = 0;
    std::string line;
    while (std::getline(f, line)) {
        if (line.rfind("MemTotal:", 0) == 0) {
            std::istringstream ss(line);
            std::string key; long val; std::string unit;
            ss >> key >> val >> unit;
            memTotal = val;
        } else if (line.rfind("MemAvailable:", 0) == 0) {
            std::istringstream ss(line);
            std::string key; long val; std::string unit;
            ss >> key >> val >> unit;
            memAvail = val;
        }
        if (memTotal > 0 && memAvail > 0) break;
    }

    result[0] = (memTotal - memAvail) / 1024L;  // used MB
    result[1] = memTotal / 1024L;               // total MB

    jlongArray arr = env->NewLongArray(2);
    env->SetLongArrayRegion(arr, 0, 2, result);
    return arr;
}
