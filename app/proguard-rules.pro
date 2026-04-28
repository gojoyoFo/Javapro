# ═══════════════════════════════════════════════════════════════════════
# proguard-rules.pro  —  JavaPro Anti-Piracy Obfuscation Config
# ═══════════════════════════════════════════════════════════════════════

# ── Agresifkan obfuscation ────────────────────────────────────────────
-repackageclasses ''
-allowaccessmodification
-overloadaggressively

# Sembunyikan nama file sumber di stack trace
-renamesourcefileattribute x

# Hapus semua log di release build
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# ── HONEYPOT: Umpan palsu untuk modder ───────────────────────────────
# Nama-nama ini sengaja dijaga agar mudah ditemukan di smali
# Tapi semua nilai hardcoded false — mengubahnya tidak akan berpengaruh
-keepclassmembers class com.javapro.utils.PremiumManager {
    public static volatile boolean isPremiumUnlocked;
    public static volatile boolean checkVipStatus;
    public static volatile boolean hasLicense;
    public static volatile boolean premiumOverride;
    public static volatile boolean vipMode;
    public static volatile boolean unlockAll;
}

# ── PremiumManager: Obfuscate SEMUA method internal ──────────────────
# Hanya jaga method yang dipanggil lewat reflection atau dari luar package
-keepclassmembers class com.javapro.utils.PremiumManager {
    public static java.lang.String getDeviceId(android.content.Context);
    public static long getExpiryMs(android.content.Context);
    public static java.lang.String getPremiumType(android.content.Context);
}
# isPremium, isRealPremium, checkOnline, verifySignature, hmacSha256,
# saveCache → TIDAK di-keep → nama diobfuscate oleh R8

# ── SecurityGuard: Hapus debug helpers di release ────────────────────
-assumenosideeffects class com.javapro.security.SecurityGuard {
    public static java.lang.String debugGetCertHash(android.content.Context);
    public static java.lang.String debugEncodeHash(java.lang.String);
}

# ── Jaga komponen Android yang wajib ada ─────────────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ── Shizuku ──────────────────────────────────────────────────────────
-keep class com.javapro.utils.ShizukuUserService { *; }
-keep interface com.javapro.IShizukuService { *; }
-keep class com.javapro.IShizukuService$* { *; }

# ── Jetpack Compose ───────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ── Unity Ads ────────────────────────────────────────────────────────
-keep class com.unity3d.ads.** { *; }
-keep interface com.unity3d.ads.** { *; }

# ── EncryptedSharedPreferences ───────────────────────────────────────
-keep class androidx.security.crypto.** { *; }

# ── Navigation Compose ───────────────────────────────────────────────
-keep class androidx.navigation.** { *; }

# ── Coroutines ───────────────────────────────────────────────────────
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── Kotlin metadata ──────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# ── Suppress warning ─────────────────────────────────────────────────
-dontwarn com.google.errorprone.annotations.**
-keep class com.google.errorprone.annotations.** { *; }
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn rikka.shizuku.**

# ── Libsu ────────────────────────────────────────────────────────────
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**
