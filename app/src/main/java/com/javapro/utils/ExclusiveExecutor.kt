package com.javapro.utils

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ColorPreset(val name: String, val red: Float, val green: Float, val blue: Float, val sat: Float)
data class TweakProfile(val name: String, val createdAt: Long, val mainSnap: Map<String, Any>, val boostSnap: Map<String, Any>, val appProfileSnap: Map<String, Any>, val appOpsSnap: Map<String, Any>)

object ExclusiveExecutor {

    private const val KEY_PRESETS     = "color_presets_json"
    private const val KEY_PROFILES    = "tweak_profiles_json"
    private const val KEY_AUTO_TWEAK  = "auto_tweak_enabled"
    const val  KEY_BANNER_URI         = "custom_banner_uri"

    fun prefsToJson(p: SharedPreferences): JSONObject {
        val j = JSONObject()
        p.all.forEach { (k, v) ->
            when (v) {
                is Boolean -> j.put(k, v)
                is String  -> j.put(k, v)
                is Int     -> j.put(k, v)
                is Float   -> j.put(k, v.toDouble())
                is Long    -> j.put(k, v)
            }
        }
        return j
    }

    fun jsonToPrefs(j: JSONObject, p: SharedPreferences) {
        val ed = p.edit()
        j.keys().forEach { k ->
            when (val v = j.get(k)) {
                is Boolean -> ed.putBoolean(k, v)
                is String  -> ed.putString(k, v)
                is Int     -> ed.putInt(k, v)
                is Double  -> ed.putFloat(k, v.toFloat())
                is Long    -> ed.putLong(k, v)
            }
        }
        ed.commit()
    }

    private fun jsonToMap(j: JSONObject): Map<String, Any> {
        val m = mutableMapOf<String, Any>()
        j.keys().forEach { k -> m[k] = j.get(k) }
        return m
    }

    private fun mapToJson(m: Map<String, Any>): JSONObject {
        val j = JSONObject()
        m.forEach { (k, v) -> j.put(k, v) }
        return j
    }

    fun snapPrefs(p: SharedPreferences): Map<String, Any> {
        val m = mutableMapOf<String, Any>()
        p.all.forEach { (k, v) -> if (v != null) m[k] = v }
        return m
    }

