package com.javapro.ui.screens

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import androidx.navigation.NavController
import com.javapro.R
import com.javapro.utils.DailyRewardManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class AdWatchResult { COMPLETED, SKIPPED, UNAVAILABLE }

private enum class RewardUiState {
    PROGRESS, LOADING_AD, LOADING_API, SUCCESS, ALREADY, WEEKLY_LIMIT, ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyRewardScreen(
    navController : NavController,
    onWatchAd     : (onAdStarted: () -> Unit, onAdFinished: (AdWatchResult) -> Unit) -> Unit,
    onGranted     : () -> Unit,
    lang          : String = "en"
) {
    val context     = LocalContext.current
    val scope       = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

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

    var adsWatched by remember {
        mutableIntStateOf(DailyRewardManager.adsWatchedSession(context))
    }
    val adsRequired     = DailyRewardManager.ADS_REQUIRED
    val maxWeeklyClaims = DailyRewardManager.MAX_CLAIMS_WEEKLY

    var cooldownLeft by remember {
        mutableIntStateOf(DailyRewardManager.cooldownSecondsLeft(context))
    }

    // FIX: Tambah epoch untuk trigger LaunchedEffect setiap kali cooldown dimulai baru,
    // bahkan jika sebelumnya sudah dalam state cooldown (isCoolingDown true -> true).
    var cooldownEpoch by remember { mutableIntStateOf(0) }

    LaunchedEffect(cooldownEpoch) {
        if (cooldownLeft > 0) {
            while (true) {
                delay(1_000L)
                val remaining = DailyRewardManager.cooldownSecondsLeft(context)
                cooldownLeft = remaining
                if (remaining <= 0) break
            }
        }
    }

    var uiState by remember {
        mutableStateOf(
            when {
                DailyRewardManager.hasReachedWeeklyLimit(context) -> RewardUiState.WEEKLY_LIMIT
                DailyRewardManager.msUntilNextClaim(context) > 0L -> RewardUiState.ALREADY
                else                                               -> RewardUiState.PROGRESS
            }
        )
    }
    var errorMsg            by remember { mutableStateOf("") }
    var errorResetsAds      by remember { mutableStateOf(false) }
    var nextClaimMs         by remember { mutableStateOf(DailyRewardManager.msUntilNextClaim(context)) }
    var nextMondayMs        by remember { mutableStateOf(DailyRewardManager.msUntilNextMonday(context)) }
    var isGranting          by remember { mutableStateOf(false) }
    var confirmedAdsWatched by remember { mutableIntStateOf(adsWatched) }

    val claimedThisWeek = DailyRewardManager.weekClaimCount(context)

    val progress by animateFloatAsState(
        targetValue   = confirmedAdsWatched.toFloat() / adsRequired.toFloat(),
        animationSpec = tween(600),
        label         = "adProgress"
    )

    fun claimNow() {
        if (!isNetworkAvailable) return
        if (uiState != RewardUiState.PROGRESS) return
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            uiState = RewardUiState.LOADING_API
            when (val res = DailyRewardManager.claimReward(context)) {
                is DailyRewardManager.ClaimResult.Success -> {
                    uiState = RewardUiState.SUCCESS
                }
                is DailyRewardManager.ClaimResult.AlreadyClaimed -> {
                    nextClaimMs = res.nextClaimMs - System.currentTimeMillis()
                    uiState     = RewardUiState.ALREADY
                }
                is DailyRewardManager.ClaimResult.WeeklyLimitReached -> {
                    nextMondayMs = res.nextMondayMs - System.currentTimeMillis()
                    uiState      = RewardUiState.WEEKLY_LIMIT
                }
                DailyRewardManager.ClaimResult.InsufficientAds -> {
                    // SSV Unity belum masuk — auto retry sekali setelah 5 detik
                    uiState = RewardUiState.LOADING_API
                    delay(5_000L)
                    when (val retry = DailyRewardManager.claimReward(context)) {
                        is DailyRewardManager.ClaimResult.Success -> {
                            uiState = RewardUiState.SUCCESS
                        }
                        is DailyRewardManager.ClaimResult.AlreadyClaimed -> {
                            nextClaimMs = retry.nextClaimMs - System.currentTimeMillis()
                            uiState     = RewardUiState.ALREADY
                        }
                        is DailyRewardManager.ClaimResult.WeeklyLimitReached -> {
                            nextMondayMs = retry.nextMondayMs - System.currentTimeMillis()
                            uiState      = RewardUiState.WEEKLY_LIMIT
                        }
                        else -> {
                            errorMsg       = context.getString(R.string.reward_error_ssv_pending)
                            errorResetsAds = false
                            uiState        = RewardUiState.ERROR
                        }
                    }
                }
                DailyRewardManager.ClaimResult.AdFraudDetected -> {
                    DailyRewardManager.resetSessionAds(context)
                    adsWatched          = 0
                    confirmedAdsWatched = 0
                    errorMsg       = context.getString(R.string.reward_error_ad_skipped)
                    errorResetsAds = true
                    uiState        = RewardUiState.ERROR
                }
                DailyRewardManager.ClaimResult.TamperDetected -> {
                    DailyRewardManager.resetSessionAds(context)
                    adsWatched          = 0
                    confirmedAdsWatched = 0
                    errorMsg       = context.getString(R.string.reward_error_server)
                    errorResetsAds = true
                    uiState        = RewardUiState.ERROR
                }
                DailyRewardManager.ClaimResult.NetworkError,
                DailyRewardManager.ClaimResult.ServerError -> {
                    // Jangan reset — biarkan user retry claim tanpa nonton iklan lagi
                    uiState = RewardUiState.PROGRESS
                    Toast.makeText(
                        context,
                        context.getString(R.string.reward_toast_server_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun watchNextAd() {
        if (!isNetworkAvailable) return
        if (uiState != RewardUiState.PROGRESS) return

        uiState = RewardUiState.LOADING_AD

        onWatchAd(
            { DailyRewardManager.markAdStart(context) },
            { result ->
            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                when (result) {
                    AdWatchResult.UNAVAILABLE -> {
                        DailyRewardManager.clearAdStart(context)
                        uiState = RewardUiState.PROGRESS
                        Toast.makeText(
                            context,
                            context.getString(R.string.reward_error_ad_unavailable),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    AdWatchResult.SKIPPED -> {
                        DailyRewardManager.clearAdStart(context)
                        errorMsg       = context.getString(R.string.reward_error_ad_skipped)
                        errorResetsAds = false
                        uiState        = RewardUiState.ERROR
                    }

                    AdWatchResult.COMPLETED -> {
                        if (!DailyRewardManager.isAdDurationValid(context)) {
                            DailyRewardManager.clearAdStart(context)
                            errorMsg       = context.getString(R.string.reward_error_ad_skipped)
                            errorResetsAds = false
                            uiState        = RewardUiState.ERROR
                            return@launch
                        }

                        DailyRewardManager.clearAdStart(context)
                        DailyRewardManager.recordAdWatched(context)
                        adsWatched          = DailyRewardManager.adsWatchedSession(context)
                        confirmedAdsWatched = adsWatched

                        if (DailyRewardManager.hasWatchedEnoughAds(context)) {
                            uiState = RewardUiState.LOADING_API
                            when (val res = DailyRewardManager.claimReward(context)) {
                                is DailyRewardManager.ClaimResult.Success -> {
                                    uiState = RewardUiState.SUCCESS
                                }
                                is DailyRewardManager.ClaimResult.AlreadyClaimed -> {
                                    nextClaimMs = res.nextClaimMs - System.currentTimeMillis()
                                    uiState     = RewardUiState.ALREADY
                                }
                                is DailyRewardManager.ClaimResult.WeeklyLimitReached -> {
                                    nextMondayMs = res.nextMondayMs - System.currentTimeMillis()
                                    uiState      = RewardUiState.WEEKLY_LIMIT
                                }
                                DailyRewardManager.ClaimResult.InsufficientAds -> {
                                    // SSV Unity belum masuk ke Supabase — auto retry sekali setelah 5 detik
                                    uiState = RewardUiState.LOADING_API
                                    delay(5_000L)
                                    when (val retry = DailyRewardManager.claimReward(context)) {
                                        is DailyRewardManager.ClaimResult.Success -> {
                                            uiState = RewardUiState.SUCCESS
                                        }
                                        is DailyRewardManager.ClaimResult.AlreadyClaimed -> {
                                            nextClaimMs = retry.nextClaimMs - System.currentTimeMillis()
                                            uiState     = RewardUiState.ALREADY
                                        }
                                        is DailyRewardManager.ClaimResult.WeeklyLimitReached -> {
                                            nextMondayMs = retry.nextMondayMs - System.currentTimeMillis()
                                            uiState      = RewardUiState.WEEKLY_LIMIT
                                        }
                                        else -> {
                                            // Masih gagal setelah retry — tampilkan pesan yang tepat
                                            errorMsg       = context.getString(R.string.reward_error_ssv_pending)
                                            errorResetsAds = false
                                            uiState        = RewardUiState.ERROR
                                        }
                                    }
                                }
                                DailyRewardManager.ClaimResult.AdFraudDetected -> {
                                    DailyRewardManager.resetSessionAds(context)
                                    adsWatched          = 0
                                    confirmedAdsWatched = 0
                                    errorMsg       = context.getString(R.string.reward_error_ad_skipped)
                                    errorResetsAds = true
                                    uiState        = RewardUiState.ERROR
                                }
                                DailyRewardManager.ClaimResult.TamperDetected -> {
                                    DailyRewardManager.resetSessionAds(context)
                                    adsWatched          = 0
                                    confirmedAdsWatched = 0
                                    errorMsg       = context.getString(R.string.reward_error_server)
                                    errorResetsAds = true
                                    uiState        = RewardUiState.ERROR
                                }
                                DailyRewardManager.ClaimResult.NetworkError -> {
                                    uiState = RewardUiState.PROGRESS
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.reward_toast_server_error),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                DailyRewardManager.ClaimResult.ServerError -> {
                                    uiState = RewardUiState.PROGRESS
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.reward_toast_server_error),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } else {
                            // FIX: increment epoch SEBELUM set cooldownLeft,
                            // supaya LaunchedEffect(cooldownEpoch) terpicu dengan nilai terbaru.
                            DailyRewardManager.startCooldown(context)
                            cooldownLeft  = DailyRewardManager.COOLDOWN_SECONDS
                            cooldownEpoch++
                            uiState = RewardUiState.PROGRESS
                        }
                    }
                }
            }
        })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text       = stringResource(R.string.reward_screen_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor             = colorScheme.surface,
                    titleContentColor          = colorScheme.onSurface,
                    navigationIconContentColor = colorScheme.onSurface
                )
            )
        },
        containerColor = colorScheme.background
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        when (uiState) {
                            RewardUiState.SUCCESS      -> colorScheme.primaryContainer
                            RewardUiState.ALREADY,
                            RewardUiState.WEEKLY_LIMIT -> colorScheme.secondaryContainer
                            RewardUiState.ERROR        -> colorScheme.errorContainer
                            else                       -> colorScheme.primaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState  = uiState,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    label        = "heroIcon"
                ) { state ->
                    val (icon, tint) = when (state) {
                        RewardUiState.SUCCESS      -> Icons.Filled.CheckCircle      to colorScheme.primary
                        RewardUiState.ALREADY,
                        RewardUiState.WEEKLY_LIMIT -> Icons.Filled.Schedule         to colorScheme.secondary
                        RewardUiState.ERROR        -> Icons.Filled.ErrorOutline     to colorScheme.error
                        RewardUiState.LOADING_AD,
                        RewardUiState.LOADING_API  -> Icons.Filled.PlayCircleFilled to colorScheme.primary
                        else                       -> Icons.Filled.CardGiftcard     to colorScheme.primary
                    }
                    Icon(
                        imageVector        = icon,
                        contentDescription = null,
                        tint               = tint,
                        modifier           = Modifier.size(52.dp)
                    )
                }
            }

            AnimatedContent(
                targetState  = uiState,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label        = "title"
            ) { state ->
                Text(
                    text = when (state) {
                        RewardUiState.SUCCESS      -> stringResource(R.string.reward_title_success)
                        RewardUiState.ALREADY      -> stringResource(R.string.reward_title_already)
                        RewardUiState.WEEKLY_LIMIT -> stringResource(R.string.reward_title_weekly_limit)
                        RewardUiState.ERROR        -> stringResource(R.string.reward_title_failed)
                        RewardUiState.LOADING_AD,
                        RewardUiState.LOADING_API  -> stringResource(R.string.reward_title_loading)
                        else                       -> stringResource(R.string.reward_title_idle)
                    },
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = when (state) {
                        RewardUiState.SUCCESS      -> colorScheme.primary
                        RewardUiState.ALREADY,
                        RewardUiState.WEEKLY_LIMIT -> colorScheme.secondary
                        RewardUiState.ERROR        -> colorScheme.error
                        else                       -> colorScheme.onBackground
                    },
                    textAlign = TextAlign.Center
                )
            }

            AnimatedContent(
                targetState  = uiState,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label        = "body"
            ) { state ->
                val text = when (state) {
                    RewardUiState.PROGRESS    -> stringResource(R.string.reward_body_progress, adsWatched, adsRequired, claimedThisWeek, maxWeeklyClaims)
                    RewardUiState.LOADING_AD  -> stringResource(R.string.reward_body_loading_ad)
                    RewardUiState.LOADING_API -> stringResource(R.string.reward_body_loading_api)
                    RewardUiState.SUCCESS     -> stringResource(R.string.reward_body_success, claimedThisWeek, maxWeeklyClaims)
                    RewardUiState.ALREADY -> {
                        val ms    = nextClaimMs.coerceAtLeast(0L)
                        val hours = TimeUnit.MILLISECONDS.toHours(ms)
                        val mins  = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
                        stringResource(R.string.reward_body_already, hours, mins)
                    }
                    RewardUiState.WEEKLY_LIMIT -> {
                        val ms   = nextMondayMs.coerceAtLeast(0L)
                        val days = TimeUnit.MILLISECONDS.toDays(ms)
                        val hrs  = TimeUnit.MILLISECONDS.toHours(ms) % 24
                        stringResource(R.string.reward_body_weekly_limit, maxWeeklyClaims, days, hrs)
                    }
                    RewardUiState.ERROR -> errorMsg
                }
                Text(
                    text       = text,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = colorScheme.onSurfaceVariant,
                    textAlign  = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }

            AnimatedVisibility(
                visible = uiState == RewardUiState.PROGRESS || uiState == RewardUiState.LOADING_AD,
                enter   = fadeIn(tween(300)) + scaleIn(tween(300))
            ) {
                AdProgressSection(
                    adsWatched      = confirmedAdsWatched,
                    adsRequired     = adsRequired,
                    claimedThisWeek = claimedThisWeek,
                    maxWeeklyClaims = maxWeeklyClaims,
                    progress        = progress,
                    colorScheme     = colorScheme
                )
            }

            AnimatedVisibility(
                visible = uiState == RewardUiState.PROGRESS,
                enter   = fadeIn(tween(300)) + scaleIn(tween(300))
            ) {
                PerksCard(colorScheme = colorScheme)
            }

            Spacer(Modifier.height(8.dp))

            AnimatedContent(
                targetState  = uiState,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label        = "buttons"
            ) { state ->
                when (state) {

                    RewardUiState.PROGRESS -> {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier            = Modifier.fillMaxWidth()
                        ) {
                            AnimatedVisibility(
                                visible = !isNetworkAvailable,
                                enter   = fadeIn(tween(300)),
                                exit    = fadeOut(tween(300))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colorScheme.errorContainer)
                                        .border(0.5.dp, colorScheme.error.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector        = Icons.Filled.SignalWifiOff,
                                        contentDescription = null,
                                        tint               = colorScheme.onErrorContainer,
                                        modifier           = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text       = stringResource(R.string.reward_no_network_warning),
                                        fontSize   = 12.sp,
                                        color      = colorScheme.onErrorContainer,
                                        lineHeight = 17.sp
                                    )
                                }
                            }

                            val adsFull       = confirmedAdsWatched >= adsRequired
                            val isCoolingDown = cooldownLeft > 0
                            val buttonEnabled = !isCoolingDown && isNetworkAvailable
                            Button(
                                onClick  = { if (buttonEnabled) { if (adsFull) claimNow() else watchNextAd() } },
                                enabled  = buttonEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape    = RoundedCornerShape(16.dp),
                                colors   = ButtonDefaults.buttonColors(
                                    containerColor         = colorScheme.primary,
                                    contentColor           = colorScheme.onPrimary,
                                    disabledContainerColor = colorScheme.surfaceVariant,
                                    disabledContentColor   = colorScheme.onSurfaceVariant
                                )
                            ) {
                                when {
                                    adsFull -> {
                                        Icon(Icons.Filled.CardGiftcard, null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.reward_btn_claim), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                    !isNetworkAvailable -> {
                                        Icon(Icons.Filled.SignalWifiOff, null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.reward_btn_watch_ad), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                    isCoolingDown -> {
                                        Icon(Icons.Filled.Schedule, null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        val mins = cooldownLeft / 60
                                        val secs = cooldownLeft % 60
                                        Text(if (mins > 0) "%d:%02d".format(mins, secs) else "${secs}s", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                    else -> {
                                        Icon(Icons.Filled.PlayCircleFilled, null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.reward_btn_watch_ad), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                }
                            }

                            TextButton(
                                onClick  = { navController.popBackStack() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text  = stringResource(R.string.reward_btn_later),
                                    color = colorScheme.outline
                                )
                            }
                        }
                    }

                    RewardUiState.LOADING_AD,
                    RewardUiState.LOADING_API -> {
                        CircularProgressIndicator(
                            color       = colorScheme.primary,
                            modifier    = Modifier.size(40.dp),
                            strokeWidth = 3.dp
                        )
                    }

                    RewardUiState.SUCCESS -> {
                        // Tidak perlu restart — invalidateCache di onResume sudah handle
                        LaunchedEffect(Unit) {
                            if (!isGranting) {
                                isGranting = true
                                kotlinx.coroutines.delay(2_000L)
                                onGranted()
                            }
                        }

                        Button(
                            onClick  = {
                                if (!isGranting) { isGranting = true; onGranted() }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape    = RoundedCornerShape(16.dp),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = colorScheme.primary,
                                contentColor   = colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text       = stringResource(R.string.reward_btn_start),
                                fontWeight = FontWeight.Bold,
                                fontSize   = 15.sp
                            )
                        }
                    }

                    RewardUiState.ALREADY,
                    RewardUiState.WEEKLY_LIMIT,
                    RewardUiState.ERROR -> {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier            = Modifier.fillMaxWidth()
                        ) {
                            if (state == RewardUiState.ERROR) {
                                Button(
                                    onClick = {
                                        if (errorResetsAds) {
                                            adsWatched          = 0
                                            confirmedAdsWatched = 0
                                        }
                                        uiState = RewardUiState.PROGRESS
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape    = RoundedCornerShape(16.dp),
                                    colors   = ButtonDefaults.buttonColors(
                                        containerColor = colorScheme.primary,
                                        contentColor   = colorScheme.onPrimary
                                    )
                                ) {
                                    Text(
                                        text       = stringResource(R.string.reward_btn_retry),
                                        fontWeight = FontWeight.Bold,
                                        fontSize   = 15.sp
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick  = { navController.popBackStack() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape    = RoundedCornerShape(16.dp)
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
}

@Composable
private fun AdProgressSection(
    adsWatched      : Int,
    adsRequired     : Int,
    claimedThisWeek : Int,
    maxWeeklyClaims : Int,
    progress        : Float,
    colorScheme     : ColorScheme
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        colors   = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
    ) {
        Column(
            modifier            = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text  = stringResource(R.string.reward_progress_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    text       = "$adsWatched / $adsRequired",
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color      = colorScheme.primary
                )
            }

            LinearProgressIndicator(
                progress   = { progress },
                modifier   = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50)),
                color      = colorScheme.primary,
                trackColor = colorScheme.surfaceContainerHighest
            )

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                repeat(adsRequired) { index ->
                    val done = index < adsWatched
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (done) colorScheme.primary
                                else      colorScheme.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (done) {
                            Icon(
                                imageVector        = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint               = colorScheme.onPrimary,
                                modifier           = Modifier.size(16.dp)
                            )
                        } else {
                            Text(
                                text  = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp)

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text  = stringResource(R.string.reward_weekly_claims_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    text       = "$claimedThisWeek / $maxWeeklyClaims",
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color      = colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun PerksCard(colorScheme: ColorScheme) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        colors   = CardDefaults.cardColors(containerColor = colorScheme.secondaryContainer)
    ) {
        Column(
            modifier            = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text       = stringResource(R.string.reward_perks_title),
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color      = colorScheme.onSecondaryContainer
            )
            PerkRow(Icons.Filled.Bolt,         stringResource(R.string.reward_perk_no_ads),       colorScheme)
            PerkRow(Icons.Filled.LockOpen,      stringResource(R.string.reward_perk_all_features), colorScheme)
            PerkRow(Icons.Filled.CalendarMonth, stringResource(R.string.reward_perk_duration),     colorScheme)
            PerkRow(Icons.Filled.Repeat,        stringResource(R.string.reward_perk_weekly),       colorScheme)
        }
    }
}

@Composable
private fun PerkRow(
    icon        : androidx.compose.ui.graphics.vector.ImageVector,
    label       : String,
    colorScheme : ColorScheme
) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier              = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = colorScheme.onSecondaryContainer,
            modifier           = Modifier.size(18.dp)
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSecondaryContainer
        )
    }
}
