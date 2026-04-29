package com.javapro.ui.components

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.getSystemService
import com.javapro.R
import com.javapro.ui.screens.AdWatchResult
import com.javapro.utils.DailyRewardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@Composable
fun DailyRewardDialog(
    onWatchAd : (onAdStarted: () -> Unit, onAdFinished: (AdWatchResult) -> Unit) -> Unit,
    onDismiss : () -> Unit,
    onGranted : () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    fun hasActiveNetwork(): Boolean {
        val cm   = context.getSystemService<ConnectivityManager>() ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    var isNetworkAvailable by remember { mutableStateOf(hasActiveNetwork()) }

    DisposableEffect(Unit) {
        val cm = context.getSystemService<ConnectivityManager>()
        if (cm == null) {
            onDispose {}
        } else {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.post { isNetworkAvailable = hasActiveNetwork() }
                }
                override fun onLost(network: Network) {
                    mainHandler.post { isNetworkAvailable = hasActiveNetwork() }
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    mainHandler.post { isNetworkAvailable = hasActiveNetwork() }
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            onDispose { cm.unregisterNetworkCallback(callback) }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000L)
            isNetworkAvailable = hasActiveNetwork()
        }
    }

    val adsRequired = DailyRewardManager.ADS_REQUIRED
    var adsWatched  by remember { mutableIntStateOf(DailyRewardManager.adsWatchedSession(context)) }
    var uiState     by remember { mutableStateOf(RewardUiState.IDLE) }
    var errorMsg    by remember { mutableStateOf("") }
    var nextClaim   by remember { mutableStateOf(0L) }

    Dialog(
        onDismissRequest = {
            if (uiState == RewardUiState.IDLE ||
                uiState == RewardUiState.ERROR ||
                uiState == RewardUiState.ALREADY) onDismiss()
        },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress    = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF13171F))
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                val iconEmoji = if (uiState == RewardUiState.SUCCESS) "✅" else "🎁"
                val iconBg    = if (uiState == RewardUiState.SUCCESS)
                    Brush.radialGradient(listOf(Color(0xFF1B5E20), Color(0xFF0A1A0A)))
                else
                    Brush.radialGradient(listOf(Color(0xFF7C4DFF), Color(0xFF1A1F2E)))

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(iconEmoji, fontSize = 34.sp)
                }

                Text(
                    text = when (uiState) {
                        RewardUiState.SUCCESS -> stringResource(R.string.reward_title_success)
                        RewardUiState.ALREADY -> stringResource(R.string.reward_title_already)
                        RewardUiState.ERROR   -> stringResource(R.string.reward_title_failed)
                        else                  -> stringResource(R.string.reward_title_idle)
                    },
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (uiState) {
                        RewardUiState.SUCCESS -> Color(0xFF69F0AE)
                        RewardUiState.ALREADY -> Color(0xFFFFB74D)
                        RewardUiState.ERROR   -> Color(0xFFEF5350)
                        else                  -> Color(0xFFE0E0E0)
                    },
                    textAlign = TextAlign.Center
                )

                val bodyText = when (uiState) {
                    RewardUiState.IDLE        -> stringResource(R.string.reward_body_progress, adsWatched, adsRequired)
                    RewardUiState.LOADING_AD  -> stringResource(R.string.reward_body_loading_ad)
                    RewardUiState.LOADING_API -> stringResource(R.string.reward_body_loading_api)
                    RewardUiState.SUCCESS     -> stringResource(R.string.reward_body_success)
                    RewardUiState.ALREADY -> {
                        val remaining = nextClaim - System.currentTimeMillis()
                        val hours = TimeUnit.MILLISECONDS.toHours(remaining)
                        val mins  = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
                        stringResource(R.string.reward_body_already, hours, mins)
                    }
                    RewardUiState.ERROR -> errorMsg
                }

                Text(
                    text       = bodyText,
                    fontSize   = 13.sp,
                    color      = Color(0xFFB0BEC5),
                    textAlign  = TextAlign.Center,
                    lineHeight = 20.sp
                )

                AnimatedVisibility(
                    visible = uiState == RewardUiState.IDLE,
                    enter   = fadeIn(tween(300)) + scaleIn(tween(300))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E2330))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PerkRow("⚡", stringResource(R.string.reward_perk_no_ads))
                        PerkRow("🔓", stringResource(R.string.reward_perk_all_features))
                        PerkRow("⏱️", stringResource(R.string.reward_perk_duration))
                    }
                }

                Spacer(Modifier.height(4.dp))

                when (uiState) {

                    RewardUiState.IDLE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AnimatedVisibility(
                                visible = !isNetworkAvailable,
                                enter   = fadeIn(tween(300)),
                                exit    = fadeOut(tween(300))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF3E1A1A))
                                        .border(0.5.dp, Color(0xFFEF5350).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector        = Icons.Filled.SignalWifiOff,
                                        contentDescription = null,
                                        tint               = Color(0xFFEF9A9A),
                                        modifier           = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text       = stringResource(R.string.reward_no_network_warning),
                                        fontSize   = 11.sp,
                                        color      = Color(0xFFEF9A9A),
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (!isNetworkAvailable) return@Button
                                    uiState = RewardUiState.LOADING_AD

                                    onWatchAd(
                                        { DailyRewardManager.markAdStart(context) },
                                        { result ->
                                        scope.launch(Dispatchers.Main) {
                                            when (result) {
                                                AdWatchResult.UNAVAILABLE -> {
                                                    DailyRewardManager.clearAdStart(context)
                                                    uiState = RewardUiState.IDLE
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.reward_error_ad_unavailable),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                AdWatchResult.SKIPPED -> {
                                                    DailyRewardManager.clearAdStart(context)
                                                    errorMsg = context.getString(R.string.reward_error_ad_skipped)
                                                    uiState  = RewardUiState.ERROR
                                                }
                                                AdWatchResult.COMPLETED -> {
                                                    if (!DailyRewardManager.isAdDurationValid(context)) {
                                                        DailyRewardManager.clearAdStart(context)
                                                        errorMsg = context.getString(R.string.reward_error_ad_skipped)
                                                        uiState  = RewardUiState.ERROR
                                                        return@launch
                                                    }
                                                    DailyRewardManager.clearAdStart(context)
                                                    DailyRewardManager.recordAdWatched(context)
                                                    adsWatched = DailyRewardManager.adsWatchedSession(context)

                                                    if (!DailyRewardManager.hasWatchedEnoughAds(context)) {
                                                        // Progres bertambah, tapi belum cukup → balik ke IDLE
                                                        uiState = RewardUiState.IDLE
                                                        return@launch
                                                    }

                                                    uiState = RewardUiState.LOADING_API
                                                    when (val res = DailyRewardManager.claimReward(context)) {
                                                        is DailyRewardManager.ClaimResult.Success -> {
                                                            uiState = RewardUiState.SUCCESS
                                                        }
                                                        is DailyRewardManager.ClaimResult.AlreadyClaimed -> {
                                                            nextClaim = res.nextClaimMs
                                                            uiState   = RewardUiState.ALREADY
                                                        }
                                                        is DailyRewardManager.ClaimResult.WeeklyLimitReached -> {
                                                            errorMsg = context.getString(R.string.reward_error_server)
                                                            uiState  = RewardUiState.ERROR
                                                        }
                                                        DailyRewardManager.ClaimResult.InsufficientAds -> {
                                                            errorMsg = context.getString(R.string.reward_error_ad_skipped)
                                                            uiState  = RewardUiState.ERROR
                                                        }
                                                        DailyRewardManager.ClaimResult.AdFraudDetected -> {
                                                            DailyRewardManager.resetSessionAds(context)
                                                            adsWatched = 0
                                                            errorMsg = context.getString(R.string.reward_error_ad_skipped)
                                                            uiState  = RewardUiState.ERROR
                                                        }
                                                        DailyRewardManager.ClaimResult.TamperDetected -> {
                                                            DailyRewardManager.resetSessionAds(context)
                                                            adsWatched = 0
                                                            errorMsg = context.getString(R.string.reward_error_server)
                                                            uiState  = RewardUiState.ERROR
                                                        }
                                                        DailyRewardManager.ClaimResult.NetworkError -> {
                                                            errorMsg = context.getString(R.string.reward_error_no_internet)
                                                            uiState  = RewardUiState.ERROR
                                                        }
                                                        DailyRewardManager.ClaimResult.ServerError -> {
                                                            errorMsg = context.getString(R.string.reward_error_server)
                                                            uiState  = RewardUiState.ERROR
                                                        }
                                                    }
                                                    adsWatched = DailyRewardManager.adsWatchedSession(context)
                                                }
                                            }
                                        }
                                    })
                                },
                                enabled  = isNetworkAvailable,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape    = RoundedCornerShape(14.dp),
                                colors   = ButtonDefaults.buttonColors(
                                    containerColor         = Color(0xFF7C4DFF),
                                    contentColor           = Color.White,
                                    disabledContainerColor = Color(0xFF2A2F3E),
                                    disabledContentColor   = Color(0xFF607D8B)
                                )
                            ) {
                                if (!isNetworkAvailable) {
                                    Icon(Icons.Filled.SignalWifiOff, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    text       = stringResource(R.string.reward_btn_watch_ad),
                                    fontWeight = FontWeight.Bold,
                                    fontSize   = 15.sp
                                )
                            }

                            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text     = stringResource(R.string.reward_btn_later),
                                    color    = Color(0xFF78909C),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    RewardUiState.LOADING_AD,
                    RewardUiState.LOADING_API -> {
                        CircularProgressIndicator(
                            color       = Color(0xFF7C4DFF),
                            modifier    = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                    }

                    RewardUiState.SUCCESS -> {
                        var showRestartDialog by remember { mutableStateOf(false) }

                        if (showRestartDialog) {
                            AlertDialog(
                                onDismissRequest = {},
                                title   = { Text("Restart JavaPro") },
                                text    = { Text("Premium berhasil diaktifkan!\n\nMohon tutup dan buka ulang JavaPro agar pengecekan online di splash screen bekerja.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showRestartDialog = false
                                        onGranted()
                                    }) { Text("OK, Tutup Sekarang") }
                                }
                            )
                        }

                        Button(
                            onClick  = { showRestartDialog = true },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E7D32),
                                contentColor   = Color.White
                            )
                        ) {
                            Text(
                                text       = stringResource(R.string.reward_btn_start),
                                fontWeight = FontWeight.Bold,
                                fontSize   = 15.sp
                            )
                        }
                    }

                    RewardUiState.ALREADY,
                    RewardUiState.ERROR -> {
                        Button(
                            onClick  = { uiState = RewardUiState.IDLE },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2A2F3E),
                                contentColor   = Color(0xFFB0BEC5)
                            )
                        ) {
                            Text(
                                text       = stringResource(R.string.reward_btn_close),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PerkRow(emoji: String, label: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier              = Modifier.fillMaxWidth()
    ) {
        Text(emoji, fontSize = 14.sp)
        Text(label, fontSize = 12.sp, color = Color(0xFF90A4AE))
    }
}

private enum class RewardUiState {
    IDLE, LOADING_AD, LOADING_API, SUCCESS, ALREADY, ERROR
}
