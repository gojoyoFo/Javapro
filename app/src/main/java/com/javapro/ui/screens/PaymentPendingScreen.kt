package com.javapro.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.utils.PremiumManager
import kotlinx.coroutines.delay
import java.util.Calendar

// ── Warna ────────────────────────────────────────────────────────────────────
private val ColorPending  = Color(0xFFFFB300)
private val ColorSuccess  = Color(0xFF4CAF50)
private val ColorInfo     = Color(0xFF29B6F6)
private val ColorWarning  = Color(0xFFEF5350)

/**
 * PaymentPendingScreen — ditampilkan setelah user kembali dari Saweria/Sociabuzz.
 *
 * Navigasi: PremiumScreen → PaymentPendingScreen (setelah openPayment)
 * Route   : "payment_pending/{packageType}/{email}"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentPendingScreen(
    navController : NavController,
    packageType   : String,
    email         : String,
    lang          : String
) {
    val context = LocalContext.current

    // ── State ─────────────────────────────────────────────────────────────────
    var checkState  by remember { mutableStateOf<CheckState>(CheckState.Idle) }
    var isPremium   by remember { mutableStateOf(false) }
    var checkCount  by remember { mutableIntStateOf(0) }
    val maxChecks   = 5

    // ── Jam operasional ───────────────────────────────────────────────────────
    val isOffHours       = remember { isOutsideOperationalHours() }
    val isWeekend        = remember { isWeekend() }
    // Weekend bisa cepat, yang lambat hanya di luar jam 07–15 hari kerja
    val showDelayWarning = isOffHours && !isWeekend

    // ── Animasi pulse ─────────────────────────────────────────────────────────
    val infiniteAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteAnim.animateFloat(
        initialValue  = 0.95f,
        targetValue   = 1.05f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse),
        label         = "scale"
    )

    // ── Auto-check saat masuk screen ──────────────────────────────────────────
    LaunchedEffect(Unit) {
        delay(2000)
        repeat(maxChecks) { attempt ->
            checkState  = CheckState.Checking
            checkCount  = attempt + 1
            val result  = PremiumManager.checkOnline(context)
            if (result) {
                isPremium  = true
                checkState = CheckState.Success
                return@LaunchedEffect
            }
            if (attempt < maxChecks - 1) {
                checkState = CheckState.Waiting
                delay(8000)
            }
        }
        if (!isPremium) checkState = CheckState.NotFound
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Status Pembayaran", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement    = Arrangement.spacedBy(16.dp),
            horizontalAlignment    = Alignment.CenterHorizontally
        ) {

            // ── Status utama ──────────────────────────────────────────────────
            when (checkState) {
                CheckState.Idle, CheckState.Checking, CheckState.Waiting -> {
                    PendingStatusCard(
                        pulseScale   = if (checkState == CheckState.Checking) pulseScale else 1f,
                        packageType  = packageType,
                        email        = email,
                        checkCount   = checkCount,
                        maxChecks    = maxChecks,
                        isChecking   = checkState == CheckState.Checking
                    )
                }
                CheckState.Success -> {
                    SuccessCard(email = email, packageType = packageType)
                }
                CheckState.NotFound -> {
                    NotFoundCard(email = email)
                }
            }

            // ── Warning jam operasional ───────────────────────────────────────
            if (showDelayWarning && checkState !is CheckState.Success) {
                OperationalHoursCard()
            }

            // ── Langkah-langkah ───────────────────────────────────────────────
            if (checkState !is CheckState.Success) {
                StepsCard(email = email, packageType = packageType)
            }

            // ── Tombol cek ulang manual ───────────────────────────────────────
            if (checkState == CheckState.NotFound) {
                Button(
                    onClick = {
                        checkCount = 0
                        checkState = CheckState.Idle
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cek Ulang Sekarang")
                }
                OutlinedButton(
                    onClick  = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Text("Kembali ke Halaman Premium")
                }
            }

            if (checkState == CheckState.Success) {
                Button(
                    onClick  = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = ColorSuccess)
                ) {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Mulai Pakai JavaPro Pro!")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun PendingStatusCard(
    pulseScale  : Float,
    packageType : String,
    email       : String,
    checkCount  : Int,
    maxChecks   : Int,
    isChecking  : Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        colors   = CardDefaults.cardColors(containerColor = ColorPending.copy(alpha = 0.08f)),
        border   = androidx.compose.foundation.BorderStroke(1.dp, ColorPending.copy(alpha = 0.3f))
    ) {
        Column(
            modifier            = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Ikon pulse
            Box(
                modifier        = Modifier
                    .scale(pulseScale)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(ColorPending.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        color     = ColorPending,
                        modifier  = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(Icons.Default.HourglassTop, null, tint = ColorPending, modifier = Modifier.size(36.dp))
                }
            }

            Text(
                text       = if (isChecking) "Memeriksa pembayaran..." else "Menunggu konfirmasi",
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                color      = ColorPending,
                textAlign  = TextAlign.Center
            )

            Text(
                text      = "Pembayaran kamu sedang diproses oleh sistem. Ini biasanya memakan waktu beberapa detik hingga beberapa menit.",
                fontSize  = 13.sp,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )

            // Progress pengecekan
            if (checkCount > 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LinearProgressIndicator(
                        progress       = { checkCount.toFloat() / maxChecks },
                        modifier       = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                        color          = ColorPending,
                        trackColor     = ColorPending.copy(alpha = 0.15f)
                    )
                    Text(
                        "Pengecekan ke-$checkCount dari $maxChecks",
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Info paket
            InfoRow(Icons.Default.Diamond, "Paket", packageType.replaceFirstChar { it.uppercase() }, ColorPending)
            InfoRow(Icons.Default.Email,   "Email", email, ColorPending)
        }
    }
}

@Composable
private fun SuccessCard(email: String, packageType: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        colors   = CardDefaults.cardColors(containerColor = ColorSuccess.copy(alpha = 0.08f)),
        border   = androidx.compose.foundation.BorderStroke(1.dp, ColorSuccess.copy(alpha = 0.3f))
    ) {
        Column(
            modifier            = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(ColorSuccess.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = ColorSuccess, modifier = Modifier.size(40.dp))
            }

            Text(
                "Premium Aktif! 🎉",
                fontSize   = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color      = ColorSuccess,
                textAlign  = TextAlign.Center
            )

            Text(
                "Terima kasih! Paket ${packageType.replaceFirstChar { it.uppercase() }} kamu sudah aktif. Selamat menikmati semua fitur JavaPro Pro!",
                fontSize   = 13.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign  = TextAlign.Center,
                lineHeight = 19.sp
            )

            InfoRow(Icons.Default.Email, "Email", email, ColorSuccess)
        }
    }
}

@Composable
private fun NotFoundCard(email: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        colors   = CardDefaults.cardColors(containerColor = ColorWarning.copy(alpha = 0.08f)),
        border   = androidx.compose.foundation.BorderStroke(1.dp, ColorWarning.copy(alpha = 0.3f))
    ) {
        Column(
            modifier            = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = ColorWarning, modifier = Modifier.size(40.dp))

            Text(
                "Pembayaran belum terdeteksi",
                fontSize   = 16.sp,
                fontWeight = FontWeight.Bold,
                color      = ColorWarning,
                textAlign  = TextAlign.Center
            )

            Text(
                "Jangan panik! Kalau kamu sudah bayar, pembayaran akan tetap diproses. Coba cek ulang beberapa menit lagi.",
                fontSize   = 13.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign  = TextAlign.Center,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun OperationalHoursCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFFFF6F00).copy(alpha = 0.08f)),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6F00).copy(alpha = 0.35f))
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.Top
        ) {
            Icon(Icons.Default.AccessTime, null, tint = Color(0xFFFF8F00),
                modifier = Modifier.size(22.dp).padding(top = 2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "⚠️ Di luar jam operasional",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 13.sp,
                    color      = Color(0xFFFF8F00)
                )
                Text(
                    "Pembayaran yang masuk di luar jam 07.00–15.00 WIB (Senin–Jumat) akan diproses pada hari kerja berikutnya mulai jam 07.00 WIB.\n\nSabtu & Minggu proses bisa lebih cepat.\n\nPremium kamu tetap akan aktif, tenang saja!",
                    fontSize   = 12.sp,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun StepsCard(email: String, packageType: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Info, null, tint = ColorInfo, modifier = Modifier.size(18.dp))
                Text("Cara kerja sistem", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            StepItem(
                number = "1",
                text   = "Kamu transfer di Saweria/Sociabuzz dengan pesan yang sudah terisi otomatis",
                color  = ColorInfo
            )
            StepItem(
                number = "2",
                text   = "Sistem menerima notifikasi pembayaran dari Saweria/Sociabuzz secara otomatis",
                color  = ColorInfo
            )
            StepItem(
                number = "3",
                text   = "Email $email langsung di-grant premium paket ${packageType.replaceFirstChar { it.uppercase() }}",
                color  = ColorInfo
            )
            StepItem(
                number = "4",
                text   = "Buka ulang halaman Premium di JavaPro untuk memuat status terbaru",
                color  = ColorInfo
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                "💡 Pastikan pesan donasi tidak diubah agar email kamu terdeteksi otomatis oleh sistem.",
                fontSize   = 11.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun StepItem(number: String, text: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment     = Alignment.Top
    ) {
        Box(
            modifier         = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, color: Color) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

// ── State ─────────────────────────────────────────────────────────────────────

private sealed class CheckState {
    data object Idle     : CheckState()
    data object Checking : CheckState()
    data object Waiting  : CheckState()
    data object Success  : CheckState()
    data object NotFound : CheckState()
}

// ── Helpers ───────────────────────────────────────────────────────────────────

// Jam operasional cepat: 07.00–15.00 WIB, Senin–Jumat
// Sabtu–Minggu: bisa cepat (owner aktif)
// Di luar jam 07–15 hari kerja: akan diproses jam 07.00 berikutnya
private fun isOutsideOperationalHours(): Boolean {
    val cal     = Calendar.getInstance()
    val hour    = cal.get(Calendar.HOUR_OF_DAY)
    val dayOfW  = cal.get(Calendar.DAY_OF_WEEK)
    val isWeekday = dayOfW in Calendar.MONDAY..Calendar.FRIDAY
    // Di luar jam 07–15 di hari kerja = lambat
    return isWeekday && (hour < 7 || hour >= 15)
}

private fun isWeekend(): Boolean {
    val cal = Calendar.getInstance()
    val day = cal.get(Calendar.DAY_OF_WEEK)
    return day == Calendar.SATURDAY || day == Calendar.SUNDAY
}
