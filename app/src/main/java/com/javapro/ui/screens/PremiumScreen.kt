package com.javapro.ui.screens
import com.javapro.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
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
    val deviceId     = remember { PremiumManager.getDeviceId(context) }
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
                        Icon(Icons.Default.Fingerprint, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.premium_device_id), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                                Toast.makeText(context, context.getString(R.string.premium_device_id_copied), Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(deviceId, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        stringResource(R.string.premium_device_id_tap_hint),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PremiumPackageCard(
                        modifier    = Modifier.weight(1f),
                        title       = "Plus",
                        price = context.getString(R.string.premium_price_weekly),
                        duration = context.getString(R.string.premium_7days),
                        icon        = Icons.Default.EventAvailable,
                        accentColor = accentWeekly,
                        buttonLabel = context.getString(R.string.action_buy),
                        onClick     = {
                            val msg = Uri.encode(
                                context.getString(R.string.premium_buy_weekly_msg, deviceId)
                            )
                            openTelegram(context, msg)
                        }
                    )
                    PremiumPackageCard(
                        modifier    = Modifier.weight(1f),
                        title       = "Plus+",
                        price = context.getString(R.string.premium_price_monthly),
                        duration = context.getString(R.string.premium_30days),
                        icon        = Icons.Default.CalendarToday,
                        accentColor = accentMonthly,
                        buttonLabel = context.getString(R.string.action_buy),
                        onClick     = {
                            val msg = Uri.encode(
                                context.getString(R.string.premium_buy_monthly_msg, deviceId)
                            )
                            openTelegram(context, msg)
                        }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PremiumPackageCard(
                        modifier    = Modifier.weight(1f),
                        title       = "Plus\u2605",
                        price = context.getString(R.string.premium_price_yearly),
                        duration = context.getString(R.string.premium_365days),
                        icon        = Icons.Default.AutoAwesome,
                        accentColor = Color(0xFFFF8F00),
                        buttonLabel = context.getString(R.string.action_buy),
                        onClick     = {
                            val msg = Uri.encode(
                                context.getString(R.string.premium_buy_yearly_msg, deviceId)
                            )
                            openTelegram(context, msg)
                        }
                    )
                    PremiumPackageCard(
                        modifier    = Modifier.weight(1f),
                        title       = "King",
                        price = context.getString(R.string.premium_price_permanent),
                        duration    = "∞",
                        icon        = Icons.Default.AllInclusive,
                        accentColor = accentPermanent,
                        buttonLabel = context.getString(R.string.action_buy),
                        onClick     = {
                            val msg = Uri.encode(
                                context.getString(R.string.premium_buy_permanent_msg, deviceId)
                            )
                            openTelegram(context, msg)
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(stringResource(R.string.premium_key_activation_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape    = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.premium_activation_info),
                            fontSize = 13.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick  = {
                                val msg = Uri.encode(
                                    context.getString(R.string.premium_help_msg, deviceId)
                                )
                                openTelegram(context, msg)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape    = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Send, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_request_activation))
                        }
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
    onClick     : () -> Unit
) {
    Card(
        onClick  = onClick,
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.08f)),
        border   = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.5f)),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier            = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, null, tint = accentColor, modifier = Modifier.size(28.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = accentColor)
            Text(price, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(duration, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(8.dp), color = accentColor) {
                Text(buttonLabel, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp), fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun openTelegram(context: Context, encodedMessage: String) {
    val url = "https://t.me/Java_nih_deks?text=$encodedMessage"
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Java_nih_deks")))
    }
}
