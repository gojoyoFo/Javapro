package com.javapro.ads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.javapro.utils.PremiumManager
import com.javapro.utils.findActivity
import com.unity3d.services.banners.BannerErrorInfo
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "BannerAdView"

@Composable
fun BannerAdView(modifier: Modifier = Modifier) {
    val context   = LocalContext.current
    val isPremium = remember { PremiumManager.isPremium(context) }

    if (isPremium) return

    // Resolve activity di level composable (bukan di dalam factory).
    // Kalau null (mis. context berasal dari Application atau wrapper non-Activity),
    // kita skip render banner sama sekali — lebih aman daripada crash.
    val activity = remember(context) { context.findActivity() }
    if (activity == null) {
        Log.w(TAG, "findActivity() returned null — skipping banner render")
        return
    }

    var isOnline by remember { mutableStateOf(isNetworkAvailable(context)) }
    var retryKey by remember { mutableIntStateOf(0) }
    val scope    = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { isOnline = true }
            override fun onLost(network: Network)      { isOnline = isNetworkAvailable(context) }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }

    if (isOnline) {
        key(retryKey) {
            AndroidView(
                modifier = modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .wrapContentWidth(Alignment.CenterHorizontally),
                factory = { ctx ->
                    // activity sudah divalidasi di atas — aman dipakai di sini.
                    // ctx dipakai untuk FrameLayout agar theming ikut window yang benar.
                    val container = FrameLayout(ctx)
                    val banner = BannerView(
                        activity,
                        "Banner_Android",
                        UnityBannerSize.getDynamicSize(activity)
                    )
                    banner.listener = object : BannerView.IListener {
                        override fun onBannerLoaded(bannerAdView: BannerView) {}
                        override fun onBannerShown(bannerAdView: BannerView) {}
                        override fun onBannerClick(bannerAdView: BannerView) {}
                        override fun onBannerFailedToLoad(
                            bannerAdView: BannerView,
                            errorInfo: BannerErrorInfo
                        ) {
                            bannerAdView.destroy()
                            val delayMs = (15_000L..30_000L).random()
                            scope.launch {
                                delay(delayMs)
                                retryKey++
                            }
                        }
                        override fun onBannerLeftApplication(bannerView: BannerView) {}
                    }
                    banner.load()
                    val lp = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_HORIZONTAL
                    )
                    container.addView(banner, lp)
                    container
                }
            )
        }
    } else {
        Surface(
            modifier       = modifier.fillMaxWidth().height(50.dp),
            color          = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp
        ) {}
    }
}

private fun isNetworkAvailable(context: Context): Boolean {
    val cm      = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps    = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
