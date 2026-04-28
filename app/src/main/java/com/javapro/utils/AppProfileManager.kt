package com.javapro.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class AppInfo(
    val name        : String,
    val packageName : String,
    val icon        : Drawable,
    val profile     : String
)

object AppProfileManager {
    private const val PREF_PROFILE  = "App_Profiles"
    private const val PREF_SETTINGS = "App_Settings"

    private suspend fun exec(command: String) {
        TweakExecutor.execute(command)
    }

    suspend fun applyDownscale(packageName: String, scale: String, fps: String) {
        exec("cmd game downscale $scale $packageName")
        exec("cmd device_config put game_overlay \"$packageName\" \"mode=2,downscaleFactor=$scale,useAngle=false,fps=$fps,loadingBoost=999999999\"")
        exec("cmd game mode 2 \"$packageName\"")
    }

    suspend fun resetDownscale(packageName: String) {
        exec("cmd game reset \"$packageName\"")
        exec("cmd device_config delete game_overlay \"$packageName\"")
    }

    fun getAppProfile(context: Context, packageName: String): String {
        return context.getSharedPreferences(PREF_PROFILE, Context.MODE_PRIVATE)
            .getString(packageName, "balance") ?: "balance"
    }

    fun setAppProfile(context: Context, packageName: String, mode: String) {
        context.getSharedPreferences(PREF_PROFILE, Context.MODE_PRIVATE)
            .edit().putString(packageName, mode).apply()
        applyProfileTweak(context, mode)
    }

