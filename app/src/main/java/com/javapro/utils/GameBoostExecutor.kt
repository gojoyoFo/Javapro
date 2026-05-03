package com.javapro.utils

import android.content.Context
import android.content.Intent

object GameBoostExecutor {

    data class BoostConfig(
        val killBg: Boolean          = false,
        val prioritize: Boolean      = false,
        val disableAnim: Boolean     = false,
        val touchBoost: Boolean      = false,
        val gameMode: Boolean        = false,
        val memOpt: Boolean          = false,
        val renderAhead: Boolean     = false,
        val renderAheadVal: String   = "1",
        val sustainedPerf: Boolean   = false,
        val thermal: Boolean         = false,
        val cpuGpuBoost: Boolean     = false,
        val uclamp: Boolean          = false,
        val io: Boolean              = false,
        val vm: Boolean              = false,
        val sched: Boolean           = false,
        val net: Boolean             = false,
        val doze: Boolean            = false,
        val lmk: Boolean             = false,
        val wifiLatency: Boolean     = false,
        val adrenoBoost: String      = "0",
        val gpuIdleTimer: Boolean    = false,
        val irqAffinity: Boolean     = false,
        val scale: String            = "disable",
        val fps: String              = "60",
        val lockFps: Boolean         = false,
        val dsMethod: String         = "new",
        val driver: String           = "default"
    )

    suspend fun applyBoost(pkg: String, cfg: BoostConfig, isRooted: Boolean) {
        applyBaseOptions(pkg, cfg)
        if (isRooted) applyRootOptions(pkg, cfg)
    }

    suspend fun stopBoost(pkg: String, isRooted: Boolean) {
        TweakExecutor.execute("cmd game mode 1 $pkg")
        TweakExecutor.execute("cmd game reset \"$pkg\"")
        TweakExecutor.execute("cmd device_config delete game_overlay \"$pkg\"")
        TweakExecutor.execute("settings put global window_animation_scale 1.0")
        TweakExecutor.execute("settings put global transition_animation_scale 1.0")
        TweakExecutor.execute("settings put global animator_duration_scale 1.0")
        TweakExecutor.execute("cmd device_config put input_native_boot palm_rejection_enabled 1")
        TweakExecutor.execute("settings delete system touch_blocking_period")
        TweakExecutor.execute("settings put global sustained_performance_mode 0")
        if (isRooted) stopRootOptions()
    }

    suspend fun applyBoostOptions(pkg: String, cfg: BoostConfig) {
        if (cfg.killBg) TweakExecutor.execute("am kill-all")
        if (cfg.gameMode) TweakExecutor.execute("cmd game mode 2 $pkg")
        if (cfg.disableAnim) {
            TweakExecutor.execute("settings put global window_animation_scale 0.5")
            TweakExecutor.execute("settings put global transition_animation_scale 0.5")
            TweakExecutor.execute("settings put global animator_duration_scale 0.5")
        }
        if (cfg.touchBoost) {
            TweakExecutor.execute("cmd device_config put input_native_boot palm_rejection_enabled 0")
            TweakExecutor.execute("settings put system touch_blocking_period 0")
        }
        if (cfg.prioritize) {
            TweakExecutor.execute("cmd game set --mode 2 $pkg")
            TweakExecutor.execute("cmd device_config put game_overlay \"$pkg\" \"mode=2\"")
        }
        if (cfg.memOpt) TweakExecutor.execute("echo 3 > /proc/sys/vm/drop_caches")
    }

    suspend fun applyPerfExtra(pkg: String, cfg: BoostConfig) {
        if (cfg.renderAhead) TweakExecutor.execute("cmd game set --render_ahead ${cfg.renderAheadVal} $pkg")
        if (cfg.sustainedPerf) TweakExecutor.execute("settings put global sustained_performance_mode 1")
        if (cfg.thermal) applyThermalDisable()
        if (cfg.cpuGpuBoost) applyCpuGpuBoost()
        if (cfg.uclamp) applyUclamp()
        if (cfg.io) applyIoTweak()
        if (cfg.vm) applyVmTweak()
        if (cfg.sched) applySchedTweak()
        if (cfg.lmk) applyLmkTweak()
        if (cfg.net) applyNetTweak()
        if (cfg.wifiLatency) applyWifiTweak()
        if (cfg.doze) TweakExecutor.execute("dumpsys deviceidle disable")
    }

    suspend fun applyRootExtra(pkg: String, cfg: BoostConfig) {
        if (cfg.adrenoBoost != "0") {
            TweakExecutor.execute("[ -e /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost ] && echo ${cfg.adrenoBoost} > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost")
        }
        if (cfg.gpuIdleTimer) {
            TweakExecutor.execute("[ -e /sys/class/kgsl/kgsl-3d0/idle_timer ] && echo 10 > /sys/class/kgsl/kgsl-3d0/idle_timer")
        }
        if (cfg.irqAffinity) {
            TweakExecutor.execute("for f in /proc/irq/*/smp_affinity_list; do echo f > \$f 2>/dev/null; done")
        }
    }

