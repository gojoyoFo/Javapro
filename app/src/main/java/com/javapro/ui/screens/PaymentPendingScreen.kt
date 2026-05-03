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

private val ColorPending = Color(0xFFFFB300)
private val ColorSuccess = Color(0xFF4CAF50)
private val ColorInfo    = Color(0xFF29B6F6)
private val ColorWarning = Color(0xFFEF5350)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentPendingScreen(
    navController : NavController,
    packageType   : String,
    email         : String,
    lang          : String
) {
    val context = LocalContext.current

    var checkState by remember { mutableStateOf<CheckState>(CheckState.Idle) }
    var isPremium  by remember { mutableStateOf(false) }
    var checkCount by remember { mutableIntStateOf(0) }
    val maxChecks  = 5

    val isOffHours       = remember { isOutsideOperationalHours() }
    val isWeekend        = remember { isWeekend() }
    val showDelayWarning = isOffHours && !isWeekend

    val infiniteAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteAnim.animateFloat(
        initialValue  = 0.95f,
        targetValue   = 1.05f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse),
        label         = "scale"
    )

    LaunchedEffect(Unit) {
        delay(2000)
        repeat(maxChecks) { attempt ->
            checkState = CheckState.Checking
            checkCount = attempt + 1
            val result = PremiumManager.checkOnline(context)
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

    val strTitle = when (lang) {
        "id"  -> "Status Pembayaran"
        "zh"  -> "付款状态"
        "hi"  -> "भुगतान स्थिति"
        "fil" -> "Status ng Bayad"
        else  -> "Payment Status"
    }
    val strCheckAgain = when (lang) {
        "id"  -> "Cek Ulang Sekarang"
        "zh"  -> "立即重新检查"
        "hi"  -> "अभी पुनः जांचें"
        "fil" -> "Suriin Muli Ngayon"
        else  -> "Check Again Now"
    }
    val strBackToPremium = when (lang) {
        "id"  -> "Kembali ke Halaman Premium"
        "zh"  -> "返回高级页面"
        "hi"  -> "प्रीमियम पेज पर वापस जाएं"
        "fil" -> "Bumalik sa Premium Page"
        else  -> "Back to Premium Page"
    }
    val strStartUsing = when (lang) {
        "id"  -> "Mulai Pakai JavaPro Pro!"
        "zh"  -> "开始使用 JavaPro Pro！"
        "hi"  -> "JavaPro Pro का उपयोग शुरू करें!"
        "fil" -> "Simulan Gamitin ang JavaPro Pro!"
        else  -> "Start Using JavaPro Pro!"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strTitle, fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (checkState) {
                CheckState.Idle, CheckState.Checking, CheckState.Waiting -> {
                    PendingStatusCard(
                        pulseScale  = if (checkState == CheckState.Checking) pulseScale else 1f,
                        packageType = packageType,
                        email       = email,
                        checkCount  = checkCount,
                        maxChecks   = maxChecks,
                        isChecking  = checkState == CheckState.Checking,
                        lang        = lang
                    )
                }
                CheckState.Success -> {
                    SuccessCard(email = email, packageType = packageType, lang = lang)
                }
                CheckState.NotFound -> {
                    NotFoundCard(email = email, lang = lang)
                }
            }

            if (showDelayWarning && checkState !is CheckState.Success) {
                OperationalHoursCard(lang = lang)
            }

            if (checkState !is CheckState.Success) {
                StepsCard(email = email, packageType = packageType, lang = lang)
            }

            if (checkState == CheckState.NotFound) {
                Button(
                    onClick  = { checkCount = 0; checkState = CheckState.Idle },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strCheckAgain)
                }
                OutlinedButton(
                    onClick  = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Text(strBackToPremium)
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
                    Text(strStartUsing)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PendingStatusCard(
    pulseScale  : Float,
    packageType : String,
    email       : String,
    checkCount  : Int,
    maxChecks   : Int,
    isChecking  : Boolean,
    lang        : String
) {
    val strChecking = when (lang) {
        "id"  -> "Memeriksa pembayaran..."
        "zh"  -> "正在检查付款..."
        "hi"  -> "भुगतान जाँच रहे हैं..."
        "fil" -> "Sinusuri ang bayad..."
        else  -> "Checking payment..."
    }
    val strWaiting = when (lang) {
        "id"  -> "Menunggu konfirmasi"
        "zh"  -> "等待确认"
        "hi"  -> "पुष्टि की प्रतीक्षा"
        "fil" -> "Naghihintay ng kumpirmasyon"
        else  -> "Waiting for confirmation"
    }
    val strDesc = when (lang) {
        "id"  -> "Pembayaran kamu sedang diproses oleh sistem. Ini biasanya memakan waktu beberapa detik hingga beberapa menit."
        "zh"  -> "您的付款正在系统处理中。通常需要几秒钟到几分钟。"
        "hi"  -> "आपका भुगतान सिस्टम द्वारा प्रोसेस किया जा रहा है। इसमें कुछ सेकंड से लेकर कुछ मिनट लग सकते हैं।"
        "fil" -> "Pinoproseso ng system ang iyong bayad. Karaniwang tumatagal ng ilang segundo hanggang ilang minuto."
        else  -> "Your payment is being processed by the system. This usually takes a few seconds to a few minutes."
    }
    val strCheckOf = when (lang) {
        "id"  -> "Pengecekan ke-$checkCount dari $maxChecks"
        "zh"  -> "检查 $checkCount / $maxChecks"
        "hi"  -> "जाँच $checkCount / $maxChecks"
        "fil" -> "Pagsusuri $checkCount sa $maxChecks"
        else  -> "Check $checkCount of $maxChecks"
    }
    val strPackage = when (lang) {
        "id"  -> "Paket"
        "zh"  -> "套餐"
        "hi"  -> "पैकेज"
        "fil" -> "Pakete"
        else  -> "Package"
    }

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
            Box(
                modifier         = Modifier
                    .scale(pulseScale)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(ColorPending.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                if (isChecking) {
                    CircularProgressIndicator(color = ColorPending, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                } else {
                    Icon(Icons.Default.HourglassTop, null, tint = ColorPending, modifier = Modifier.size(36.dp))
                }
            }

            Text(
                text       = if (isChecking) strChecking else strWaiting,
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                color      = ColorPending,
                textAlign  = TextAlign.Center
            )

            Text(
                text       = strDesc,
                fontSize   = 13.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign  = TextAlign.Center,
                lineHeight = 19.sp
            )

            if (checkCount > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress   = { checkCount.toFloat() / maxChecks },
                        modifier   = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                        color      = ColorPending,
                        trackColor = ColorPending.copy(alpha = 0.15f)
                    )
                    Text(strCheckOf, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            InfoRow(Icons.Default.Diamond, strPackage, packageType.replaceFirstChar { it.uppercase() }, ColorPending)
            InfoRow(Icons.Default.Email, "Email", email, ColorPending)
        }
    }
}

@Composable
private fun SuccessCard(email: String, packageType: String, lang: String) {
    val strTitle = when (lang) {
        "id"  -> "Premium Aktif! 🎉"
        "zh"  -> "高级版已激活！🎉"
        "hi"  -> "प्रीमियम सक्रिय! 🎉"
        "fil" -> "Premium Aktibo Na! 🎉"
        else  -> "Premium Active! 🎉"
    }
    val strDesc = when (lang) {
        "id"  -> "Terima kasih! Paket ${packageType.replaceFirstChar { it.uppercase() }} kamu sudah aktif. Selamat menikmati semua fitur JavaPro Pro!"
        "zh"  -> "感谢您！您的 ${packageType.replaceFirstChar { it.uppercase() }} 套餐已激活。享受 JavaPro Pro 的所有功能！"
        "hi"  -> "धन्यवाद! आपका ${packageType.replaceFirstChar { it.uppercase() }} पैकेज सक्रिय हो गया है। JavaPro Pro की सभी सुविधाओं का आनंद लें!"
        "fil" -> "Salamat! Ang iyong ${packageType.replaceFirstChar { it.uppercase() }} package ay aktibo na. I-enjoy ang lahat ng features ng JavaPro Pro!"
        else  -> "Thank you! Your ${packageType.replaceFirstChar { it.uppercase() }} package is now active. Enjoy all JavaPro Pro features!"
    }

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
                modifier         = Modifier.size(72.dp).clip(CircleShape).background(ColorSuccess.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = ColorSuccess, modifier = Modifier.size(40.dp))
            }

            Text(strTitle, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = ColorSuccess, textAlign = TextAlign.Center)

            Text(strDesc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 19.sp)

            InfoRow(Icons.Default.Email, "Email", email, ColorSuccess)
        }
    }
}

@Composable
private fun NotFoundCard(email: String, lang: String) {
    val strTitle = when (lang) {
        "id"  -> "Pembayaran belum terdeteksi"
        "zh"  -> "未检测到付款"
        "hi"  -> "भुगतान अभी तक नहीं मिला"
        "fil" -> "Hindi pa natukoy ang bayad"
        else  -> "Payment not yet detected"
    }
    val strDesc = when (lang) {
        "id"  -> "Jangan panik! Kalau kamu sudah bayar, pembayaran akan tetap diproses. Coba cek ulang beberapa menit lagi."
        "zh"  -> "别慌！如果您已付款，款项仍将处理。请几分钟后再次检查。"
        "hi"  -> "घबराएं नहीं! यदि आपने भुगतान किया है तो यह प्रोसेस होगा। कुछ मिनट बाद फिर से जाँचें।"
        "fil" -> "Huwag mag-alala! Kung nagbayad ka na, ipoproseso pa rin ito. Subukan muli pagkatapos ng ilang minuto."
        else  -> "Don't panic! If you already paid, it will still be processed. Try checking again in a few minutes."
    }

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
            Text(strTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ColorWarning, textAlign = TextAlign.Center)
            Text(strDesc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun OperationalHoursCard(lang: String) {
    val strHeader = when (lang) {
        "id"  -> "⚠️ Di luar jam operasional"
        "zh"  -> "⚠️ 非营业时间"
        "hi"  -> "⚠️ परिचालन घंटों के बाहर"
        "fil" -> "⚠️ Labas ng oras ng operasyon"
        else  -> "⚠️ Outside operational hours"
    }
    val strBody = when (lang) {
        "id"  -> "Pembayaran yang masuk di luar jam 07.00–15.00 WIB (Senin–Jumat) akan diproses pada hari kerja berikutnya mulai jam 07.00 WIB.\n\nSabtu & Minggu proses bisa lebih cepat.\n\nPremium kamu tetap akan aktif, tenang saja!"
        "zh"  -> "在工作日 07:00–15:00（WIB）以外收到的付款将在下一个工作日从 07:00 开始处理。\n\n周六和周日处理可能更快。\n\n您的高级版最终会激活，放心！"
        "hi"  -> "कार्यदिवसों में 07:00–15:00 WIB के बाहर प्राप्त भुगतान अगले कार्यदिवस 07:00 WIB से प्रोसेस किए जाएंगे।\n\nशनिवार और रविवार को प्रोसेसिंग तेज हो सकती है।\n\nआपका प्रीमियम जरूर सक्रिय होगा, चिंता न करें!"
        "fil" -> "Ang mga bayad na natanggap sa labas ng 07:00–15:00 WIB (Lunes–Biyernes) ay ipoproseso sa susunod na araw ng trabaho mula 07:00 WIB.\n\nMaaaring mas mabilis ang proseso sa Sabado at Linggo.\n\nMa-a-activate pa rin ang iyong premium, huwag mag-alala!"
        else  -> "Payments received outside 07:00–15:00 WIB (Mon–Fri) will be processed on the next business day from 07:00 WIB.\n\nSaturday & Sunday processing may be faster.\n\nYour premium will still be activated, don't worry!"
    }

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
            Icon(Icons.Default.AccessTime, null, tint = Color(0xFFFF8F00), modifier = Modifier.size(22.dp).padding(top = 2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strHeader, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFFF8F00))
                Text(strBody, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun StepsCard(email: String, packageType: String, lang: String) {
    val strHowIt = when (lang) {
        "id"  -> "Cara kerja sistem"
        "zh"  -> "系统工作原理"
        "hi"  -> "सिस्टम कैसे काम करता है"
        "fil" -> "Paano gumagana ang system"
        else  -> "How the system works"
    }
    val steps = when (lang) {
        "id" -> listOf(
            "Kamu transfer di Saweria/Sociabuzz dengan pesan yang sudah terisi otomatis",
            "Sistem menerima notifikasi pembayaran dari Saweria/Sociabuzz secara otomatis",
            "Email $email langsung di-grant premium paket ${packageType.replaceFirstChar { it.uppercase() }}",
            "Buka ulang halaman Premium di JavaPro untuk memuat status terbaru"
        )
        "zh" -> listOf(
            "您在 Saweria/Sociabuzz 进行转账，消息已自动填写",
            "系统自动接收来自 Saweria/Sociabuzz 的付款通知",
            "电子邮件 $email 立即获得 ${packageType.replaceFirstChar { it.uppercase() }} 套餐高级权限",
            "重新打开 JavaPro 中的高级页面以加载最新状态"
        )
        "hi" -> listOf(
            "आप Saweria/Sociabuzz पर स्वचालित रूप से भरे संदेश के साथ ट्रांसफर करें",
            "सिस्टम Saweria/Sociabuzz से स्वचालित रूप से भुगतान सूचना प्राप्त करता है",
            "ईमेल $email को तुरंत ${packageType.replaceFirstChar { it.uppercase() }} पैकेज का प्रीमियम मिलता है",
            "नवीनतम स्थिति लोड करने के लिए JavaPro में Premium पेज फिर से खोलें"
        )
        "fil" -> listOf(
            "Mag-transfer ka sa Saweria/Sociabuzz gamit ang awtomatikong nalagay na mensahe",
            "Awtomatikong natatanggap ng system ang notification ng bayad mula Saweria/Sociabuzz",
            "Ang email $email ay agad na bibigyan ng premium na ${packageType.replaceFirstChar { it.uppercase() }} package",
            "Buksan muli ang Premium page sa JavaPro para i-load ang pinakabagong status"
        )
        else -> listOf(
            "Transfer on Saweria/Sociabuzz with the automatically filled message",
            "The system automatically receives payment notifications from Saweria/Sociabuzz",
            "Email $email is immediately granted ${packageType.replaceFirstChar { it.uppercase() }} premium package",
            "Reopen the Premium page in JavaPro to load the latest status"
        )
    }
    val strTip = when (lang) {
        "id"  -> "💡 Pastikan pesan donasi tidak diubah agar email kamu terdeteksi otomatis oleh sistem."
        "zh"  -> "💡 请确保不要更改捐款消息，以便系统自动检测您的电子邮件。"
        "hi"  -> "💡 सुनिश्चित करें कि डोनेशन संदेश न बदलें ताकि सिस्टम आपका ईमेल स्वचालित रूप से पहचान सके।"
        "fil" -> "💡 Siguraduhing hindi binabago ang mensahe ng donasyon para awtomatikong matukoy ng system ang iyong email."
        else  -> "💡 Make sure the donation message is not changed so the system can automatically detect your email."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Info, null, tint = ColorInfo, modifier = Modifier.size(18.dp))
                Text(strHowIt, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            steps.forEachIndexed { index, text ->
                StepItem(number = "${index + 1}", text = text, color = ColorInfo)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(strTip, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun StepItem(number: String, text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier         = Modifier.size(22.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
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
        modifier              = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.07f)).padding(horizontal = 12.dp, vertical = 8.dp),
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

private sealed class CheckState {
    data object Idle     : CheckState()
    data object Checking : CheckState()
    data object Waiting  : CheckState()
    data object Success  : CheckState()
    data object NotFound : CheckState()
}

private fun isOutsideOperationalHours(): Boolean {
    val cal      = Calendar.getInstance()
    val hour     = cal.get(Calendar.HOUR_OF_DAY)
    val dayOfW   = cal.get(Calendar.DAY_OF_WEEK)
    val isWeekday = dayOfW in Calendar.MONDAY..Calendar.FRIDAY
    return isWeekday && (hour < 7 || hour >= 15)
}

private fun isWeekend(): Boolean {
    val cal = Calendar.getInstance()
    val day = cal.get(Calendar.DAY_OF_WEEK)
    return day == Calendar.SATURDAY || day == Calendar.SUNDAY
}
