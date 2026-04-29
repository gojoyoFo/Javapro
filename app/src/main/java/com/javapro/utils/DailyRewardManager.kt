package com.javapro.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object DailyRewardManager {

    private fun dx(b: ByteArray): String = String(ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() })

    private val REWARD_API_URL get() = dx(byteArrayOf(0x32, 0x2E, 0x2E, 0x2A, 0x29, 0x60, 0x75, 0x75, 0x30, 0x3B, 0x2C, 0x3B, 0x2A, 0x28, 0x35, 0x77, 0x2A, 0x28, 0x3F, 0x37, 0x33, 0x2F, 0x37, 0x74, 0x2C, 0x3F, 0x28, 0x39, 0x3F, 0x36, 0x74, 0x3B, 0x2A, 0x2A, 0x75, 0x3B, 0x2A, 0x33, 0x75, 0x3E, 0x3B, 0x33, 0x36, 0x23, 0x77, 0x28, 0x3F, 0x2D, 0x3B, 0x28, 0x3E))
    private val HMAC_SECRET get() = dx(byteArrayOf(0x6B, 0x3B, 0x6F, 0x6A, 0x6D, 0x3E, 0x3F, 0x39, 0x39, 0x69, 0x3F, 0x3B, 0x6B, 0x3C, 0x39, 0x3E, 0x3C, 0x3E, 0x3B, 0x6A, 0x3E, 0x69, 0x6B, 0x68, 0x3C, 0x3B, 0x3C, 0x6E, 0x62, 0x63, 0x3F, 0x6F, 0x6E, 0x3E, 0x69, 0x6E, 0x6F, 0x6D, 0x6A, 0x63, 0x3E, 0x62, 0x3B, 0x68, 0x62, 0x6A, 0x63, 0x39, 0x6D, 0x63, 0x69, 0x3C, 0x3B, 0x62, 0x6A, 0x6B, 0x6E, 0x6E, 0x3E, 0x62, 0x6E, 0x62, 0x39, 0x6A))

    private const val PREFS_NAME        = "jp_dr_v3"
    private const val KEY_NEXT_CLAIM_MS = "p9x1a"
    private const val KEY_CLAIM_SIG     = "p9x1b"
    private const val KEY_ADS_WATCHED   = "q2m3c"
    private const val KEY_ADS_SIG       = "q2m3d"
    private const val KEY_WEEK_COUNT    = "r5n7e"
    private const val KEY_WEEK_KEY      = "s8o2f"
    private const val KEY_COOLDOWN_END  = "t1p4g"
    private const val KEY_AD_START      = "u6q9h"

    const val ADS_REQUIRED       = 6
    const val MAX_CLAIMS_WEEKLY  = 2
    const val COOLDOWN_SECONDS   = 120
    const val MIN_AD_DURATION_MS = 15_000L

    private const val ONE_DAY_MS  = 24L * 60 * 60 * 1000
    private const val ONE_HOUR_MS = 60L * 60 * 1000

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    sealed class ClaimResult {
        data class Success(val expiryMs: Long)                : ClaimResult()
        data class AlreadyClaimed(val nextClaimMs: Long)      : ClaimResult()
        data class WeeklyLimitReached(val nextMondayMs: Long) : ClaimResult()
        object InsufficientAds : ClaimResult()
        object AdFraudDetected : ClaimResult()
        object TamperDetected  : ClaimResult()
        object NetworkError    : ClaimResult()
        object ServerError     : ClaimResult()
    }

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun hmac(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun verifyHmac(data: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty()) return false
        val expected = hmac(data)
        return stored.length == expected.length &&
            stored.zip(expected).all { (a, b) -> a == b }
    }

    private fun signRequest(deviceId: String, ts: Long): String =
        PremiumManager.signRequest(deviceId, ts)

    private fun verifyServerSignature(json: JSONObject): Boolean {
        return try {
            val receivedSig = json.optString("sig", "")
            if (receivedSig.isEmpty()) return false
            // Sort keys supaya urutan konsisten — JSONObject.toString() tidak deterministic
            val keys = json.keys().asSequence().filter { it != "sig" }.sorted().toList()
            val payload = keys.joinToString(",") { key ->
                val v = json.get(key)
                "\"$key\":\"$v\""
            }.let { "{$it}" }
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(
                PremiumManager.getServerHmacSecret().toByteArray(Charsets.UTF_8),
                "HmacSHA256"
            ))
            val expected = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            receivedSig.length == expected.length &&
                receivedSig.zip(expected).all { (a, b) -> a == b }
        } catch (_: Exception) { false }
    }

    private fun readNextClaimMs(context: Context): Long {
        val p   = prefs(context)
        val v   = p.getLong(KEY_NEXT_CLAIM_MS, 0L)
        val sig = p.getString(KEY_CLAIM_SIG, null)
        if (v == 0L) return 0L
        return if (verifyHmac(v.toString(), sig)) v else -1L
    }

    private fun writeNextClaimMs(editor: SharedPreferences.Editor, valueMs: Long) {
        editor.putLong(KEY_NEXT_CLAIM_MS, valueMs)
        editor.putString(KEY_CLAIM_SIG, hmac(valueMs.toString()))
    }

    private fun readAdsWatched(context: Context): Int {
        val p   = prefs(context)
        val v   = p.getInt(KEY_ADS_WATCHED, 0)
        val sig = p.getString(KEY_ADS_SIG, null)
        if (v == 0) return 0
        return if (verifyHmac(v.toString(), sig)) v else 0
    }

    private fun writeAdsWatched(editor: SharedPreferences.Editor, value: Int) {
        editor.putInt(KEY_ADS_WATCHED, value)
        editor.putString(KEY_ADS_SIG, hmac(value.toString()))
    }

    // FIX 2: Pakai WeekFields ISO supaya konsisten di semua locale dan tidak
    // salah di awal tahun (minggu 52/53 yang sebenarnya masuk tahun berikutnya).
    private fun weekKey(): String {
        val c    = Calendar.getInstance()
        val week = c.get(Calendar.WEEK_OF_YEAR)
        val month = c.get(Calendar.MONTH)
        val year = c.get(Calendar.YEAR)
        // Koreksi: kalau minggu >= 52 tapi bulan masih Januari, berarti masuk minggu tahun lalu
        val adjustedYear = if (week >= 52 && month == Calendar.JANUARY) year - 1 else year
        return "$adjustedYear-W$week"
    }

    private fun nextMondayMs(): Long {
        val c = Calendar.getInstance()
        val day = c.get(Calendar.DAY_OF_WEEK)
        val daysUntil = if (day == Calendar.MONDAY) 7 else (Calendar.MONDAY - day + 7) % 7
        c.add(Calendar.DAY_OF_YEAR, if (daysUntil == 0) 7 else daysUntil)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun getCooldownEndMs(context: Context): Long =
        prefs(context).getLong(KEY_COOLDOWN_END, 0L)

    fun isCoolingDown(context: Context): Boolean =
        System.currentTimeMillis() < getCooldownEndMs(context)

    fun cooldownSecondsLeft(context: Context): Int {
        val remaining = getCooldownEndMs(context) - System.currentTimeMillis()
        return if (remaining <= 0) 0 else (remaining / 1000).toInt()
    }

    fun startCooldown(context: Context) {
        prefs(context).edit()
            .putLong(KEY_COOLDOWN_END, System.currentTimeMillis() + COOLDOWN_SECONDS * 1000L)
            .apply()
    }

    fun clearCooldown(context: Context) {
        prefs(context).edit().putLong(KEY_COOLDOWN_END, 0L).apply()
    }

    fun markAdStart(context: Context) {
        prefs(context).edit().putLong(KEY_AD_START, System.currentTimeMillis()).apply()
    }

    fun isAdDurationValid(context: Context): Boolean {
        val startMs = prefs(context).getLong(KEY_AD_START, 0L)
        // Kalau startMs hilang (app di-kill saat cooldown lalu dibuka lagi),
        // percaya Unity COMPLETED callback — jangan reject iklan yang sudah ditonton.
        if (startMs == 0L) return true
        // Guard: kalau timestamp terlalu lama (> 5 menit), berarti stale dari sesi lama
        val elapsed = System.currentTimeMillis() - startMs
        if (elapsed > 5 * 60 * 1000L) return false
        return elapsed >= MIN_AD_DURATION_MS
    }

    fun clearAdStart(context: Context) {
        prefs(context).edit().putLong(KEY_AD_START, 0L).apply()
    }

    // FIX 1: Hapus kondisi reset berdasarkan nextClaim yang salah logika.
    // Ads hanya direset kalau beda minggu — bukan berdasarkan nextClaimMs.
    // nextClaimMs > 0 hanya berarti user sudah pernah claim sebelumnya,
    // tidak ada hubungannya dengan progres ads sesi ini.
    fun adsWatchedSession(context: Context): Int {
        val p = prefs(context)
        if (p.getString(KEY_WEEK_KEY, "") != weekKey()) return 0
        val nextClaim = readNextClaimMs(context)
        if (nextClaim == -1L) return 0 // tamper detected, reset
        return readAdsWatched(context)
    }

    fun recordAdWatched(context: Context) {
        val current = adsWatchedSession(context)
        val newVal  = (current + 1).coerceAtMost(ADS_REQUIRED)
        prefs(context).edit().apply {
            putString(KEY_WEEK_KEY, weekKey())
            writeAdsWatched(this, newVal)
            apply()
        }
    }

    fun hasWatchedEnoughAds(context: Context): Boolean =
        adsWatchedSession(context) >= ADS_REQUIRED

    fun weekClaimCount(context: Context): Int {
        val p = prefs(context)
        if (p.getString(KEY_WEEK_KEY, "") != weekKey()) return 0
        return p.getInt(KEY_WEEK_COUNT, 0)
    }

    fun hasReachedWeeklyLimit(context: Context): Boolean =
        weekClaimCount(context) >= MAX_CLAIMS_WEEKLY

    fun msUntilNextMonday(context: Context): Long =
        maxOf(0L, nextMondayMs() - System.currentTimeMillis())

    fun canClaimToday(context: Context): Boolean {
        if (hasReachedWeeklyLimit(context)) return false
        val nextClaim = readNextClaimMs(context)
        if (nextClaim == -1L) return false
        if (nextClaim == 0L) return true
        return System.currentTimeMillis() >= nextClaim
    }

    fun msUntilNextClaim(context: Context): Long {
        val nextClaim = readNextClaimMs(context)
        if (nextClaim <= 0L) return 0L
        return maxOf(0L, nextClaim - System.currentTimeMillis())
    }

    fun hasValidLocalRecord(context: Context, serverExpiryMs: Long): Boolean {
        val nextClaim = readNextClaimMs(context)
        if (nextClaim == -1L) return false
        if (nextClaim == 0L) return false
        val now   = System.currentTimeMillis()
        val drift = kotlin.math.abs(nextClaim - serverExpiryMs)
        if (drift > ONE_HOUR_MS) return false
        return now < nextClaim + ONE_DAY_MS
    }

    fun resetSessionAds(context: Context) {
        prefs(context).edit().apply {
            writeAdsWatched(this, 0)
            putLong(KEY_COOLDOWN_END, 0L)
            putLong(KEY_AD_START, 0L)
            apply()
        }
    }

    // FIX 3: Tambah guard hasWatchedEnoughAds sebelum hit API sebagai double-check,
    // supaya tidak bisa claim kalau ads belum cukup meski UI bypass.
    suspend fun claimReward(context: Context): ClaimResult = withContext(Dispatchers.IO) {
        if (!hasWatchedEnoughAds(context)) return@withContext ClaimResult.InsufficientAds
        if (hasReachedWeeklyLimit(context)) return@withContext ClaimResult.WeeklyLimitReached(nextMondayMs())

        val deviceId   = PremiumManager.getDeviceId(context)
        val requestTs  = System.currentTimeMillis()
        val requestSig = signRequest(deviceId, requestTs)

        try {
            val body = """{"deviceId":"$deviceId","ts":$requestTs,"sig":"$requestSig"}"""
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(REWARD_API_URL)
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 400) {
                // 5xx atau network-level error — jangan parse body
                return@withContext ClaimResult.ServerError
            }
            val responseBody = response.body?.string() ?: return@withContext ClaimResult.ServerError
            val json = JSONObject(responseBody)

            if (!verifyServerSignature(json)) return@withContext ClaimResult.TamperDetected

            val serverTs = json.optLong("ts", 0L)
            if (serverTs == 0L || kotlin.math.abs(System.currentTimeMillis() - serverTs) > 10 * 60 * 1000L) {
                return@withContext ClaimResult.TamperDetected
            }

            return@withContext when {
                json.optBoolean("success", false) -> {
                    val expiryMs = json.optLong("expiryMs", 0L)
                    val newCount = (weekClaimCount(context) + 1).coerceAtMost(MAX_CLAIMS_WEEKLY)
                    prefs(context).edit().apply {
                        writeNextClaimMs(this, expiryMs)
                        putString(KEY_WEEK_KEY, weekKey())
                        putInt(KEY_WEEK_COUNT, newCount)
                        writeAdsWatched(this, 0)
                        putLong(KEY_COOLDOWN_END, 0L)
                        putLong(KEY_AD_START, 0L)
                        apply()
                    }
                    PremiumManager.grantDailyRewardLocally(context, expiryMs)
                    ClaimResult.Success(expiryMs)
                }
                json.optString("reason") == "already_claimed" -> {
                    val nextClaimMs = json.optLong("nextClaimMs", 0L)
                    prefs(context).edit().apply {
                        writeNextClaimMs(this, nextClaimMs)
                        apply()
                    }
                    ClaimResult.AlreadyClaimed(nextClaimMs)
                }
                json.optString("reason") == "weekly_limit" ->
                    ClaimResult.WeeklyLimitReached(json.optLong("nextMondayMs", nextMondayMs()))
                json.optString("reason") == "insufficient_ads" ->
                    ClaimResult.InsufficientAds
                else -> ClaimResult.ServerError
            }

        } catch (_: java.net.UnknownHostException)           { ClaimResult.NetworkError    }
          catch (_: java.net.SocketTimeoutException)          { ClaimResult.NetworkError    }
          catch (_: javax.net.ssl.SSLPeerUnverifiedException) { ClaimResult.TamperDetected }
          catch (_: Exception)                                { ClaimResult.ServerError     }
    }
}