    suspend fun applyDownscale(pkg: String, cfg: BoostConfig) {
        if (cfg.dsMethod == "new") {
            if (cfg.scale == "disable") {
                TweakExecutor.execute("cmd game reset \"$pkg\"")
                TweakExecutor.execute("cmd device_config delete game_overlay \"$pkg\"")
            } else {
                TweakExecutor.execute("cmd game set --downscale ${cfg.scale} $pkg")
            }
        } else {
            if (cfg.scale != "disable") {
                TweakExecutor.execute("cmd game downscale ${cfg.scale} $pkg")
                TweakExecutor.execute("cmd device_config put game_overlay \"$pkg\" \"mode=2,downscaleFactor=${cfg.scale},fps=${cfg.fps}\"")
                TweakExecutor.execute("cmd game mode 2 \"$pkg\"")
            } else {
                TweakExecutor.execute("cmd game reset \"$pkg\"")
                TweakExecutor.execute("cmd device_config delete game_overlay \"$pkg\"")
            }
        }
        if (cfg.lockFps) {
            TweakExecutor.execute("settings put system peak_refresh_rate ${cfg.fps}.0")
            TweakExecutor.execute("settings put system min_refresh_rate ${cfg.fps}.0")
            TweakExecutor.execute("settings put secure user_refresh_rate ${cfg.fps}")
            TweakExecutor.execute("service call SurfaceFlinger 1035 i32 ${cfg.fps}")
        }
    }

    suspend fun resetDownscale(pkg: String) {
        TweakExecutor.execute("cmd game reset \"$pkg\"")
        TweakExecutor.execute("cmd device_config delete game_overlay \"$pkg\"")
        TweakExecutor.execute("settings delete system peak_refresh_rate")
        TweakExecutor.execute("settings delete system min_refresh_rate")
        TweakExecutor.execute("settings delete secure user_refresh_rate")
    }

    suspend fun applyDriver(pkg: String, driver: String) {
        when (driver) {
            "skia_vulkan" -> {
                TweakExecutor.execute("settings put global gpu_debug_layers off")
                TweakExecutor.execute("cmd device_config put game_driver enable_game_driver 1")
                TweakExecutor.execute("setprop debug.hwui.renderer skiaVk")
                TweakExecutor.execute("setprop debug.renderengine.backend skiaVk")
            }
            "skia_gl" -> {
                TweakExecutor.execute("settings put global gpu_debug_layers off")
                TweakExecutor.execute("cmd device_config put game_driver enable_game_driver 1")
                TweakExecutor.execute("setprop debug.hwui.renderer skiagl")
                TweakExecutor.execute("setprop debug.renderengine.backend skiagl")
            }
            "opengl" -> {
                TweakExecutor.execute("settings put global gpu_debug_layers off")
                TweakExecutor.execute("cmd device_config put game_driver enable_game_driver 0")
                TweakExecutor.execute("setprop debug.hwui.renderer opengl")
                TweakExecutor.execute("setprop debug.renderengine.backend opengl")
            }
            "default" -> {
                TweakExecutor.execute("setprop debug.hwui.renderer \"\"")
                TweakExecutor.execute("setprop debug.renderengine.backend \"\"")
                TweakExecutor.execute("cmd device_config delete game_driver enable_game_driver")
            }
        }
    }

