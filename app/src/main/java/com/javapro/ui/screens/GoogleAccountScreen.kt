package com.javapro.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.javapro.R
import com.javapro.utils.GoogleAuthManager
import com.javapro.utils.PremiumManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleAccountScreen(
    navController : NavController,
    lang          : String
) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()

    var user         by remember { mutableStateOf(GoogleAuthManager.getUser(context)) }
    val isPremium    = remember { PremiumManager.isPremium(context) }
    val premiumType  = remember { PremiumManager.getPremiumType(context) }
    val expiryMs     = remember { PremiumManager.getExpiryMs(context) }

    val avatarFile          = remember { java.io.File(context.filesDir, "custom_avatar.jpg") }
    val avatarPrefs         = remember { context.getSharedPreferences("avatar_prefs", android.content.Context.MODE_PRIVATE) }
    var avatarVersion       by remember { mutableLongStateOf(if (avatarFile.exists()) avatarFile.lastModified() else 0L) }
    var customDisplayName   by remember { mutableStateOf(avatarPrefs.getString("custom_display_name", null)) }
    var isSigningIn         by remember { mutableStateOf(false) }
    var showSignOutDialog   by remember { mutableStateOf(false) }
    var showEditNameDialog  by remember { mutableStateOf(false) }

    val avatarPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    avatarFile.outputStream().use { output -> input.copyTo(output) }
                }
                avatarVersion = System.currentTimeMillis()
            } catch (_: Exception) { }
        }
    }

    val strTitle = when (lang) {
        "id"  -> "Akun Google"
        "zh"  -> "Google 账户"
        "hi"  -> "Google खाता"
        "fil" -> "Google Account"
        else  -> "Google Account"
    }
    val strSignOut = when (lang) {
        "id"  -> "Keluar"
        "zh"  -> "退出"
        "hi"  -> "साइन आउट"
        "fil" -> "Mag-sign out"
        else  -> "Sign Out"
    }
    val strSignOutTitle = when (lang) {
        "id"  -> "Keluar dari Akun?"
        "zh"  -> "退出账户？"
        "hi"  -> "खाते से साइन आउट करें?"
        "fil" -> "Mag-sign out sa Account?"
        else  -> "Sign Out of Account?"
    }
    val strSignOutDesc = when (lang) {
        "id"  -> "Status premium kamu akan dicek ulang saat login berikutnya."
        "zh"  -> "您的高级状态将在下次登录时重新检查。"
        "hi"  -> "आपकी प्रीमियम स्थिति अगले लॉगिन पर पुनः जांची जाएगी।"
        "fil" -> "Ang iyong premium status ay muling susuriin sa susunod na pag-login."
        else  -> "Your premium status will be re-checked on next login."
    }
    val strCancel = when (lang) {
        "id"  -> "Batal"
        "zh"  -> "取消"
        "hi"  -> "रद्द करें"
        "fil" -> "Kanselahin"
        else  -> "Cancel"
    }
    val strChangeAvatar = when (lang) {
        "id"  -> "Ganti Avatar"
        "zh"  -> "更换头像"
        "hi"  -> "अवतार बदलें"
        "fil" -> "Palitan ang Avatar"
        else  -> "Change Avatar"
    }
    val strEditName = when (lang) {
        "id"  -> "Ganti Nama"
        "zh"  -> "更改名称"
        "hi"  -> "नाम बदलें"
        "fil" -> "Palitan ang Pangalan"
        else  -> "Change Name"
    }
    val strEditNameHint = when (lang) {
        "id"  -> "Nama ini hanya ditampilkan di aplikasi."
        "zh"  -> "此名称仅在应用中显示。"
        "hi"  -> "यह नाम केवल ऐप में दिखाया जाएगा।"
        "fil" -> "Ang pangalang ito ay ipapakita lamang sa app."
        else  -> "This name is only displayed in the app."
    }
    val strEditNameLabel = when (lang) {
        "id"  -> "Nama"
        "zh"  -> "名称"
        "hi"  -> "नाम"
        "fil" -> "Pangalan"
        else  -> "Name"
    }
    val strSave = when (lang) {
        "id"  -> "Simpan"
        "zh"  -> "保存"
        "hi"  -> "सहेजें"
        "fil" -> "I-save"
        else  -> "Save"
    }
    val strNotLoggedIn = when (lang) {
        "id"  -> "Belum Login"
        "zh"  -> "未登录"
        "hi"  -> "लॉगिन नहीं हुए"
        "fil" -> "Hindi pa naka-login"
        else  -> "Not Logged In"
    }
    val strLoginGoogle = when (lang) {
        "id"  -> "Login dengan Google"
        "zh"  -> "使用 Google 登录"
        "hi"  -> "Google से लॉगिन करें"
        "fil" -> "Mag-login gamit ang Google"
        else  -> "Login with Google"
    }
    val strLoginDesc = when (lang) {
        "id"  -> "Hubungkan akun Google untuk verifikasi status premium JavaPro kamu."
        "zh"  -> "连接您的 Google 账户以验证您的 JavaPro 高级状态。"
        "hi"  -> "JavaPro प्रीमियम स्थिति सत्यापित करने के लिए Google खाता कनेक्ट करें।"
        "fil" -> "I-connect ang iyong Google account para i-verify ang iyong JavaPro premium status."
        else  -> "Connect your Google account to verify your JavaPro premium status."
    }
    val strStatusSection = when (lang) {
        "id"  -> "Status JavaPro"
        "zh"  -> "JavaPro 状态"
        "hi"  -> "JavaPro स्थिति"
        "fil" -> "JavaPro Status"
        else  -> "JavaPro Status"
    }
    val strAccountInfo = when (lang) {
        "id"  -> "Info Akun"
        "zh"  -> "账户信息"
        "hi"  -> "खाता जानकारी"
        "fil" -> "Account Info"
        else  -> "Account Info"
    }
    val strEmail = when (lang) {
        "id"  -> "Email"
        "zh"  -> "电子邮件"
        "hi"  -> "ईमेल"
        "fil" -> "Email"
        else  -> "Email"
    }
    val strName = when (lang) {
        "id"  -> "Nama"
        "zh"  -> "姓名"
        "hi"  -> "नाम"
        "fil" -> "Pangalan"
        else  -> "Name"
    }
    val strExpiry = when (lang) {
        "id"  -> "Berlaku hingga"
        "zh"  -> "有效期至"
        "hi"  -> "तक वैध"
        "fil" -> "Valid hanggang"
        else  -> "Valid until"
    }
    val strPermanent = when (lang) {
        "id"  -> "Selamanya"
        "zh"  -> "永久"
        "hi"  -> "स्थायी"
        "fil" -> "Permanente"
        else  -> "Permanent"
    }
    val strFree = when (lang) {
        "id"  -> "Gratis"
        "zh"  -> "免费"
        "hi"  -> "मुफ़्त"
        "fil" -> "Libre"
        else  -> "Free"
    }
    val strReLogin = when (lang) {
        "id"  -> "Login Ulang"
        "zh"  -> "重新登录"
        "hi"  -> "पुनः लॉगिन करें"
        "fil" -> "Mag-login Ulit"
        else  -> "Re-Login"
    }
    val strReLoginDesc = when (lang) {
        "id"  -> "Perbarui sesi akun Google kamu"
        "zh"  -> "刷新您的 Google 账户会话"
        "hi"  -> "अपना Google खाता सत्र रीफ्रेश करें"
        "fil" -> "I-refresh ang iyong Google account session"
        else  -> "Refresh your Google account session"
    }
    val strRefreshPremium = when (lang) {
        "id"  -> "Cek Status Premium"
        "zh"  -> "检查高级状态"
        "hi"  -> "प्रीमियम स्थिति जांचें"
        "fil" -> "I-check ang Premium Status"
        else  -> "Check Premium Status"
    }
    val strRefreshPremiumDesc = when (lang) {
        "id"  -> "Sinkronisasi status premium dengan server"
        "zh"  -> "与服务器同步高级状态"
        "hi"  -> "सर्वर के साथ प्रीमियम स्थिति सिंक्रनाइज़ करें"
        "fil" -> "I-sync ang premium status sa server"
        else  -> "Sync premium status with server"
    }

    val premiumLabel = when (premiumType) {
        "weekly"    -> "PLUS VERSION"
        "monthly"   -> "PLUS+ VERSION"
        "yearly"    -> "PLUS★ VERSION"
        "permanent" -> "KING VERSION"
        else        -> "FREE VERSION"
    }

    val expiryText = when {
        !isPremium          -> "-"
        premiumType == "permanent" -> strPermanent
        else -> {
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            sdf.format(Date(expiryMs))
        }
    }

    // ── Dialog Ganti Nama ─────────────────────────────────────────────────────
    if (showEditNameDialog) {
        var nameInput by remember { mutableStateOf(customDisplayName ?: user?.displayName ?: "") }
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            shape            = RoundedCornerShape(24.dp),
            containerColor   = MaterialTheme.colorScheme.surface,
            title = { Text(strEditName, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strEditNameHint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value         = nameInput,
                        onValueChange = { if (it.length <= 30) nameInput = it },
                        singleLine    = true,
                        label         = { Text(strEditNameLabel) },
                        shape         = RoundedCornerShape(14.dp),
                        modifier      = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = nameInput.trim()
                        if (trimmed.isNotEmpty()) {
                            avatarPrefs.edit().putString("custom_display_name", trimmed).apply()
                            customDisplayName = trimmed
                        }
                        showEditNameDialog = false
                    },
                    shape = RoundedCornerShape(50.dp)
                ) { Text(strSave, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showEditNameDialog = false },
                    shape   = RoundedCornerShape(50.dp)
                ) { Text(strCancel) }
            }
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            shape            = RoundedCornerShape(24.dp),
            containerColor   = MaterialTheme.colorScheme.surface,
            icon = {
                Box(
                    modifier         = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                }
            },
            title = { Text(text = strSignOutTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text  = { Text(text = strSignOutDesc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutDialog = false
                        scope.launch {
                            GoogleAuthManager.signOut(context)
                            user = null
                            navController.popBackStack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    shape  = RoundedCornerShape(50.dp)
                ) {
                    Text(strSignOut, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSignOutDialog = false }, shape = RoundedCornerShape(50.dp)) {
                    Text(strCancel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(text = strTitle, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (user != null) {
                        IconButton(onClick = { showSignOutDialog = true }) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (user != null) {

                Box(
                    modifier         = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(contentAlignment = Alignment.BottomEnd) {
                            Box(
                                modifier         = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                if (avatarVersion > 0L) {
                                    AsyncImage(
                                        model = coil.request.ImageRequest.Builder(context)
                                            .data(Uri.fromFile(avatarFile))
                                            .memoryCacheKey("custom_avatar_$avatarVersion")
                                            .diskCacheKey("custom_avatar_$avatarVersion")
                                            .build(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else if (user!!.photoUrl != null) {
                                    AsyncImage(model = user!!.photoUrl, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape))
                                } else {
                                    Icon(imageVector = Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(52.dp))
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { avatarPickerLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                            }
                        }

                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text       = customDisplayName ?: user!!.displayName,
                                fontSize   = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color      = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector        = Icons.Default.Edit,
                                contentDescription = strEditName,
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier           = Modifier
                                    .size(18.dp)
                                    .clickable { showEditNameDialog = true }
                            )
                        }

                        if (isPremium) {
                            Surface(
                                shape = RoundedCornerShape(50.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Row(
                                    modifier              = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(painter = painterResource(id = R.drawable.ic_crown), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                    Text(text = premiumLabel, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(50.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text(text = strFree, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
                            }
                        }
                    }
                }

                AccountInfoCard(
                    title = strAccountInfo,
                    rows  = listOf(
                        Triple(Icons.Default.Person, strName,  customDisplayName ?: user!!.displayName),
                        Triple(Icons.Default.Email,  strEmail, user!!.email)
                    )
                )

                JavaProStatusCard(
                    title        = strStatusSection,
                    isPremium    = isPremium,
                    premiumLabel = premiumLabel,
                    expiryLabel  = strExpiry,
                    expiryText   = expiryText,
                    strFree      = strFree
                )

                AccountActionCard(
                    icon     = Icons.Default.Refresh,
                    title    = strRefreshPremium,
                    subtitle = strRefreshPremiumDesc,
                    onClick  = {
                        scope.launch {
                            val result = PremiumManager.checkOnline(context)
                            Toast.makeText(context, if (result) "✓ Premium aktif" else "✗ Premium tidak ditemukan", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                AccountActionCard(
                    icon     = Icons.Default.SyncAlt,
                    title    = strReLogin,
                    subtitle = strReLoginDesc,
                    onClick  = {
                        scope.launch {
                            isSigningIn = true
                            val result = GoogleAuthManager.signIn(context)
                            result.onSuccess { u -> user = u }
                            result.onFailure { Toast.makeText(context, "Login gagal: ${it.localizedMessage}", Toast.LENGTH_SHORT).show() }
                            isSigningIn = false
                        }
                    }
                )

                OutlinedButton(
                    onClick  = { showSignOutDialog = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape    = RoundedCornerShape(14.dp),
                    border   = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text = strSignOut, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }

            } else {
                Column(
                    modifier            = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier         = Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(52.dp))
                    }

                    Text(text = strNotLoggedIn, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(text = strLoginDesc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 19.sp)

                    Button(
                        onClick  = {
                            scope.launch {
                                isSigningIn = true
                                val result = GoogleAuthManager.signIn(context)
                                result.onSuccess { u -> user = u }
                                result.onFailure { Toast.makeText(context, "Login gagal: ${it.localizedMessage}", Toast.LENGTH_SHORT).show() }
                                isSigningIn = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(14.dp),
                        enabled  = !isSigningIn
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        } else {
                            Icon(imageVector = Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(text = strLoginGoogle, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AccountInfoCard(title: String, rows: List<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, String>>) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 0.8.sp)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.8.dp)
            rows.forEach { (icon, label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    Text(text = label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun JavaProStatusCard(
    title        : String,
    isPremium    : Boolean,
    premiumLabel : String,
    expiryLabel  : String,
    expiryText   : String,
    strFree      : String
) {
    val statusColor = if (isPremium) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = if (isPremium) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border    = if (isPremium) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) else null,
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    painter            = painterResource(id = R.drawable.ic_crown),
                    contentDescription = null,
                    tint               = statusColor,
                    modifier           = Modifier.size(16.dp)
                )
                Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = statusColor, letterSpacing = 0.8.sp)
            }
            HorizontalDivider(color = statusColor.copy(alpha = 0.2f), thickness = 0.8.dp)

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Status", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(text = if (isPremium) premiumLabel else strFree, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = statusColor)
            }

            if (isPremium) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = expiryLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(text = expiryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun AccountActionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier         = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
