package com.javapro.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.javapro.R
import com.javapro.ads.AdManager
import com.javapro.ads.BannerAdView
import com.javapro.ui.components.JavaProNavBar
import com.javapro.ui.components.getNavBarStyle
import com.javapro.ui.screens.*
import com.javapro.utils.PreferenceManager
import com.javapro.utils.PremiumManager

private fun Context.findActivity(): Activity {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    error("No Activity found in context chain")
}

private enum class WindowType { COMPACT, MEDIUM, EXPANDED }

private data class WindowSizeInfo(val widthType: WindowType) {
    val useNavRail get() = widthType != WindowType.COMPACT
}

@Composable
private fun rememberWindowSizeInfo(): WindowSizeInfo {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) {
        WindowSizeInfo(
            widthType = when {
                widthDp < 600 -> WindowType.COMPACT
                widthDp < 840 -> WindowType.MEDIUM
                else          -> WindowType.EXPANDED
            }
        )
    }
}

data class NavigationItem(
    val route          : String,
    val selectedIcon   : ImageVector,
    val unselectedIcon : ImageVector,
    val label          : String
)

@Composable
fun MainScreen(
    prefManager : PreferenceManager,
    onShowAd    : (slot: String, onGranted: () -> Unit) -> Unit = { _, granted -> granted() }
) {
    val navController = rememberNavController()
    val context       = LocalContext.current
    val isPremium     = remember { PremiumManager.isPremium(context) }
    val windowSize    = rememberWindowSizeInfo()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute      = navBackStackEntry?.destination?.route ?: "home"

    var useFloatingNav by remember { mutableStateOf(getNavBarStyle(context)) }
    LaunchedEffect(currentRoute) { useFloatingNav = getNavBarStyle(context) }

    // Daily reward screen bukan top-level, jadi nav bar disembunyikan
    val topLevelRoutes = setOf("home", "gamelist", "tweaks", "settings")
    val showNav        = currentRoute in topLevelRoutes

    val labelHome     = stringResource(R.string.nav_home)
    val labelGamelist = stringResource(R.string.nav_gamelist)
    val labelAdvanced = stringResource(R.string.nav_advanced)
    val labelSettings = stringResource(R.string.nav_settings)

    val items = listOf(
        NavigationItem("home",     Icons.Filled.Home,          Icons.Outlined.Home,          labelHome),
        NavigationItem("gamelist", Icons.Filled.SportsEsports, Icons.Outlined.SportsEsports, labelGamelist),
        NavigationItem("tweaks",   Icons.Filled.Tune,          Icons.Outlined.Tune,          labelAdvanced),
        NavigationItem("settings", Icons.Filled.Settings,      Icons.Outlined.Settings,      labelSettings)
    )

    if (windowSize.useNavRail) {
        DesktopLayout(
            navController = navController,
            items         = items,
            currentRoute  = currentRoute,
            showNav       = showNav,
            isPremium     = isPremium,
            prefManager   = prefManager,
            onShowAd      = onShowAd
        )
    } else {
        MobileLayout(
            navController  = navController,
            items          = items,
            currentRoute   = currentRoute,
            showNav        = showNav,
            isPremium      = isPremium,
            useFloatingNav = useFloatingNav,
            prefManager    = prefManager,
            onShowAd       = onShowAd
        )
    }
}

@Composable
private fun DesktopLayout(
    navController : NavHostController,
    items         : List<NavigationItem>,
    currentRoute  : String,
    showNav       : Boolean,
    isPremium     : Boolean,
    prefManager   : PreferenceManager,
    onShowAd      : (String, () -> Unit) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (showNav) {
                NavigationRail(
                    modifier       = Modifier.fillMaxHeight(),
                    containerColor = Color.Transparent,
                    header = {
                        Spacer(Modifier.height(8.dp))
                        Icon(
                            imageVector        = Icons.Filled.Terminal,
                            contentDescription = null,
                            tint               = MaterialTheme.colorScheme.primary,
                            modifier           = Modifier.padding(vertical = 12.dp)
                        )
                    }
                ) {
                    Spacer(Modifier.weight(1f))
                    items.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationRailItem(
                            selected = selected,
                            onClick  = {
                                if (!selected) navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            },
                            icon  = { Icon(if (selected) item.selectedIcon else item.unselectedIcon, null) },
                            label = { Text(item.label) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }

                VerticalDivider(
                    thickness = 0.5.dp,
                    color     = MaterialTheme.colorScheme.outlineVariant
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.weight(1f)) {
                    NavContent(
                        navController = navController,
                        prefManager   = prefManager,
                        onShowAd      = onShowAd,
                        modifier      = Modifier.fillMaxSize()
                    )
                }
                if (!isPremium) {
                    BannerAdView(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 50.dp))
                }
            }
        }
    }
}