    fun launchGame(context: Context, pkg: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    private suspend fun applyBaseOptions(pkg: String, cfg: BoostConfig) {
        if (cfg.killBg) TweakExecutor.execute("am kill-all")
        if (cfg.gameMode) TweakExecutor.execute("cmd game mode 2 $pkg")
        if (cfg.disableAnim) {
            TweakExecutor.execute("settings put global window_animation_scale 0.5")
            TweakExecutor.execute("settings put global transition_animation_scale 0.5")
            TweakExecutor.execute("settings put global animator_duration_scale 0.5")
        }
        if (cfg.touchBoost) {
            TweakExecutor.execute("cmd device_config put input_native_boot palm_rejection_enabled 0")
            TweakExecutor.execute("settings put system touch_blocking_period 0")
        }
        if (cfg.prioritize) {
            TweakExecutor.execute("cmd game set --mode 2 $pkg")
            TweakExecutor.execute("cmd device_config put game_overlay \"$pkg\" \"mode=2\"")
        }
        if (cfg.memOpt) TweakExecutor.execute("echo 3 > /proc/sys/vm/drop_caches")
        if (cfg.renderAhead) TweakExecutor.execute("cmd game set --render_ahead ${cfg.renderAheadVal} $pkg")
        if (cfg.sustainedPerf) TweakExecutor.execute("settings put global sustained_performance_mode 1")
    }

    private suspend fun applyRootOptions(pkg: String, cfg: BoostConfig) {
        if (cfg.thermal) applyThermalDisable()
        if (cfg.cpuGpuBoost) applyCpuGpuBoost()
        if (cfg.uclamp) applyUclamp()
        if (cfg.io) applyIoTweak()
        if (cfg.vm) applyVmTweak()
        if (cfg.sched) applySchedTweak()
        if (cfg.lmk) applyLmkTweak()
        if (cfg.net) applyNetTweak()
        if (cfg.wifiLatency) applyWifiTweak()
        if (cfg.doze) TweakExecutor.execute("dumpsys deviceidle disable")
        if (cfg.adrenoBoost != "0") TweakExecutor.execute("[ -e /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost ] && echo ${cfg.adrenoBoost} > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost")
        if (cfg.gpuIdleTimer) TweakExecutor.execute("[ -e /sys/class/kgsl/kgsl-3d0/idle_timer ] && echo 10 > /sys/class/kgsl/kgsl-3d0/idle_timer")
        if (cfg.irqAffinity) TweakExecutor.execute("for f in /proc/irq/*/smp_affinity_list; do echo f > \$f 2>/dev/null; done")
    }

    private suspend fun stopRootOptions() {
        TweakExecutor.execute("for i in /sys/class/thermal/thermal_zone*; do [ -f \"\$i/mode\" ] && echo enabled > \"\$i/mode\"; [ -f \"\$i/temp\" ] && chmod 644 \"\$i/temp\"; done")
        TweakExecutor.execute("start thermal-engine 2>/dev/null; start thermald 2>/dev/null; setprop vendor.thermal.mode.disable 0")
        TweakExecutor.execute("getprop | grep 'init.svc_debug_pid.' | grep -i thermal | grep -iv hal | cut -d'[' -f2 | cut -d']' -f1 | while read pid; do [ -z \"\$pid\" ] && continue; [ ! -d \"/proc/\$pid\" ] && continue; cg=\$(sed -n 's/^0:://p' /proc/\$pid/cgroup 2>/dev/null); [ -z \"\$cg\" ] && continue; echo 0 > \"/sys/fs/cgroup\$cg/cgroup.freeze\" 2>/dev/null; done")
        TweakExecutor.execute("for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo schedutil > \$g 2>/dev/null; done")
        TweakExecutor.execute("for m in /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_min_freq; do d=\$(dirname \$m); cat \$m > \$d/scaling_min_freq 2>/dev/null; done")
        TweakExecutor.execute("echo 0 > /dev/cpuctl/foreground/cpu.uclamp.min 2>/dev/null")
        TweakExecutor.execute("echo 100 > /dev/cpuctl/foreground/cpu.uclamp.max 2>/dev/null")
        TweakExecutor.execute("echo 40 > /proc/sys/vm/swappiness")
        TweakExecutor.execute("echo 100 > /proc/sys/vm/vfs_cache_pressure")
        TweakExecutor.execute("echo 0 > /proc/sys/kernel/sched_child_runs_first")
        TweakExecutor.execute("echo 1 > /proc/sys/kernel/sched_autogroup_enabled")
        TweakExecutor.execute("settings put global wifi_sleep_policy 0")
        TweakExecutor.execute("settings put global wifi_scan_throttle_enabled 1")
        TweakExecutor.execute("dumpsys deviceidle enable")
        TweakExecutor.execute("[ -e /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost ] && echo 0 > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost")
    }

    private suspend fun applyThermalDisable() {
        TweakExecutor.execute("for i in /sys/class/thermal/thermal_zone*; do [ -f \"\$i/mode\" ] && echo disabled > \"\$i/mode\"; [ -f \"\$i/temp\" ] && chmod 000 \"\$i/temp\"; done")
        TweakExecutor.execute("stop thermal-engine 2>/dev/null; stop thermald 2>/dev/null; setprop vendor.thermal.mode.disable 1")
        TweakExecutor.execute("getprop | grep 'init.svc_debug_pid.' | grep -i thermal | grep -iv hal | cut -d'[' -f2 | cut -d']' -f1 | while read pid; do [ -z \"\$pid\" ] && continue; [ ! -d \"/proc/\$pid\" ] && continue; cg=\$(sed -n 's/^0:://p' /proc/\$pid/cgroup 2>/dev/null); [ -z \"\$cg\" ] && continue; echo 1 > \"/sys/fs/cgroup\$cg/cgroup.freeze\" 2>/dev/null; done")
    }

    private suspend fun applyCpuGpuBoost() {
        TweakExecutor.execute("for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo performance > \$g; done")
        TweakExecutor.execute("for m in /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq; do d=\$(dirname \$m); cat \$m > \$d/scaling_max_freq; cat \$m > \$d/scaling_min_freq; done")
        TweakExecutor.execute("[ -e /sys/class/kgsl/kgsl-3d0/devfreq/governor ] && echo performance > /sys/class/kgsl/kgsl-3d0/devfreq/governor && cat /sys/class/kgsl/kgsl-3d0/max_gpuclk > /sys/class/kgsl/kgsl-3d0/min_pwrlevel")
        TweakExecutor.execute("[ -e /sys/class/devfreq/mtk-mali/governor ] && echo performance > /sys/class/devfreq/mtk-mali/governor")
        TweakExecutor.execute("[ -e /proc/gpufreq/mtk_mali_sysfs/mali_pm_governor ] && echo performance > /proc/gpufreq/mtk_mali_sysfs/mali_pm_governor")
        TweakExecutor.execute("[ -e /sys/class/kgsl/kgsl-3d0/force_bus_on ] && echo 1 > /sys/class/kgsl/kgsl-3d0/force_bus_on")
        TweakExecutor.execute("[ -e /sys/class/kgsl/kgsl-3d0/force_clk_on ] && echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on")
        TweakExecutor.execute("[ -e /sys/class/kgsl/kgsl-3d0/force_rail_on ] && echo 1 > /sys/class/kgsl/kgsl-3d0/force_rail_on")
        TweakExecutor.execute("[ -e /sys/class/kgsl/kgsl-3d0/bus_split ] && echo 0 > /sys/class/kgsl/kgsl-3d0/bus_split")
    }

    private suspend fun applyUclamp() {
        TweakExecutor.execute("echo 100 > /dev/cpuctl/foreground/cpu.uclamp.min 2>/dev/null")
        TweakExecutor.execute("echo 100 > /dev/cpuctl/foreground/cpu.uclamp.max 2>/dev/null")
        TweakExecutor.execute("echo 100 > /dev/cpuctl/top-app/cpu.uclamp.min 2>/dev/null")
        TweakExecutor.execute("echo 100 > /dev/cpuctl/top-app/cpu.uclamp.max 2>/dev/null")
    }

    private suspend fun applyIoTweak() {
        TweakExecutor.execute("for q in /sys/block/*/queue; do echo none > \$q/scheduler 2>/dev/null; echo 0 > \$q/iostats 2>/dev/null; echo 2 > \$q/rq_affinity 2>/dev/null; echo 0 > \$q/add_random 2>/dev/null; echo 256 > \$q/nr_requests 2>/dev/null; done")
    }

    private suspend fun applyVmTweak() {
        TweakExecutor.execute("echo 0 > /proc/sys/vm/swappiness")
        TweakExecutor.execute("echo 10 > /proc/sys/vm/vfs_cache_pressure")
        TweakExecutor.execute("echo 0 > /proc/sys/vm/page-cluster")
        TweakExecutor.execute("echo 50 > /proc/sys/vm/dirty_ratio")
        TweakExecutor.execute("echo 10 > /proc/sys/vm/dirty_background_ratio")
        TweakExecutor.execute("echo 4096 > /proc/sys/vm/min_free_kbytes")
    }

    private suspend fun applySchedTweak() {
        TweakExecutor.execute("echo 1 > /proc/sys/kernel/sched_child_runs_first")
        TweakExecutor.execute("echo 0 > /proc/sys/kernel/sched_autogroup_enabled")
        TweakExecutor.execute("echo 4000000 > /proc/sys/kernel/sched_latency_ns")
        TweakExecutor.execute("echo 500000 > /proc/sys/kernel/sched_min_granularity_ns")
        TweakExecutor.execute("echo 1000000 > /proc/sys/kernel/sched_wakeup_granularity_ns")
    }

    private suspend fun applyLmkTweak() {
        TweakExecutor.execute("echo 0,0,0,0,0,0 > /sys/module/lowmemorykiller/parameters/adj 2>/dev/null")
        TweakExecutor.execute("echo 1 > /proc/sys/vm/oom_kill_allocating_task 2>/dev/null")
        TweakExecutor.execute("setprop lmkd.reinit 1 2>/dev/null")
    }

    private suspend fun applyNetTweak() {
        TweakExecutor.execute("echo 1 > /proc/sys/net/ipv4/tcp_fastopen")
        TweakExecutor.execute("echo 1 > /proc/sys/net/ipv4/tcp_sack")
        TweakExecutor.execute("echo 1 > /proc/sys/net/ipv4/tcp_timestamps")
        TweakExecutor.execute("settings put global mobile_data_always_on 1")
    }

    private suspend fun applyWifiTweak() {
        TweakExecutor.execute("settings put global wifi_sleep_policy 2")
        TweakExecutor.execute("settings put global wifi_scan_throttle_enabled 0")
    }
}