    fun applyProfileTweak(context: Context, mode: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val cmds = mutableListOf<String>()
            when (mode) {
                "performance" -> {
                    cmds.addAll(getThermalStopCommands())
                    cmds.add("sh -c 'for cpu in /sys/devices/system/cpu/cpu*/cpufreq; do echo performance > \$cpu/scaling_governor; cat \$cpu/cpuinfo_max_freq > \$cpu/scaling_max_freq; cat \$cpu/cpuinfo_max_freq > \$cpu/scaling_min_freq; done'")
                    cmds.add("echo performance > /sys/class/kgsl/kgsl-3d0/devfreq/governor")
                    cmds.add("echo 0 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel")
                    cmds.add("echo 1 > /sys/class/kgsl/kgsl-3d0/force_bus_on")
                    cmds.add("echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on")
                    cmds.add("echo 100 > /dev/cpuctl/foreground/cpu.uclamp.min")
                    cmds.add("echo 100 > /dev/cpuctl/foreground/cpu.uclamp.max")
                    cmds.add("echo 1024 > /dev/cpuctl/foreground/cpu.shares")
                    cmds.add("echo 0 > /proc/sys/vm/swappiness")
                    cmds.add("echo 10 > /proc/sys/vm/vfs_cache_pressure")
                    cmds.add("echo 1 > /proc/sys/kernel/sched_child_runs_first")
                    cmds.add("echo 0 > /proc/sys/kernel/sched_autogroup_enabled")
                    cmds.add("dumpsys deviceidle disable")
                    cmds.add("echo 3 > /proc/sys/vm/drop_caches")
                }
                "powersave" -> {
                    cmds.addAll(getThermalStartCommands())
                    cmds.add("sh -c 'for cpu in /sys/devices/system/cpu/cpu*/cpufreq; do echo powersave > \$cpu/scaling_governor; cat \$cpu/cpuinfo_min_freq > \$cpu/scaling_min_freq; done'")
                    cmds.add("echo 10 > /dev/cpuctl/foreground/cpu.uclamp.max")
                    cmds.add("echo 100 > /proc/sys/vm/swappiness")
                    cmds.add("dumpsys deviceidle force-idle")
                }
                else -> {
                    cmds.addAll(getThermalStartCommands())
                    cmds.add("sh -c 'for cpu in /sys/devices/system/cpu/cpu*/cpufreq; do echo schedutil > \$cpu/scaling_governor; cat \$cpu/cpuinfo_min_freq > \$cpu/scaling_min_freq; done'")
                    cmds.add("echo 40 > /proc/sys/vm/swappiness")
                    cmds.add("dumpsys deviceidle enable")
                }
            }
            cmds.forEach { exec(it) }
        }
    }

    private fun getThermalStopCommands() = listOf(
        "stop thermal-engine", "stop thermald",
        "setprop init.svc.vendor.thermal-hal-2-0.mtk stopped",
        "setprop init.svc.vendor.thermal-hal-1-0 stopped",
        "setprop init.svc.thermal-engine stopped",
        "setprop ctl.stop thermal-engine",
        "setprop vendor.thermal.mode.disable 1"
    )

    private fun getThermalStartCommands() = listOf(
        "start thermal-engine", "start thermald",
        "setprop vendor.thermal.mode.disable 0",
        "setprop ctl.start thermal-engine"
    )

    fun getAppDriver(context: Context, packageName: String): String {
        return context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .getString("driver_$packageName", "default") ?: "default"
    }

    fun setAppDriver(context: Context, packageName: String, driver: String) {
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .edit().putString("driver_$packageName", driver).apply()
        CoroutineScope(Dispatchers.IO).launch {
            when (driver) {
                "game" -> {
                    exec("cmd game mode 2 $packageName")
                    exec("settings put global updatable_driver_production_opt_in_apps $packageName")
                }
                "angle" -> {
                    exec("settings put global angle_gl_driver_selection_pkgs $packageName")
                    exec("settings put global angle_gl_driver_selection_values angle")
                    exec("cmd game mode 2 $packageName")
                }
                "system" -> {
                    exec("cmd game mode 1 $packageName")
                    exec("settings put global updatable_driver_production_opt_out_apps $packageName")
                }
                "prerelease" -> {
                    exec("cmd game mode 2 $packageName")
                    exec("settings put global updatable_driver_prerelease_opt_in_apps $packageName")
                }
                else -> {
                    exec("cmd game reset $packageName")
                    exec("settings delete global updatable_driver_production_opt_in_apps")
                    exec("settings delete global updatable_driver_production_opt_out_apps")
                    exec("settings delete global updatable_driver_prerelease_opt_in_apps")
                    exec("settings delete global angle_gl_driver_selection_pkgs")
                    exec("settings delete global angle_gl_driver_selection_values")
                }
            }
        }
    }

    fun getPreload(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("preload_$packageName", false)

    fun setPreload(context: Context, packageName: String, enabled: Boolean) {
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean("preload_$packageName", enabled).apply()
    }

    fun getSmartCache(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("smartcache_$packageName", false)

    fun setSmartCache(context: Context, packageName: String, enabled: Boolean) {
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean("smartcache_$packageName", enabled).apply()
    }

    fun getTouchResponsive(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("touch_$packageName", false)

    fun setTouchResponsive(context: Context, packageName: String, enabled: Boolean) {
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean("touch_$packageName", enabled).apply()
    }

    fun getReduceLogging(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("reduce_logging_$packageName", false)

    fun setReduceLogging(context: Context, packageName: String, enabled: Boolean) {
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean("reduce_logging_$packageName", enabled).apply()
    }

    fun getNotifLimiter(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("notiflimit_$packageName", false)

    fun setNotifLimiter(context: Context, packageName: String, enabled: Boolean) {
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean("notiflimit_$packageName", enabled).apply()
    }

    fun getDataSaver(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("datasaver_$packageName", false)

    fun setDataSaver(context: Context, packageName: String, enabled: Boolean) {
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean("datasaver_$packageName", enabled).apply()
    }

    fun getBatteryOptimize(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("battopt_$packageName", false)

    fun setBatteryOptimize(context: Context, packageName: String, enabled: Boolean) {
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean("battopt_$packageName", enabled).apply()
    }

    fun getKillBackground(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("killbg_$packageName", false)

    suspend fun applyAdvancedTweaks(context: Context, packageName: String, isRooted: Boolean) {
        val prefs       = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        val preload     = prefs.getBoolean("preload_$packageName",         false)
        val smartCache  = prefs.getBoolean("smartcache_$packageName",      false)
        val touch       = prefs.getBoolean("touch_$packageName",           false)
        val reduceLog   = prefs.getBoolean("reduce_logging_$packageName",  false)
        val notifLimit  = prefs.getBoolean("notiflimit_$packageName",      false)
        val dataSaver   = prefs.getBoolean("datasaver_$packageName",       false)
        val battOpt     = prefs.getBoolean("battopt_$packageName",         false)

        if (preload) {
            exec("cmd device_config put activity_manager max_cached_processes 128")
            exec("cmd package compile -m speed-profile -f $packageName")
        }

        if (smartCache) exec("cmd package clear-cache $packageName")

        if (touch) {
            if (isRooted) {
                exec("echo 0 > /proc/sys/kernel/input_boost")
                exec("echo performance > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
                exec("echo 1 > /sys/class/kgsl/kgsl-3d0/force_gpu_on")
            } else {
                exec("settings put system pointer_speed 10")
                exec("cmd deviceidle disable")
            }
        } else {
            if (isRooted) {
                exec("echo 1 > /proc/sys/kernel/input_boost")
                exec("echo schedutil > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
                exec("echo 0 > /sys/class/kgsl/kgsl-3d0/force_gpu_on")
            } else {
                exec("settings put system pointer_speed 0")
                exec("cmd deviceidle enable")
            }
        }

        if (reduceLog) {
            exec("setprop log.tag.$packageName SUPPRESS")
            exec("setprop debug.atrace.tags.enableflags 0")
            exec("setprop persist.traced.enable 0")
        } else {
            exec("setprop log.tag.$packageName \"\"")
            exec("setprop debug.atrace.tags.enableflags 1")
            exec("setprop persist.traced.enable 1")
        }

        if (notifLimit) exec("appops set $packageName POST_NOTIFICATIONS ignore")
        else            exec("appops set $packageName POST_NOTIFICATIONS allow")

        if (dataSaver) {
            exec("cmd netpolicy set restrict-background-whitelist $packageName false")
            exec("appops set $packageName RUN_IN_BACKGROUND ignore")
        } else {
            exec("cmd netpolicy set restrict-background-whitelist $packageName true")
            exec("appops set $packageName RUN_IN_BACKGROUND allow")
        }

        if (battOpt) {
            exec("dumpsys deviceidle whitelist -$packageName")
            exec("appops set $packageName RUN_ANY_IN_BACKGROUND ignore")
        } else {
            exec("dumpsys deviceidle whitelist +$packageName")
            exec("appops set $packageName RUN_ANY_IN_BACKGROUND allow")
        }
    }

    suspend fun compileDexOpt(packageName: String, mode: String) {
        exec("cmd package compile -m $mode -f $packageName")
    }

    suspend fun applyStandbyBucket(packageName: String, bucket: String) {
        exec("am set-standby-bucket $packageName $bucket")
    }

    suspend fun trimMemory(packageName: String) {
        exec("am send-trim-memory $packageName RUNNING_CRITICAL")
        exec("am send-trim-memory $packageName COMPLETE")
    }

    suspend fun clearCacheApp(packageName: String) {
        exec("cmd package clear-cache $packageName")
    }

    suspend fun forceStopApp(packageName: String) {
        exec("am force-stop $packageName")
    }

    suspend fun applyCpuPriority(packageName: String, priority: String) {
        val pid = TweakExecutor.executeWithOutput("pidof $packageName 2>/dev/null || cat /proc/*/cmdline 2>/dev/null | tr '\\0' '\\n' | grep -Fx $packageName | head -1").trim()
        if (pid.isEmpty()) return
        val nice = when (priority) {
            "realtime" -> "-20"
            "high"     -> "-10"
            else       -> "0"
        }
        exec("renice $nice -p $pid 2>/dev/null")
        when (priority) {
            "realtime" -> exec("chrt -f -p 10 $pid 2>/dev/null")
            "high"     -> exec("chrt -f -p 5 $pid 2>/dev/null")
            else       -> exec("chrt -o -p 0 $pid 2>/dev/null")
        }
    }

    fun setCustomResolution(context: Context, packageName: String, width: Int, height: Int, dpi: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            if (width > 0 && height > 0) exec("wm size ${width}x${height}")
            if (dpi > 0) exec("wm density $dpi")
        }
    }

    fun resetResolution(context: Context, packageName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            exec("wm size reset")
            exec("wm density reset")
        }
    }

    fun setAppOp(context: Context, packageName: String, op: String, allow: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            exec("appops set $packageName $op ${if (allow) "allow" else "ignore"}")
        }
    }

    suspend fun getInstalledApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm      = context.packageManager
        val appList = mutableListOf<AppInfo>()
        try {
            val packages = pm.getInstalledPackages(0)
            for (pkg in packages) {
                try {
                    val appInfo = pkg.applicationInfo ?: continue
                    val isSystemApp    = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val hasLauncher    = pm.getLaunchIntentForPackage(pkg.packageName) != null
                    if (!isSystemApp || hasLauncher) {
                        val label = try { appInfo.loadLabel(pm).toString() } catch (e: Exception) { pkg.packageName }
                        val icon  = try { appInfo.loadIcon(pm) } catch (e: Exception) { pm.defaultActivityIcon }
                        appList.add(AppInfo(label, pkg.packageName, icon, getAppProfile(context, pkg.packageName)))
                    }
                } catch (e: Exception) { continue }
            }
        } catch (e: Exception) { return@withContext emptyList() }
        appList.sortedBy { it.name.lowercase() }
    }
}
