package com.javapro

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.javapro.R
import com.javapro.ads.AdManager
import com.javapro.ui.MainScreen
import com.javapro.ui.components.DownloadProgressDialog
import com.javapro.ui.components.UpdateDialog
import com.javapro.ui.screens.SplashScreen
import com.javapro.ui.theme.JavaProTheme
import com.javapro.utils.LocaleHelper
import com.javapro.utils.PreferenceManager
import com.javapro.utils.PremiumManager
import com.javapro.utils.ReleaseInfo
import com.javapro.utils.ShizukuManager
import com.javapro.utils.TweakManager
import com.javapro.utils.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.UnityAds
import rikka.shizuku.Shizuku
import java.net.InetAddress
import kotlin.system.exitProcess

private val AD_PROBE_DOMAINS = listOf(
    "googleads.g.doubleclick.net",
    "config.unityads.unity3d.com",
    "pagead2.googlesyndication.com",
    "ads.pubmatic.com"
)
private val BLOCKED_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1")

private fun isNetworkAvailable(context: Context): Boolean {
    val cm      = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps    = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

suspend fun isDnsAdBlockActive(context: Context): Boolean = withContext(Dispatchers.IO) {
    if (!isNetworkAvailable(context)) return@withContext false
    var blockedCount = 0
    for (domain in AD_PROBE_DOMAINS) {
        try {
            val addresses = InetAddress.getAllByName(domain)
            if (addresses.any { it.hostAddress in BLOCKED_IPS }) blockedCount++
        } catch (_: Exception) {}
    }
    blockedCount >= 2
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) Toast.makeText(this, getString(R.string.toast_permission_required), Toast.LENGTH_LONG).show()
    }

    private val shizukuListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            requestShizukuPermissionIfNeeded()
            ShizukuManager.bindService()
            Shizuku.removeBinderReceivedListener(this)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val adBlockInvokedCallback = OnBackInvokedCallback {}

    private var adCallbackRegistered = false

    private val adStateHandler = Handler(Looper.getMainLooper())
    private val adStateWatcher = object : Runnable {
        override fun run() {
            syncAdBackBlock()
            adStateHandler.postDelayed(this, 80)
        }
    }

    private fun syncAdBackBlock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (AdManager.isShowingAd && !adCallbackRegistered) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    adBlockInvokedCallback
                )
                adCallbackRegistered = true
            } else if (!AdManager.isShowingAd && adCallbackRegistered) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(adBlockInvokedCallback)
                adCallbackRegistered = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        TweakManager.init(this)
        checkNotificationPermission()

        if (!hasUsageStatsPermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        val isPerfModeActive = TweakManager.getPrefs(this)
            .getBoolean(TweakManager.KEY_PERFORMANCE_MODE, false)
        if (isPerfModeActive) TweakManager.setPerformanceMode(this, true)

        if (Shizuku.pingBinder()) {
            requestShizukuPermissionIfNeeded()
            ShizukuManager.bindService()
        } else {
            Shizuku.addBinderReceivedListener(shizukuListener)
        }

        // Init Unity Ads hanya jika bukan real premium
        if (!PremiumManager.isRealPremium(this)) {
            UnityAds.initialize(
                applicationContext,
                AdManager.GAME_ID,
                false,
                object : IUnityAdsInitializationListener {
                    override fun onInitializationComplete() {
                        AdManager.init(this@MainActivity)
                    }
                    override fun onInitializationFailed(
                        error: UnityAds.UnityAdsInitializationError,
                        message: String
                    ) {}
                }
            )
        }

        setContent {
            val prefManager    = remember { PreferenceManager(this) }
            val fpsEnabled     by prefManager.fpsEnabledFlow.collectAsState(initial = false)
            val lang           by prefManager.languageFlow.collectAsState(initial = "en")
            val isDark         by prefManager.darkModeFlow.collectAsState()


            // isPremium sebagai mutableState agar bisa di-refresh setelah daily reward diklaim
            var isPremium by remember { mutableStateOf(PremiumManager.isPremium(this@MainActivity)) }

            var showAdBlockDialog    by remember { mutableStateOf(false) }
            var pendingRelease       by remember { mutableStateOf<ReleaseInfo?>(null) }
            var showUpdateDialog     by remember { mutableStateOf(false) }
            var isDownloading        by remember { mutableStateOf(false) }
            var downloadProgress     by remember { mutableStateOf(0) }

            val localizedContext = remember(lang) {
                val config = android.content.res.Configuration(resources.configuration)
                val locale = java.util.Locale(lang)
                config.setLocale(locale)
                object : android.content.ContextWrapper(this@MainActivity) {
                    private val localizedRes =
                        this@MainActivity.createConfigurationContext(config).resources
                    override fun getResources() = localizedRes
                }
            }

            // ── Deteksi AdBlock + update checker ─────────────────────
            LaunchedEffect(Unit) {
                if (!isPremium) {
                    val adBlockDetected = isDnsAdBlockActive(this@MainActivity)
                    if (adBlockDetected) {
                        showAdBlockDialog = true
                        return@LaunchedEffect
                    }
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val release = UpdateChecker.checkForUpdate(this@MainActivity)
                    if (release != null) {
                        pendingRelease   = release
                        showUpdateDialog = true
                    }
                }
            }

            CompositionLocalProvider(
                LocalContext  provides localizedContext
            ) {

                // ── Dialog: AdBlock detected ──────────────────────────
                if (showAdBlockDialog) {
                    AlertDialog(
                        onDismissRequest = {},
                        containerColor   = Color(0xFF13171F),
                        shape            = RoundedCornerShape(20.dp),
                        title = {
                            Text(
                                stringResource(R.string.adblock_title),
                                fontWeight = FontWeight.Bold,
                                fontSize   = 17.sp,
                                color      = Color(0xFFEF5350)
                            )
                        },
                        text = {
                            Text(
                                stringResource(R.string.adblock_body),
                                fontSize   = 13.sp,
                                color      = Color(0xFFB0BEC5),
                                lineHeight = 20.sp,
                                textAlign  = TextAlign.Start
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick  = { finishAffinity(); exitProcess(0) },
                                modifier = Modifier.fillMaxWidth(),
                                shape    = RoundedCornerShape(12.dp),
                                colors   = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFEF5350),
                                    contentColor   = Color.White
                                )
                            ) {
                                Text(
                                    stringResource(R.string.adblock_btn_exit),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    )
                }

                // ── Dialog: Update tersedia ────────────────────────────
                if (showUpdateDialog && pendingRelease != null) {
                    UpdateDialog(
                        release   = pendingRelease!!,
                        isDark    = isDark,
                        lang      = lang,
                        onConfirm = {
                            showUpdateDialog = false
                            isDownloading    = true
                            downloadProgress = 0
                            UpdateChecker.downloadAndInstall(
                                context    = this@MainActivity,
                                url        = pendingRelease!!.downloadUrl,
                                onProgress = { pct -> downloadProgress = pct },
                                onFinished = { isDownloading = false },
                                onError    = { isDownloading = false }
                            )
                        },
                        onDismiss = { showUpdateDialog = false }
                    )
                }

                // ── Dialog: Download progress ─────────────────────────
                if (isDownloading) {
                    DownloadProgressDialog(
                        progress = downloadProgress,
                        lang     = lang,
                        onCancel = { isDownloading = false }
                    )
                }

                // ── FPS Service control ───────────────────────────────
                LaunchedEffect(fpsEnabled) {
                    if (fpsEnabled) {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            startService(Intent(this@MainActivity, FpsService::class.java))
                        } else {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                            prefManager.setFpsEnabled(false)
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.toast_overlay_required),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        stopService(Intent(this@MainActivity, FpsService::class.java))
                    }
                }

                // ── Navigation ────────────────────────────────────────
                JavaProTheme(prefManager = prefManager) {
                    Surface(color = Color.Transparent) {
                        val mainNavController = rememberNavController()
                        NavHost(
                            navController    = mainNavController,
                            startDestination = "splash_route"
                        ) {
                            composable("splash_route") {
                                SplashScreen(
                                    onLoadingFinished = {
                                        mainNavController.navigate("main_route") {
                                            popUpTo("splash_route") { inclusive = true }
                                        }
                                        // Setelah splash: refresh status premium dari cache
                                        // (sudah di-update oleh checkOnline() di SplashScreen)
                                        isPremium = PremiumManager.isPremium(this@MainActivity)
                                    }
                                )
                            }
                            composable("main_route") {
                                MainScreen(
                                    prefManager = prefManager,
                                    onShowAd    = if (isPremium) {
                                        // Premium aktif (asli atau daily_reward) → skip iklan
                                        _, onGranted -> onGranted()
                                    } else {
                                        slot, onGranted ->
                                        AdManager.showInterstitialIfAllowed(
                                            this@MainActivity, slot, onGranted
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onResume() {
        super.onResume()
        adStateHandler.post(adStateWatcher)
    }

    override fun onPause() {
        super.onPause()
        adStateHandler.removeCallbacks(adStateWatcher)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && adCallbackRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(adBlockInvokedCallback)
            adCallbackRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adStateHandler.removeCallbacksAndMessages(null)
        ShizukuManager.unbindService()
    }

    private fun requestShizukuPermissionIfNeeded() {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun attachBaseContext(newBase: Context) {
        val savedLang = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLocale(newBase, savedLang))
    }
}