@Composable
private fun MobileLayout(
    navController  : NavHostController,
    items          : List<NavigationItem>,
    currentRoute   : String,
    showNav        : Boolean,
    isPremium      : Boolean,
    useFloatingNav : Boolean,
    prefManager    : PreferenceManager,
    onShowAd       : (String, () -> Unit) -> Unit
) {
    var navIsVisible   by remember { mutableStateOf(true) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -8f) navIsVisible = false
                else if (available.y > 8f) navIsVisible = true
                return Offset.Zero
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor      = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
        ) {
            Box(modifier = Modifier.weight(1f).nestedScroll(nestedScrollConnection)) {
                NavContent(
                    navController = navController,
                    prefManager   = prefManager,
                    onShowAd      = onShowAd,
                    modifier      = Modifier.fillMaxSize()
                )

                if (showNav) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        JavaProNavBar(
                            items         = items,
                            currentRoute  = currentRoute,
                            navController = navController,
                            isVisible     = navIsVisible,
                            useFloating   = useFloatingNav,
                            onShowAd      = onShowAd
                        )
                    }
                }
            }

            if (!isPremium) {
                BannerAdView(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 50.dp))
            }
        }
    }
}

@Composable
private fun NavContent(
    navController : NavHostController,
    prefManager   : PreferenceManager,
    onShowAd      : (String, () -> Unit) -> Unit,
    modifier      : Modifier = Modifier
) {
    val lang by prefManager.languageFlow.collectAsState("en")

    NavHost(
        navController    = navController,
        startDestination = "home",
        modifier         = modifier
    ) {
        composable("home") {
            HomeScreen(
                prefManager   = prefManager,
                lang          = lang,
                navController = navController,
                onShowAd      = onShowAd
            )
        }
        composable("app_profiles") {
            AppProfileScreen(navController = navController, lang = lang)
        }
        composable("app_detail/{packageName}/{lang}") { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName") ?: ""
            val detailLang  = backStackEntry.arguments?.getString("lang") ?: "en"
            AppDetailScreen(navController, packageName, detailLang)
        }
        composable("gamelist") { GameBoostScreen(navController, lang) }
        composable("gameboost_detail/{packageName}/{lang}") { backStackEntry ->
            val pkg        = backStackEntry.arguments?.getString("packageName") ?: ""
            val detailLang = backStackEntry.arguments?.getString("lang") ?: "en"
            GameBoostDetailScreen(navController, pkg, detailLang, onShowAd = { onGranted -> onShowAd(AdManager.SLOT_APPPROFILE, onGranted) })
        }
        composable("tweaks")        { TweakScreen(lang, navController, onShowAd = { onGranted -> onShowAd(AdManager.SLOT_ADVANCED, onGranted) }) }
        composable("memory_screen") { MemoryScreen(navController, lang, onShowAd = { onGranted -> onShowAd(AdManager.SLOT_GENERAL, onGranted) }) }
        composable("settings")      { SettingScreen(prefManager, navController, lang) }
        composable("credits")       { CreditsScreen(navController) }
        composable("fps_live")      { FpsLiveScreen(navController, lang) }
        composable("premium")       { PremiumScreen(navController = navController, lang = lang) }
        composable("exclusive_features") { ExclusiveFeaturesScreen(navController = navController, prefManager = prefManager, lang = lang) }
        composable("debug_tools")   { DebugToolsScreen(navController = navController, prefManager = prefManager, lang = lang) }
        composable("screen_record") { ScreenRecordScreen(navController = navController, lang = lang) }

        // ── Daily Reward Screen ──────────────────────────────────────
        composable("daily_reward") {
            val activity = LocalContext.current.findActivity()
            DailyRewardScreen(
                navController = navController,
                onWatchAd     = { onAdStarted, onAdFinished ->
                    AdManager.showRewardedForDailyReward(
                        activity = activity,
                        onStart  = onAdStarted,
                        onResult = { result -> onAdFinished(result) }
                    )
                },
                onGranted     = { navController.popBackStack() },
                lang          = lang
            )
        }

        composable("device_spoof") {
            val activity = LocalContext.current.findActivity()
            DeviceSpoofScreen(
                navController = navController,
                lang          = lang,
                onWatchAd     = { onAdStarted, onAdFinished ->
                    AdManager.showRewardedForExclusive(
                        activity    = activity,
                        onCompleted = { onAdFinished(AdWatchResult.COMPLETED) },
                        onSkipped   = { onAdFinished(AdWatchResult.UNAVAILABLE) }
                    )
                }
            )
        }
    }
}
