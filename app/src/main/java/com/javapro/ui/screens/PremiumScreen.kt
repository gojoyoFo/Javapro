package com.javapro.ui.screens
import com.javapro.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.utils.PremiumManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumScreen(navController: NavController, lang: String) {
    val context      = LocalContext.current
    val googleUser   = remember { com.javapro.utils.GoogleAuthManager.getUser(context) }
    var isPremium    by remember { mutableStateOf(PremiumManager.isPremium(context)) }
    var premiumType  by remember { mutableStateOf(PremiumManager.getPremiumType(context)) }
    var expiryMs     by remember { mutableStateOf(PremiumManager.getExpiryMs(context)) }
    var isLoading    by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        val result = PremiumManager.checkOnline(context)
        isPremium   = result
        premiumType = PremiumManager.getPremiumType(context)
        expiryMs    = PremiumManager.getExpiryMs(context)
        isLoading   = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val accentWeekly    = Color(0xFF00ACC1)
    val accentMonthly   = Color(0xFF1E88E5)
    val accentYearly    = Color(0xFFFF8F00)
    val accentPermanent = Color(0xFFAB47BC)
    val accentGreen     = Color(0xFF4CAF50)

    // ── State untuk payment method sheet ──────────────────────────────────────
    var showPaymentSheet  by remember { mutableStateOf(false) }
    var pendingPkgType    by remember { mutableStateOf("") }
    var pendingPkgAmount  by remember { mutableIntStateOf(0) }
    var pendingPkgLabel   by remember { mutableStateOf("") }

    if (showPaymentSheet) {
        PaymentMethodSheet(
            packageType   = pendingPkgType,
            packageLabel  = pendingPkgLabel,
            amountIdr     = pendingPkgAmount,
            email         = googleUser?.email ?: "",
            context       = context,
            navController = navController,
            onDismiss     = { showPaymentSheet = false }
        )
    }

    val planAccentColor = when (premiumType) {
        "weekly"    -> accentWeekly
        "monthly"   -> accentMonthly
        "yearly"    -> accentYearly
        "permanent" -> accentPermanent
        else        -> accentGreen
    }

    var remainingMs by remember { mutableStateOf(if (premiumType != "permanent") (expiryMs - System.currentTimeMillis()).coerceAtLeast(0L) else 0L) }

    LaunchedEffect(expiryMs, premiumType) {
        if (premiumType != "permanent") {
            while (true) {
                remainingMs = (expiryMs - System.currentTimeMillis()).coerceAtLeast(0L)
                delay(60_000L)
            }
        }
    }

    val cdDays  = remainingMs / (1000L * 60 * 60 * 24)
    val cdHours = (remainingMs % (1000L * 60 * 60 * 24)) / (1000L * 60 * 60)

    val cdColor = when {
        cdDays == 0L && cdHours < 6 -> Color(0xFFEF5350)
        cdDays <= 2L                 -> Color(0xFFFFB300)
        else                         -> planAccentColor
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Premium", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape    = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier            = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.premium_google_account), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    if (googleUser != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .clickable {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Email", googleUser.email))
                                    Toast.makeText(context, context.getString(R.string.premium_email_copied), Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(googleUser.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(googleUser.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        Text(
                            stringResource(R.string.premium_not_signed_in),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isPremium) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = accentGreen.copy(alpha = 0.1f)),
                    border   = BorderStroke(1.dp, accentGreen),
                    shape    = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier              = Modifier.padding(16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Star, null, tint = accentGreen, modifier = Modifier.size(28.dp))
                        Column {
                            Text(
                                stringResource(R.string.premium_already_premium),
                                fontWeight = FontWeight.Bold, fontSize = 15.sp, color = accentGreen
                            )
                            val typeLabel = when (premiumType) {
                                "permanent" -> stringResource(R.string.premium_king_no_expiry)
                                "yearly"    -> {
                                    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                    val exp = sdf.format(Date(expiryMs))
                                    stringResource(R.string.premium_plus_star_active, exp)
                                }
                                "monthly"   -> {
                                    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                    val exp = sdf.format(Date(expiryMs))
                                    stringResource(R.string.premium_plus_plus_active, exp)
                                }
                                "weekly"    -> {
                                    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                    val exp = sdf.format(Date(expiryMs))
                                    stringResource(R.string.premium_plus_active, exp)
                                }
                                else -> ""
                            }
                            Text(typeLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = planAccentColor.copy(alpha = 0.08f)),
                    border   = BorderStroke(1.5.dp, planAccentColor.copy(alpha = 0.5f)),
                    shape    = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier            = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                imageVector = when (premiumType) {
                                    "weekly"    -> Icons.Default.EventAvailable
                                    "monthly"   -> Icons.Default.CalendarToday
                                    "yearly"    -> Icons.Default.AutoAwesome
                                    "permanent" -> Icons.Default.AllInclusive
                                    else        -> Icons.Default.Star
                                },
                                contentDescription = null,
                                tint     = planAccentColor,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = when (premiumType) {
                                    "weekly"    -> stringResource(R.string.premium_package_plus)
                                    "monthly"   -> stringResource(R.string.premium_package_plus_plus)
                                    "yearly"    -> stringResource(R.string.premium_package_plus_star)
                                    "permanent" -> stringResource(R.string.premium_package_king)
                                    else        -> "Premium"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize   = 17.sp,
                                color      = planAccentColor
                            )
                        }

                        HorizontalDivider(color = planAccentColor.copy(alpha = 0.2f))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.premium_status_label), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(shape = RoundedCornerShape(6.dp), color = accentGreen.copy(alpha = 0.15f)) {
                                Text(
                                    stringResource(R.string.status_active),
                                    fontSize   = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color      = accentGreen,
                                    modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.premium_license_type), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = when (premiumType) {
                                    "weekly"    -> "Plus"
                                    "monthly"   -> "Plus+"
                                    "yearly"    -> "Plus\u2605"
                                    "permanent" -> "King"
                                    else        -> "-"
                                },
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color      = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.premium_valid_until), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = if (premiumType == "permanent") {
                                    stringResource(R.string.premium_forever)
                                } else {
                                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(expiryMs))
                                },
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color      = planAccentColor
                            )
                        }

                        if (premiumType != "permanent") {
                            HorizontalDivider(color = planAccentColor.copy(alpha = 0.15f))

                            Text(
                                stringResource(R.string.premium_time_remaining),
                                fontSize = 12.sp,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CountdownUnit(value = cdDays,  label = context.getString(R.string.premium_days_label),  color = cdColor, modifier = Modifier.weight(1f))
                                CountdownUnit(value = cdHours, label = context.getString(R.string.premium_hours_label), color = cdColor, modifier = Modifier.weight(1f))
                            }

                            if (remainingMs == 0L) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFEF5350).copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        stringResource(R.string.premium_expired_msg),
                                        fontSize = 12.sp,
                                        color    = Color(0xFFEF5350),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape    = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Diamond, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.premium_about_title), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Text(
                            stringResource(R.string.premium_about_desc),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp
                        )
                    }
                }

                Text(stringResource(R.string.premium_choose_package), fontWeight = FontWeight.Bold, fontSize = 16.sp)

                val email = googleUser?.email ?: ""

                if (email.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors   = CardDefaults.cardColors(containerColor = Color(0xFFEF5350).copy(alpha = 0.1f)),
                        border   = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f)),
                        shape    = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier              = Modifier.padding(14.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                            Text(
                                stringResource(R.string.premium_login_required_to_buy),
                                fontSize = 13.sp,
                                color    = Color(0xFFEF5350)
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PremiumPackageCard(
                        modifier    = Modifier.weight(1f),
                        title       = "Plus",
                        price       = context.getString(R.string.premium_price_weekly),
                        duration    = context.getString(R.string.premium_7days),
                        icon        = Icons.Default.EventAvailable,
                        accentColor = accentWeekly,
                        buttonLabel = context.getString(R.string.action_buy),
                        enabled     = email.isNotEmpty(),
                        onClick     = { pendingPkgType = "weekly";    pendingPkgAmount = 15000; pendingPkgLabel = "Plus (7 hari)";    showPaymentSheet = true }
                    )
                    PremiumPackageCard(
                        modifier    = Modifier.weight(1f),
                        title       = "Plus+",
                        price       = context.getString(R.string.premium_price_monthly),
                        duration    = context.getString(R.string.premium_30days),
                        icon        = Icons.Default.CalendarToday,
                        accentColor = accentMonthly,
                        buttonLabel = context.getString(R.string.action_buy),
                        enabled     = email.isNotEmpty(),
                        onClick     = { pendingPkgType = "monthly";   pendingPkgAmount = 30000; pendingPkgLabel = "Plus+ (30 hari)";  showPaymentSheet = true }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PremiumPackageCard(
                        modifier    = Modifier.weight(1f),
                        title       = "Plus\u2605",
                        price       = context.getString(R.string.premium_price_yearly),
                        duration    = context.getString(R.string.premium_365days),
                        icon        = Icons.Default.AutoAwesome,
                        accentColor = Color(0xFFFF8F00),
                        buttonLabel = context.getString(R.string.action_buy),
                        enabled     = email.isNotEmpty(),
                        onClick     = { pendingPkgType = "yearly";    pendingPkgAmount = 75000; pendingPkgLabel = "Plus★ (365 hari)"; showPaymentSheet = true }
                    )
                    PremiumPackageCard(
                        modifier    = Modifier.weight(1f),
                        title       = "King",
                        price       = context.getString(R.string.premium_price_permanent),
                        duration    = "∞",
                        icon        = Icons.Default.AllInclusive,
                        accentColor = accentPermanent,
                        buttonLabel = context.getString(R.string.action_buy),
                        enabled     = email.isNotEmpty(),
                        onClick     = { pendingPkgType = "permanent"; pendingPkgAmount = 150000; pendingPkgLabel = "King (Selamanya)"; showPaymentSheet = true }
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape    = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.premium_payment_info_title), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Text(
                            stringResource(R.string.premium_payment_info_desc),
                            fontSize   = 12.sp,
                            color      = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CountdownUnit(value: Long, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text       = String.format("%02d", value),
            fontSize   = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color      = color
        )
        Text(
            text     = label,
            fontSize = 10.sp,
            color    = color.copy(alpha = 0.75f),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PremiumPackageCard(
    modifier    : Modifier = Modifier,
    title       : String,
    price       : String,
    duration    : String,
    icon        : ImageVector,
    accentColor : Color,
    buttonLabel : String,
    enabled     : Boolean = true,
    onClick     : () -> Unit
) {
    val context = LocalContext.current

    var tiltX by remember { mutableStateOf(0f) }
    var tiltY by remember { mutableStateOf(0f) }

    val animTiltX by animateFloatAsState(targetValue = tiltX, animationSpec = spring(dampingRatio = 0.6f, stiffness = 200f), label = "tx")
    val animTiltY by animateFloatAsState(targetValue = tiltY, animationSpec = spring(dampingRatio = 0.6f, stiffness = 200f), label = "ty")

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue   = -1f,
        targetValue    = 2f,
        animationSpec  = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label          = "shimmerOffset"
    )

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                tiltY =  (event.values[0] / 9.8f).coerceIn(-1f, 1f) * 12f
                tiltX = -(event.values[1] / 9.8f).coerceIn(-1f, 1f) * 12f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    Box(
        modifier = modifier
            .aspectRatio(0.72f)
            .graphicsLayer {
                rotationX     = animTiltX
                rotationY     = animTiltY
                cameraDistance = 12f * density
            }
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.surfaceContainer,
                        accentColor.copy(alpha = 0.10f)
                    )
                )
            )
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors    = listOf(Color.Transparent, accentColor.copy(0.18f), Color.Transparent),
                        start     = androidx.compose.ui.geometry.Offset(shimmerOffset * 600f - 200f, 0f),
                        end       = androidx.compose.ui.geometry.Offset(shimmerOffset * 600f + 200f, 600f)
                    )
                )
        )

        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors  = listOf(accentColor.copy(0.08f), Color.Transparent),
                        radius  = 400f
                    )
                )
        )

        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accentColor.copy(0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = accentColor, modifier = Modifier.size(28.dp))
                }
                Text(
                    title,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize   = 15.sp,
                    color      = accentColor
                )
                Text(
                    price,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize   = 16.sp,
                    color      = MaterialTheme.colorScheme.onSurface,
                    textAlign  = TextAlign.Center
                )
                Text(
                    duration,
                    fontSize  = 11.sp,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Surface(
                shape  = RoundedCornerShape(10.dp),
                color  = if (enabled) accentColor else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    buttonLabel,
                    modifier   = Modifier.padding(vertical = 8.dp),
                    fontSize   = 13.sp,
                    color      = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )
            }
        }

        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Transparent)
        ) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                drawRoundRect(
                    color        = accentColor.copy(alpha = 0.35f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                    style        = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentMethodSheet(
    packageType   : String,
    packageLabel  : String,
    amountIdr     : Int,
    email         : String,
    context       : Context,
    navController : NavController,
    onDismiss     : () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val amountUsd  = when (packageType) {
        "weekly"    -> 1; "monthly" -> 2; "yearly" -> 5; "permanent" -> 10; else -> 1
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = MaterialTheme.colorScheme.surface,
        shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pilih Metode Pembayaran", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(
                    "Paket: $packageLabel",
                    fontSize = 13.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // ── Saweria — Indonesia ────────────────────────────────────────────
            PaymentOptionCard(
                flag        = "🇮🇩",
                name        = "Saweria",
                description = "Untuk pengguna Indonesia · Transfer Bank, GoPay, OVO, DANA, dll",
                amount      = "Rp ${"%,d".format(amountIdr).replace(',', '.')}",
                color       = Color(0xFF00ACC1),
                recommended = true,
                onClick     = {
                    onDismiss()
                    openPayment(context, email, packageType, "saweria")
                    navController.navigate("payment_pending/$packageType/${Uri.encode(email)}")
                }
            )

            // ── Sociabuzz — Global ─────────────────────────────────────────────
            PaymentOptionCard(
                flag        = "🌍",
                name        = "Sociabuzz",
                description = "For international users · Credit card, PayPal, global methods",
                amount      = "USD $$amountUsd",
                color       = Color(0xFF7B1FA2),
                recommended = false,
                onClick     = {
                    onDismiss()
                    openPayment(context, email, packageType, "sociabuzz")
                    navController.navigate("payment_pending/$packageType/${Uri.encode(email)}")
                }
            )

            // ── Info pesan ─────────────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape  = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier              = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.Top
                ) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp).padding(top = 1.dp))
                    Text(
                        "Pesan donasi sudah terisi otomatis. Jangan ubah isi pesan agar premium bisa aktif otomatis.",
                        fontSize   = 11.sp,
                        color      = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentOptionCard(
    flag        : String,
    name        : String,
    description : String,
    amount      : String,
    color       : Color,
    recommended : Boolean,
    onClick     : () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.07f)),
        border   = BorderStroke(1.5.dp, color.copy(alpha = if (recommended) 0.6f else 0.25f))
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // Flag emoji
            Box(
                modifier         = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(flag, fontSize = 22.sp)
            }

            // Info
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = color)
                    if (recommended) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = color.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "Rekomendasi",
                                fontSize   = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color      = color,
                                modifier   = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                Text(amount, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }

            Icon(Icons.Default.ChevronRight, null, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

private fun openPayment(context: Context, email: String, packageType: String, platform: String = "saweria") {
    val amountIdr = when (packageType) {
        "weekly"    -> 15000
        "monthly"   -> 30000
        "yearly"    -> 75000
        "permanent" -> 150000
        else        -> 0
    }
    val amountUsd = when (packageType) {
        "weekly"    -> 1; "monthly" -> 2; "yearly" -> 5; "permanent" -> 10; else -> 1
    }
    val message = Uri.encode("$packageType|$email")

    val url = when (platform) {
        "saweria"   -> "https://saweria.co/Javakids?amount=$amountIdr&message=$message"
        "sociabuzz" -> "https://sociabuzz.com/javakids/tribe?amount=$amountUsd&message=$message"
        else        -> "https://saweria.co/Javakids?amount=$amountIdr&message=$message"
    }

    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
