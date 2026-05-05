package com.javapro.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.javapro.ui.NavigationItem

const val KEY_FLOATING_NAVBAR = "use_floating_navbar"
const val PREFS_NAVBAR         = "javapro_settings"

fun getNavBarStyle(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAVBAR, Context.MODE_PRIVATE)
        .getBoolean(KEY_FLOATING_NAVBAR, false)

fun setNavBarStyle(context: Context, useFloating: Boolean) =
    context.getSharedPreferences(PREFS_NAVBAR, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_FLOATING_NAVBAR, useFloating).apply()

@Composable
fun rememberNavBarVisible(scrollState: androidx.compose.foundation.ScrollState): Boolean {
    var isVisible by remember { mutableStateOf(true) }
    var lastScrollValue by remember { mutableIntStateOf(0) }

    LaunchedEffect(scrollState.value) {
        val delta = scrollState.value - lastScrollValue
        when {
            delta > 10  -> isVisible = false
            delta < -10 -> isVisible = true
        }
        lastScrollValue = scrollState.value
    }
    return isVisible
}

@Composable
fun rememberNavBarVisibleLazy(lazyState: LazyListState): Boolean {
    var isVisible by remember { mutableStateOf(true) }
    val firstVisibleItem       = lazyState.firstVisibleItemIndex
    val firstVisibleItemOffset = lazyState.firstVisibleItemScrollOffset

    var prevItem   by remember { mutableIntStateOf(0) }
    var prevOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(firstVisibleItem, firstVisibleItemOffset) {
        val scrolledDown = firstVisibleItem > prevItem ||
            (firstVisibleItem == prevItem && firstVisibleItemOffset > prevOffset + 20)
        val scrolledUp = firstVisibleItem < prevItem ||
            (firstVisibleItem == prevItem && firstVisibleItemOffset < prevOffset - 20)
        when {
            scrolledDown -> isVisible = false
            scrolledUp   -> isVisible = true
        }
        prevItem   = firstVisibleItem
        prevOffset = firstVisibleItemOffset
    }
    return isVisible
}

@Composable
fun JavaProNavBar(
    items         : List<NavigationItem>,
    currentRoute  : String,
    navController : NavHostController,
    isVisible     : Boolean,
    useFloating   : Boolean = false,
    onShowAd      : (slot: String, onGranted: () -> Unit) -> Unit = { _, granted -> granted() }
) {
    AnimatedVisibility(
        visible = isVisible,
        enter   = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(220)),
        exit    = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(180))
    ) {
        if (useFloating) {
            FloatingNavBar(items = items, currentRoute = currentRoute, navController = navController, onShowAd = onShowAd)
        } else {
            GlowLiquidNavBar(items = items, currentRoute = currentRoute, navController = navController, onShowAd = onShowAd)
        }
    }
}

@Composable
fun GlowLiquidNavBar(
    items         : List<NavigationItem>,
    currentRoute  : String,
    navController : NavHostController,
    onShowAd      : (slot: String, onGranted: () -> Unit) -> Unit = { _, granted -> granted() }
) {
    val configuration = LocalConfiguration.current
    val density       = LocalDensity.current
    val navBarWidth   = configuration.screenWidthDp.dp * 0.85f
    val barHeight     = 58.dp
    val pillPadding   = 4.dp
    val navBgBase     = MaterialTheme.colorScheme.surfaceContainerHigh
    val glassTop      = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val glassBot      = Color.Transparent

    val selectedIndex = items.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val itemWidthPx   = remember { mutableStateOf(0f) }
    val itemOffsets   = remember { mutableStateMapOf<Int, Float>() }

    val targetOffsetDp = remember(selectedIndex, itemOffsets, density) {
        val offsetPx = itemOffsets[selectedIndex] ?: 0f
        with(density) { offsetPx.toDp() }
    }
    val animatedOffsetDp by animateDpAsState(
        targetValue   = targetOffsetDp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)
    )
    val pillWidth: Dp = with(density) {
        (itemWidthPx.value - with(density) { (pillPadding * 2).toPx() }).toDp()
    }

    Box(modifier = Modifier.width(navBarWidth).height(barHeight), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxSize().shadow(20.dp, CircleShape, ambientColor = Color.Black.copy(0.15f), spotColor = Color.Black.copy(0.10f)).clip(CircleShape).background(navBgBase))
        Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(Brush.verticalGradient(listOf(glassTop, glassBot))))

        if (itemWidthPx.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight().padding(vertical = pillPadding).width(pillWidth)
                    .offset(x = animatedOffsetDp + pillPadding).clip(CircleShape)
                    .background(Brush.linearGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(0.95f), MaterialTheme.colorScheme.secondary.copy(0.9f)),
                        start  = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )).align(Alignment.CenterStart)
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(0.3f), Color.Transparent), center = Offset(itemWidthPx.value / 2f, 12f), radius = 40f)))
            }
        }

        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, item ->
                val isSelected = currentRoute == item.route
                Box(
                    modifier = Modifier
                        .weight(1f).fillMaxHeight()
                        .onGloballyPositioned { coords ->
                            itemWidthPx.value  = coords.size.width.toFloat()
                            itemOffsets[index] = coords.positionInParent().x
                        }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (!isSelected) {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(
                            imageVector        = if (isSelected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = null,
                            tint               = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier           = Modifier.size(22.dp)
                        )
                        AnimatedVisibility(visible = isSelected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            Text(text = item.label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingNavBar(
    items         : List<NavigationItem>,
    currentRoute  : String,
    navController : NavHostController,
    onShowAd      : (slot: String, onGranted: () -> Unit) -> Unit = { _, granted -> granted() }
) {
    Surface(
        modifier        = Modifier,
        shape           = RoundedCornerShape(50),
        color           = Color.Transparent,
        tonalElevation  = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .height(52.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { item ->
                val isSelected = currentRoute == item.route

                val animSpec = tween<Color>(durationMillis = 280, easing = FastOutSlowInEasing)
                val bgColor by animateColorAsState(
                    targetValue   = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    animationSpec = animSpec,
                    label         = "floatBg"
                )
                val contentColor by animateColorAsState(
                    targetValue   = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = animSpec,
                    label         = "floatContent"
                )

                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(bgColor)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (!isSelected) {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector        = if (isSelected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = null,
                        tint               = contentColor,
                        modifier           = Modifier.size(20.dp)
                    )
                    AnimatedVisibility(
                        visible = isSelected,
                        enter   = expandHorizontally(tween(280, easing = FastOutSlowInEasing), expandFrom = Alignment.Start) + fadeIn(tween(280)),
                        exit    = shrinkHorizontally(tween(250, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Start) + fadeOut(tween(200))
                    ) {
                        Text(
                            text       = item.label,
                            color      = contentColor,
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines   = 1,
                            softWrap   = false,
                            modifier   = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
