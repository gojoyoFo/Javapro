package com.javapro.ads

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.javapro.ui.screens.AdWatchResult
import com.javapro.utils.PremiumManager
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.metadata.MetaData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AdManager {

    private const val TAG              = "AdManager"
    const val  GAME_ID                 = "6087951"
    private const val PLACEMENT_ID     = "Rewarded_Android"
    private const val PREFS_NAME       = "javapro_ad_prefs"
    private const val SLOT_COOLDOWN_MS = 10 * 1000L
    private const val LOAD_TIMEOUT_MS  = 15 * 1000L
    private const val RELOAD_DELAY_MS  = 3 * 1000L

    const val SLOT_GAMELIST     = "slot_gamelist"
    const val SLOT_APPPROFILE   = "slot_appprofile"
    const val SLOT_ADVANCED     = "slot_advanced"
    const val SLOT_GENERAL      = "slot_general"
    const val SLOT_EXCLUSIVE    = "slot_exclusive"
    const val SLOT_DAILY_REWARD = "slot_daily_reward"

    var isShowingAd = false
        private set

    private var isAdReady    = false
    private var isLoading    = false
    private var timeoutJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        prefs(context).edit().clear().apply()
        isShowingAd = false
        isAdReady   = false
        isLoading   = false
        Log.d(TAG, "AdManager initialized.")
        preload()
    }

    fun preload() {
        if (isAdReady || isLoading || isShowingAd) return
        isLoading = true
        Log.d(TAG, "Preloading ad...")

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (isLoading) {
                isLoading = false
                Log.w(TAG, "Preload timeout. Retrying in ${RELOAD_DELAY_MS / 1000}s...")
                delay(RELOAD_DELAY_MS)
                preload()
            }
        }

        UnityAds.load(PLACEMENT_ID, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String) {
                timeoutJob?.cancel()
                isLoading = false
                isAdReady = true
                Log.d(TAG, "Ad preloaded and ready.")
            }

            override fun onUnityAdsFailedToLoad(
                placementId : String,
                error       : UnityAds.UnityAdsLoadError,
                message     : String
            ) {
                timeoutJob?.cancel()
                isLoading = false
                isAdReady = false
                Log.w(TAG, "Preload failed: $message. Retrying in ${RELOAD_DELAY_MS / 1000}s...")
                scope.launch {
                    delay(RELOAD_DELAY_MS)
                    preload()
                }
            }
        })
    }

    private fun showReadyAd(
        activity   : Activity,
        slot       : String,
        onSuccess  : () -> Unit,
        onFail     : () -> Unit,
        onStart    : () -> Unit = {},
        customData : String?    = null
    ) {
        if (!isAdReady || isShowingAd) {
            Log.w(TAG, "[$slot] ad not ready (isAdReady=$isAdReady isShowingAd=$isShowingAd). Fallback to load-then-show.")
            loadThenShow(activity, slot, onSuccess, onFail, onStart, customData)
            return
        }

        isAdReady   = false
        isShowingAd = true
        Log.d(TAG, "[$slot] showing preloaded ad...")

        // MetaData HARUS di-set sebelum UnityAds.show() dipanggil
        // supaya sid terkirim ke SSV callback
        if (customData != null) {
            MetaData(activity).apply {
                set("unity.customdata", customData)
                commit()
            }
        }
        UnityAds.show(activity, PLACEMENT_ID, object : IUnityAdsShowListener {
            override fun onUnityAdsShowStart(placementId: String) {
                Log.d(TAG, "[$slot] show started.")
                onStart()
            }
            override fun onUnityAdsShowClick(placementId: String) {}
            override fun onUnityAdsShowComplete(
                placementId : String,
                state       : UnityAds.UnityAdsShowCompletionState
            ) {
                isShowingAd = false
                Log.d(TAG, "[$slot] complete. state=$state")
                preload()
                if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) onSuccess()
                else onFail()
            }
            override fun onUnityAdsShowFailure(
                placementId : String,
                error       : UnityAds.UnityAdsShowError,
                message     : String
            ) {
                isShowingAd = false
                Log.w(TAG, "[$slot] show failed: $message")
                preload()
                onFail()
            }
        })
    }

    private fun loadThenShow(
        activity   : Activity,
        slot       : String,
        onSuccess  : () -> Unit,
        onFail     : () -> Unit,
        onStart    : () -> Unit = {},
        customData : String?    = null
    ) {
        if (isShowingAd) {
            Log.d(TAG, "[$slot] blocked — ad already showing.")
            onFail()
            return
        }

        Log.d(TAG, "[$slot] fallback loading...")

        var fallbackTimeout: Job? = null
        var settled = false

        val loadListener = object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String) {
                if (settled) return
                settled = true
                fallbackTimeout?.cancel()
                Log.d(TAG, "[$slot] fallback loaded. Showing...")
                isShowingAd = true
                // MetaData HARUS di-set sebelum UnityAds.show() dipanggil
                if (customData != null) {
                    MetaData(activity).apply {
                        set("unity.customdata", customData)
                        commit()
                    }
                }
                UnityAds.show(activity, PLACEMENT_ID, object : IUnityAdsShowListener {
                    override fun onUnityAdsShowStart(placementId: String) { onStart() }
                    override fun onUnityAdsShowClick(placementId: String) {}
                    override fun onUnityAdsShowComplete(
                        placementId : String,
                        state       : UnityAds.UnityAdsShowCompletionState
                    ) {
                        isShowingAd = false
                        Log.d(TAG, "[$slot] fallback complete. state=$state")
                        preload()
                        if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) onSuccess()
                        else onFail()
                    }
                    override fun onUnityAdsShowFailure(
                        placementId : String,
                        error       : UnityAds.UnityAdsShowError,
                        message     : String
                    ) {
                        isShowingAd = false
                        Log.w(TAG, "[$slot] fallback show failed: $message")
                        preload()
                        onFail()
                    }
                })
            }

            override fun onUnityAdsFailedToLoad(
                placementId : String,
                error       : UnityAds.UnityAdsLoadError,
                message     : String
            ) {
                if (settled) return
                settled = true
                fallbackTimeout?.cancel()
                Log.w(TAG, "[$slot] fallback load failed: $message")
                onFail()
            }
        }

        fallbackTimeout = scope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (!settled) {
                settled = true
                Log.w(TAG, "[$slot] fallback load timeout.")
                onFail()
            }
        }

        UnityAds.load(PLACEMENT_ID, loadListener)
    }

    fun showInterstitialIfAllowed(
        activity  : Activity,
        slot      : String = SLOT_GENERAL,
        onGranted : () -> Unit
    ): Boolean {
        if (PremiumManager.isPremium(activity)) {
            onGranted()
            return false
        }

        val p       = prefs(activity)
        val now     = System.currentTimeMillis()
        val lastKey = "last_$slot"
        val elapsed = now - p.getLong(lastKey, 0L)

        if (elapsed < SLOT_COOLDOWN_MS) {
            Log.d(TAG, "[$slot] cooldown ${(SLOT_COOLDOWN_MS - elapsed) / 1000L}s. Skip ad.")
            onGranted()
            return false
        }

        showReadyAd(
            activity  = activity,
            slot      = slot,
            onSuccess = {
                p.edit().putLong(lastKey, System.currentTimeMillis()).apply()
                Log.d(TAG, "[$slot] interstitial completed → granted.")
                onGranted()
            },
            onFail = {
                Log.d(TAG, "[$slot] ad unavailable → granted anyway.")
                onGranted()
            }
        )
        return true
    }

    fun showRewardedForExclusive(
        activity    : Activity,
        onCompleted : () -> Unit,
        onSkipped   : () -> Unit
    ) {
        if (isShowingAd) {
            onSkipped()
            return
        }
        showReadyAd(
            activity  = activity,
            slot      = SLOT_EXCLUSIVE,
            onSuccess = {
                Log.d(TAG, "[exclusive] rewarded completed.")
                onCompleted()
            },
            onFail = {
                Log.d(TAG, "[exclusive] rewarded unavailable/skipped.")
                onSkipped()
            }
        )
    }

    fun showRewardedForDailyReward(
        activity  : Activity,
        onStart   : () -> Unit = {},
        onResult  : (AdWatchResult) -> Unit
    ) {
        if (isShowingAd) {
            onResult(AdWatchResult.UNAVAILABLE)
            return
        }
        // customData = deviceId dikirim ke Unity SSV sebagai parameter ?sid=
        val deviceId = PremiumManager.getDeviceId(activity)
        showReadyAd(
            activity   = activity,
            slot       = SLOT_DAILY_REWARD,
            onStart    = onStart,
            customData = deviceId,
            onSuccess  = {
                Log.d(TAG, "[daily_reward] ad completed.")
                onResult(AdWatchResult.COMPLETED)
            },
            onFail = {
                Log.d(TAG, "[daily_reward] ad unavailable.")
                onResult(AdWatchResult.UNAVAILABLE)
            }
        )
    }

    fun isExclusiveSlotReady()   = isAdReady && !isShowingAd
    fun isDailyRewardSlotReady() = isAdReady && !isShowingAd
    fun preloadExclusive()       = preload()
    fun preloadDailyReward()     = preload()
}