    fun loadPresets(p: SharedPreferences): List<ColorPreset> {
        return try {
            val arr = JSONArray(p.getString(KEY_PRESETS, "[]") ?: "[]")
            (0 until arr.length()).map {
                arr.getJSONObject(it).run {
                    ColorPreset(getString("name"), getDouble("red").toFloat(), getDouble("green").toFloat(), getDouble("blue").toFloat(), getDouble("sat").toFloat())
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun savePresets(p: SharedPreferences, list: List<ColorPreset>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().apply { put("name", it.name); put("red", it.red); put("green", it.green); put("blue", it.blue); put("sat", it.sat) }) }
        p.edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    fun loadProfiles(p: SharedPreferences): List<TweakProfile> {
        return try {
            val arr = JSONArray(p.getString(KEY_PROFILES, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TweakProfile(
                    o.getString("name"), o.getLong("createdAt"),
                    jsonToMap(o.getJSONObject("main")),
                    jsonToMap(o.getJSONObject("boost")),
                    jsonToMap(o.optJSONObject("appProfile") ?: JSONObject()),
                    jsonToMap(o.optJSONObject("appOps") ?: JSONObject())
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun saveProfiles(p: SharedPreferences, list: List<TweakProfile>) {
        val arr = JSONArray()
        list.forEach { tp ->
            arr.put(JSONObject().apply {
                put("name", tp.name); put("createdAt", tp.createdAt)
                put("main", mapToJson(tp.mainSnap)); put("boost", mapToJson(tp.boostSnap))
                put("appProfile", mapToJson(tp.appProfileSnap)); put("appOps", mapToJson(tp.appOpsSnap))
            })
        }
        p.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun buildBackupJson(mp: SharedPreferences, bp: SharedPreferences, ap: SharedPreferences, op: SharedPreferences): String {
        return JSONObject().apply {
            put("version",           3)
            put("timestamp",         System.currentTimeMillis())
            put("javapro_settings",  prefsToJson(mp))
            put("game_boost_prefs",  prefsToJson(bp))
            put("app_profile_prefs", prefsToJson(ap))
            put("app_ops_prefs",     prefsToJson(op))
        }.toString(2)
    }

    fun applyBackupJson(
        mp: SharedPreferences, bp: SharedPreferences,
        ap: SharedPreferences, op: SharedPreferences,
        pm: PreferenceManager, json: String
    ): Boolean {
        return try {
            val root = JSONObject(json)
            if (root.has("javapro_settings"))  jsonToPrefs(root.getJSONObject("javapro_settings"),  mp)
            if (root.has("game_boost_prefs"))  jsonToPrefs(root.getJSONObject("game_boost_prefs"),  bp)
            if (root.has("app_profile_prefs")) jsonToPrefs(root.getJSONObject("app_profile_prefs"), ap)
            if (root.has("app_ops_prefs"))     jsonToPrefs(root.getJSONObject("app_ops_prefs"),     op)
            val s = root.optJSONObject("javapro_settings") ?: JSONObject()
            (s.opt("dark_mode")     as? Boolean)?.let { pm.setDarkMode(it) }
            (s.opt("lang")          as? String)?.let  { pm.setLanguage(it) }
            (s.opt("boot_apply")    as? Boolean)?.let { pm.setBootApply(it) }
            (s.opt("fps_enabled")   as? Boolean)?.let { pm.setFpsEnabled(it) }
            (s.opt("fps_mode")      as? String)?.let  { pm.setFpsMode(it) }
            (s.opt("scale_val")     as? Double)?.let  { pm.setScale(it.toFloat()) }
            (s.opt("res_confirmed") as? Boolean)?.let { pm.setResConfirmed(it) }
            val r = (s.opt("red_val")   as? Double)?.toFloat()
            val g = (s.opt("green_val") as? Double)?.toFloat()
            val b = (s.opt("blue_val")  as? Double)?.toFloat()
            if (r != null && g != null && b != null) pm.setRGB(r, g, b)
            (s.opt("sat_val") as? Double)?.let { pm.setSat(it.toFloat()) }
            true
        } catch (e: Exception) { false }
    }

    fun restoreProfile(
        tp: TweakProfile,
        mp: SharedPreferences, bp: SharedPreferences,
        ap: SharedPreferences, op: SharedPreferences,
        pm: PreferenceManager
    ) {
        fun restore(snap: Map<String, Any>, p: SharedPreferences) {
            val ed = p.edit()
            snap.forEach { (k, v) ->
                when (v) {
                    is Boolean -> ed.putBoolean(k, v)
                    is String  -> ed.putString(k, v)
                    is Int     -> ed.putInt(k, v)
                    is Float   -> ed.putFloat(k, v)
                    is Double  -> ed.putFloat(k, v.toFloat())
                    is Long    -> ed.putLong(k, v)
                }
            }
            ed.commit()
        }
        restore(tp.mainSnap, mp); restore(tp.boostSnap, bp)
        restore(tp.appProfileSnap, ap); restore(tp.appOpsSnap, op)
        (tp.mainSnap["dark_mode"]     as? Boolean)?.let { pm.setDarkMode(it) }
        (tp.mainSnap["lang"]          as? String)?.let  { pm.setLanguage(it) }
        (tp.mainSnap["boot_apply"]    as? Boolean)?.let { pm.setBootApply(it) }
        (tp.mainSnap["fps_enabled"]   as? Boolean)?.let { pm.setFpsEnabled(it) }
        (tp.mainSnap["fps_mode"]      as? String)?.let  { pm.setFpsMode(it) }
        (tp.mainSnap["scale_val"]     as? Float)?.let   { pm.setScale(it) }
        (tp.mainSnap["res_confirmed"] as? Boolean)?.let { pm.setResConfirmed(it) }
        val r = tp.mainSnap["red_val"]   as? Float
        val g = tp.mainSnap["green_val"] as? Float
        val b = tp.mainSnap["blue_val"]  as? Float
        if (r != null && g != null && b != null) pm.setRGB(r, g, b)
        (tp.mainSnap["sat_val"] as? Float)?.let { pm.setSat(it) }
    }

    fun readJsonFromUri(context: Context, uri: Uri): String? {
        return try { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { null }
    }

    fun readGameList(context: Context): Set<String> {
        return try {
            context.assets.open("game.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
            }
        } catch (e: Exception) { emptySet() }
    }

    fun getForeground(context: Context): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 5000L, now)
                ?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: Exception) { null }
    }

    fun hasBoostConfig(bp: SharedPreferences, pkg: String): Boolean {
        val keys = listOf("killbg","prioritize","anim","touch","gamemode","memopt","thermal","cpugpu","uclamp","io","vm","sched","net","doze","lmk","wifi","lockfps")
        return keys.any { bp.getBoolean("${it}_$pkg", false) } ||
               (bp.getString("scale_$pkg", "disable") ?: "disable") != "disable"
    }

    suspend fun applyBoostForPkg(bp: SharedPreferences, pkg: String, rooted: Boolean) {
        fun b(k: String) = bp.getBoolean("${k}_$pkg", false)
        fun s(k: String, d: String) = bp.getString("${k}_$pkg", d) ?: d
        if (b("killbg"))     TweakExecutor.execute("am kill-all")
        if (b("memopt"))     TweakExecutor.execute("echo 3 > /proc/sys/vm/drop_caches")
        if (b("gamemode"))   TweakExecutor.execute("cmd game mode 2 $pkg")
        if (b("anim"))  {
            TweakExecutor.execute("settings put global window_animation_scale 0.5")
            TweakExecutor.execute("settings put global transition_animation_scale 0.5")
            TweakExecutor.execute("settings put global animator_duration_scale 0.5")
        }
        if (b("touch"))      TweakExecutor.execute("settings put system pointer_speed 7")
        if (b("prioritize")) {
            TweakExecutor.execute("cmd game set --mode 2 $pkg")
            TweakExecutor.execute("cmd device_config put game_overlay \"$pkg\" \"mode=2,loadingBoost=999999999\"")
        }
        if (!rooted) return
        if (b("thermal"))  {
            TweakExecutor.execute("for i in /sys/class/thermal/thermal_zone*; do [ -f \"\$i/mode\" ] && echo disabled > \"\$i/mode\"; done")
            TweakExecutor.execute("stop thermal-engine 2>/dev/null; stop thermald 2>/dev/null")
        }
        if (b("cpugpu"))   {
            TweakExecutor.execute("for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo performance > \$g 2>/dev/null; done")
            TweakExecutor.execute("for m in /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq; do d=\$(dirname \$m); cat \$m > \$d/scaling_max_freq; cat \$m > \$d/scaling_min_freq; done")
        }
        if (b("uclamp"))   {
            TweakExecutor.execute("echo 100 > /dev/cpuctl/top-app/cpu.uclamp.min 2>/dev/null")
            TweakExecutor.execute("echo 100 > /dev/cpuctl/top-app/cpu.uclamp.max 2>/dev/null")
        }
        if (b("io"))        TweakExecutor.execute("for q in /sys/block/*/queue; do echo none > \$q/scheduler 2>/dev/null; echo 0 > \$q/iostats 2>/dev/null; done")
        if (b("vm"))       {
            TweakExecutor.execute("echo 0 > /proc/sys/vm/swappiness")
            TweakExecutor.execute("echo 10 > /proc/sys/vm/vfs_cache_pressure")
        }
        if (b("sched"))    {
            TweakExecutor.execute("echo 1 > /proc/sys/kernel/sched_child_runs_first")
            TweakExecutor.execute("echo 0 > /proc/sys/kernel/sched_autogroup_enabled")
        }
        if (b("net"))      {
            TweakExecutor.execute("echo 1 > /proc/sys/net/ipv4/tcp_low_latency")
            TweakExecutor.execute("echo 1 > /proc/sys/net/ipv4/tcp_fastopen")
        }
        if (b("wifi"))     {
            TweakExecutor.execute("settings put global wifi_sleep_policy 2")
            TweakExecutor.execute("settings put global wifi_scan_throttle_enabled 0")
        }
        if (b("doze"))      TweakExecutor.execute("dumpsys deviceidle disable")
        if (b("lmk"))       TweakExecutor.execute("echo 0,0,0,0,0,0 > /sys/module/lowmemorykiller/parameters/adj 2>/dev/null")
        val scale = s("scale", "disable"); val fps = s("fps", "60")
        if (scale != "disable") {
            TweakExecutor.execute("cmd game downscale $scale $pkg")
            TweakExecutor.execute("cmd device_config put game_overlay \"$pkg\" \"mode=2,downscaleFactor=$scale,fps=$fps\"")
        }
        if (b("lockfps"))  {
            TweakExecutor.execute("settings put system peak_refresh_rate $fps.0")
            TweakExecutor.execute("settings put system min_refresh_rate $fps.0")
        }
    }

    suspend fun applyNetPreset(commands: List<String>) {
        withContext(Dispatchers.IO) { commands.forEach { TweakExecutor.execute(it) } }
    }

    suspend fun applyColorPreset(red: Float, green: Float, blue: Float, sat: Float, rooted: Boolean) {
        if (rooted) withContext(Dispatchers.IO) { TweakExecutor.applyColorModifier(red, green, blue, sat) }
    }


    suspend fun fetchLogcat(pkg: String?, lines: Int = 500, isRoot: Boolean): List<String> = withContext(Dispatchers.IO) {
        try {
            val cmd = buildString {
                append("logcat -d -t $lines")
                if (!pkg.isNullOrBlank()) append(" | grep -i \"$pkg\"")
            }
            val raw = TweakExecutor.executeWithOutput(cmd)
            if (raw.isNullOrBlank()) return@withContext listOf("(no output)")
            raw.lines().filter { it.isNotBlank() }
        } catch (e: Exception) { listOf("Error: ${e.message}") }
    }

    suspend fun fetchCrashDump(lines: Int = 500): List<String> = withContext(Dispatchers.IO) {
        try {
            val keywords = "FATAL|AndroidRuntime|ANR in|beginning of crash|Process.*has died|SIGSEGV|SIGABRT|E AndroidRuntime"
            val raw = TweakExecutor.executeWithOutput("logcat -d -t $lines | grep -E \"$keywords\"")
            if (raw.isNullOrBlank()) return@withContext emptyList()
            raw.lines().filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
    }

    fun fetchPackageInfo(context: Context, pkg: String): Map<String, String> {
        return try {
            val pm      = context.packageManager
            val pkgInfo = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            val appInfo = pkgInfo.applicationInfo ?: return emptyMap()
            val result  = mutableMapOf<String, String>()
            result["Package"]       = pkg
            result["App Name"]      = pm.getApplicationLabel(appInfo).toString()
            result["Version Name"]  = pkgInfo.versionName ?: "-"
            result["Version Code"]  = if (android.os.Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode.toString() else @Suppress("DEPRECATION") pkgInfo.versionCode.toString()
            result["Target SDK"]    = appInfo.targetSdkVersion.toString()
            result["Min SDK"]       = if (android.os.Build.VERSION.SDK_INT >= 24) appInfo.minSdkVersion.toString() else "-"
            result["Install Date"]  = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(pkgInfo.firstInstallTime))
            result["Last Updated"]  = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(pkgInfo.lastUpdateTime))
            result["Install Path"]  = appInfo.sourceDir ?: "-"
            result["Data Path"]     = appInfo.dataDir ?: "-"
            result["System App"]    = if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) "Yes" else "No"
            result["Debuggable"]    = if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) "Yes" else "No"
            val perms = pkgInfo.requestedPermissions
            if (!perms.isNullOrEmpty()) {
                result["Permissions (${perms.size})"] = perms.joinToString("\n") { it.removePrefix("android.permission.") }
            }
            result
        } catch (e: Exception) { emptyMap() }
    }

    fun fetchBuildInfo(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        result["Brand"]            = android.os.Build.BRAND
        result["Manufacturer"]     = android.os.Build.MANUFACTURER
        result["Model"]            = android.os.Build.MODEL
        result["Device"]           = android.os.Build.DEVICE
        result["Product"]          = android.os.Build.PRODUCT
        result["Hardware"]         = android.os.Build.HARDWARE
        result["Board"]            = android.os.Build.BOARD
        result["Bootloader"]       = android.os.Build.BOOTLOADER
        result["Radio (Baseband)"] = android.os.Build.getRadioVersion() ?: "-"
        result["Android Version"]  = android.os.Build.VERSION.RELEASE
        result["API Level"]        = android.os.Build.VERSION.SDK_INT.toString()
        result["Security Patch"]   = if (android.os.Build.VERSION.SDK_INT >= 23) android.os.Build.VERSION.SECURITY_PATCH else "-"
        result["Build ID"]         = android.os.Build.ID
        result["Build Type"]       = android.os.Build.TYPE
        result["Build Tags"]       = android.os.Build.TAGS
        result["Fingerprint"]      = android.os.Build.FINGERPRINT
        result["ABI"]              = android.os.Build.SUPPORTED_ABIS.joinToString(", ")
        result["CPU Cores"]        = Runtime.getRuntime().availableProcessors().toString()
        result["Host"]             = android.os.Build.HOST
        result["User"]             = android.os.Build.USER
        return result
    }

    suspend fun fetchDmesg(): List<String> = withContext(Dispatchers.IO) {
        try {
            val raw = TweakExecutor.executeWithOutput("dmesg | tail -n 300")
            if (raw.isBlank()) return@withContext listOf("(no dmesg output — root required)")
            raw.lines().filter { it.isNotBlank() }
        } catch (e: Exception) { listOf("Error: ${e.message}") }
    }

    suspend fun fetchRunningProcesses(pkg: String?): List<String> = withContext(Dispatchers.IO) {
        try {
            val cmd = if (!pkg.isNullOrBlank()) "ps -A | grep -i \"$pkg\"" else "ps -A | head -80"
            val raw = TweakExecutor.executeWithOutput(cmd)
            if (raw.isBlank()) return@withContext listOf("(no process info)")
            raw.lines().filter { it.isNotBlank() }
        } catch (e: Exception) { listOf("Error: ${e.message}") }
    }

    fun fetchAppMemoryInfo(context: Context, pkg: String): Map<String, String> {
        return try {
            val am  = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val pid = try { am.runningAppProcesses?.firstOrNull { it.processName == pkg }?.pid } catch (e: Exception) { null }
            val result = mutableMapOf<String, String>()
            if (pid != null) {
                val memInfo = am.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()
                if (memInfo != null) {
                    result["PSS Total"]   = "${memInfo.totalPss / 1024} MB"
                    result["Private RSS"] = "${memInfo.totalPrivateDirty / 1024} MB"
                    result["Shared RSS"]  = "${memInfo.totalSharedDirty / 1024} MB"
                }
                result["PID"] = pid.toString()
            }
            val usm     = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now     = System.currentTimeMillis()
            val stats   = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000L, now)
            val appStat = stats?.firstOrNull { it.packageName == pkg }
            if (appStat != null) {
                result["Last Used"]       = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(appStat.lastTimeUsed))
                result["Foreground Time"] = "${appStat.totalTimeInForeground / 1000}s today"
            }
            result
        } catch (e: Exception) { mapOf("Error" to (e.message ?: "unknown")) }
    }

    fun exportLogToFile(context: Context, lines: List<String>, fileName: String): Boolean {
        return try {
            val ts   = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val dir  = (context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir).also { it.mkdirs() }
            val file = File(dir, "${fileName}_$ts.txt")
            file.writeText(if (lines.isEmpty()) "(empty log)" else lines.joinToString("\n"))
            file.exists() && file.length() > 0
        } catch (e: Exception) { false }
    }

    fun shareLogAsText(context: Context, lines: List<String>, fileName: String) {
        val ts     = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${fileName}_$ts.txt")
            putExtra(Intent.EXTRA_TEXT, if (lines.isEmpty()) "(empty log)" else lines.joinToString("\n"))
        }
        context.startActivity(Intent.createChooser(intent, "Share Log"))
    }

    fun getExportedLogPath(context: Context): String {
        return (context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir).absolutePath
    }

    fun writeBackupToUri(context: Context, uri: Uri, json: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            true
        } catch (e: Exception) { false }
    }

    suspend fun freezeApp(pkg: String): Boolean = withContext(Dispatchers.IO) {
        try {
            TweakExecutor.execute("pm disable-user --user 0 $pkg")
            true
        } catch (e: Exception) { false }
    }

    suspend fun unfreezeApp(pkg: String): Boolean = withContext(Dispatchers.IO) {
        try {
            TweakExecutor.execute("pm enable $pkg")
            true
        } catch (e: Exception) { false }
    }

    fun isAppFrozen(context: Context, pkg: String): Boolean {
        return try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            !info.enabled
        } catch (e: Exception) { false }
    }

    fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: Exception) { false }
    }
}
