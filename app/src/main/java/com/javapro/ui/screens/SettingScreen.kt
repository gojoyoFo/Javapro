package com.javapro.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*

import android.content.Context
import android.widget.Toast
import android.content.Intent
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.javapro.BuildConfig
import com.javapro.R
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.javapro.utils.DailyRewardManager
import com.javapro.utils.GoogleAuthManager
import com.javapro.utils.LocaleHelper
import com.javapro.utils.PreferenceManager
import com.javapro.utils.PremiumManager
import com.javapro.utils.TweakExecutor
import com.javapro.utils.TweakManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(pref: PreferenceManager, navController: NavController, lang: String = "en") {
    val context     = LocalContext.current
    val scope       = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val isPremium   = remember { PremiumManager.isPremium(context) }
    val premiumType = remember { PremiumManager.getPremiumType(context) }

    var googleUser  by remember { mutableStateOf(GoogleAuthManager.getUser(context)) }
    var isSigningIn by remember { mutableStateOf(false) }

    // Refresh user info setiap kali SettingScreen kembali ke foreground (misal: balik dari GoogleAccountScreen)
    val navBackStack by navController.currentBackStackEntryAsState()
    LaunchedEffect(navBackStack) {
        googleUser = GoogleAuthManager.getUser(context)
    }

    val currentLang  by pref.languageFlow.collectAsState(initial = "en")
    val isBootActive by pref.bootApplyFlow.collectAsState()
    val savedRed     by pref.redValFlow.collectAsState()
    val savedGreen   by pref.greenValFlow.collectAsState()
    val savedBlue    by pref.blueValFlow.collectAsState()
    val savedSat     by pref.satValFlow.collectAsState()

    var redUI   by remember { mutableFloatStateOf(savedRed) }
    var greenUI by remember { mutableFloatStateOf(savedGreen) }
    var blueUI  by remember { mutableFloatStateOf(savedBlue) }
    var satUI   by remember { mutableFloatStateOf(savedSat) }

    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(savedRed, savedGreen, savedBlue, savedSat) {
        redUI = savedRed; greenUI = savedGreen; blueUI = savedBlue; satUI = savedSat
    }
    LaunchedEffect(redUI, greenUI, blueUI, satUI) {
        delay(300)
        if (redUI != 0f && satUI != 0f) {
            TweakExecutor.applyColorModifier(redUI, greenUI, blueUI, satUI)
            pref.setRGB(redUI, greenUI, blueUI)
            pref.setSat(satUI)
        }
    }

    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    val strAllSettingsReset = stringResource(R.string.all_settings_reset)

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            shape            = RoundedCornerShape(24.dp),
            containerColor   = MaterialTheme.colorScheme.surface,
            icon             = {
                Box(
                    modifier         = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Default.Refresh,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.error,
                        modifier           = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text       = stringResource(R.string.dialog_reset_title),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 18.sp,
                    color      = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text       = stringResource(R.string.dialog_reset_message),
                    fontSize   = 14.sp,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        scope.launch {
                            pref.resetAll()
                            redUI = 1000f; greenUI = 1000f; blueUI = 1000f; satUI = 1000f
                            TweakExecutor.applyColorModifier(1000f, 1000f, 1000f, 1000f)
                            TweakManager.resetAllTweaks(context)
                            context.getSharedPreferences("App_Profiles", android.content.Context.MODE_PRIVATE)
                                .edit().clear().apply()
                            context.getSharedPreferences("App_Settings", android.content.Context.MODE_PRIVATE)
                                .edit().clear().apply()
                            context.getSharedPreferences("GameBoostPrefs", android.content.Context.MODE_PRIVATE)
                                .edit().clear().putInt("pref_version", 3).apply()
                            Toast.makeText(context, strAllSettingsReset, Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor   = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(50.dp)
                ) {
                    Text(text = stringResource(R.string.dialog_reset_confirm), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetDialog = false },
                    shape   = RoundedCornerShape(50.dp),
                    border  = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(text = stringResource(R.string.dialog_reset_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text  = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {

            GoogleAccountCardLarge(
                user      = googleUser,
                isLoading = isSigningIn,
                isPremium = isPremium,
                onSignIn  = {
                    scope.launch {
                        isSigningIn = true
                        val result = GoogleAuthManager.signIn(context)
                        result.onSuccess { user -> googleUser = user }
                        result.onFailure {
                            Toast.makeText(context, "Login gagal: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                        isSigningIn = false
                    }
                },
                onOpenProfile = {
                    navController.navigate("google_account")
                }
            )

            Spacer(Modifier.height(16.dp))

            LanguagePickerCard(
                currentLang  = currentLang,
                onSelectLang = { selectedLang ->
                    LocaleHelper.saveLanguage(context, selectedLang)
                    pref.setLanguage(selectedLang)
                }
            )

            Spacer(Modifier.height(16.dp))

            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = cardColor),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                DailyRewardSettingItem(navController = navController)
            }

            Spacer(Modifier.height(16.dp))

            SettingsSectionHeader(
                title = stringResource(R.string.settings_color_calibration_label),
                icon  = Icons.Default.Palette
            )

            Spacer(Modifier.height(8.dp))

            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = cardColor),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    ElegantColorSlider(
                        label         = stringResource(R.string.settings_color_red),
                        value         = redUI,
                        onValueChange = { redUI = it }
                    )
                    Spacer(Modifier.height(16.dp))
                    ElegantColorSlider(
                        label         = stringResource(R.string.settings_color_green),
                        value         = greenUI,
                        onValueChange = { greenUI = it }
                    )
                    Spacer(Modifier.height(16.dp))
                    ElegantColorSlider(
                        label         = stringResource(R.string.settings_color_blue),
                        value         = blueUI,
                        onValueChange = { blueUI = it }
                    )

                    HorizontalDivider(
                        modifier  = Modifier.padding(vertical = 16.dp),
                        color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        thickness = 1.dp
                    )

                    ElegantColorSlider(
                        label         = stringResource(R.string.settings_color_saturation),
                        value         = satUI,
                        maxValue      = 2000f,
                        onValueChange = { satUI = it }
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text       = stringResource(R.string.settings_apply_on_boot_label),
                                fontSize   = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color      = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text     = stringResource(R.string.settings_apply_on_boot_desc),
                                fontSize = 12.sp,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked         = isBootActive,
                            onCheckedChange = { pref.setBootApply(it) }
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    TextButton(
                        onClick  = { redUI = 1000f; greenUI = 1000f; blueUI = 1000f; satUI = 1000f },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(text = stringResource(R.string.settings_reset_colors_btn), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column {
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .clickable { showResetDialog = true }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier         = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Refresh,
                                contentDescription = null,
                                tint               = MaterialTheme.colorScheme.error,
                                modifier           = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text       = stringResource(R.string.settings_reset_all_btn),
                                fontSize   = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color      = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text     = stringResource(R.string.settings_reset_all_desc),
                                fontSize = 12.sp,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector        = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint               = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            AboutAppCard()

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun GoogleAccountCardLarge(
    user          : com.javapro.utils.GoogleUser?,
    isLoading     : Boolean,
    isPremium     : Boolean,
    onSignIn      : () -> Unit,
    onOpenProfile : () -> Unit
) {
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(24.dp),
        colors    = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        if (user != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenProfile() }
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier         = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (user.photoUrl != null) {
                        AsyncImage(
                            model              = user.photoUrl,
                            contentDescription = null,
                            modifier           = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Icon(
                            imageVector        = Icons.Default.Person,
                            contentDescription = null,
                            tint               = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier           = Modifier.size(32.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = user.displayName,
                        fontSize   = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.onSurface,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Text(
                        text     = user.email,
                        fontSize = 13.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isPremium) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape         = RoundedCornerShape(50.dp),
                            color         = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier      = Modifier.wrapContentSize()
                        ) {
                            Row(
                                modifier          = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    painter            = painterResource(id = R.drawable.ic_crown),
                                    contentDescription = null,
                                    tint               = MaterialTheme.colorScheme.primary,
                                    modifier           = Modifier.size(12.dp)
                                )
                                Text(
                                    text       = "Premium",
                                    fontSize   = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color      = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isLoading, onClick = onSignIn)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier         = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(
                            imageVector        = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier           = Modifier.size(32.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = "Login dengan Google",
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text     = "Untuk verifikasi status premium",
                        fontSize = 13.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AboutAppCard() {
    val cardColor   = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier         = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Column {
                    Text(text = stringResource(R.string.about_title),    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(text = stringResource(R.string.about_app_name), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.8.dp)
            Spacer(Modifier.height(14.dp))

            AboutInfoRow(label = stringResource(R.string.about_version),   value = versionName,            icon = Icons.Default.NewReleases)
            Spacer(Modifier.height(10.dp))
            AboutInfoRow(label = stringResource(R.string.about_build),     value = versionCode.toString(), icon = Icons.Default.Build)
            Spacer(Modifier.height(10.dp))
            AboutInfoRow(label = stringResource(R.string.about_developer), value = stringResource(R.string.about_developer_name), icon = Icons.Default.Code)

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.8.dp)
            Spacer(Modifier.height(12.dp))

            Text(
                text      = stringResource(R.string.about_copyright),
                fontSize  = 11.sp,
                color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier  = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String, icon: ImageVector) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        Text(text = label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LanguagePickerCard(
    currentLang  : String,
    onSelectLang : (String) -> Unit
) {
    data class LangOption(val code: String, val flag: String, val label: String, val native: String)
    val languages = listOf(
        LangOption("en",  "🇬🇧", "English",    "English"),
        LangOption("id",  "🇮🇩", "Indonesian", "Bahasa Indonesia"),
        LangOption("zh",  "🇨🇳", "Chinese",    "中文"),
        LangOption("hi",  "🇮🇳", "Hindi",      "हिंदी"),
        LangOption("fil", "🇵🇭", "Filipino",   "Filipino"),
    )

    var expanded by remember { mutableStateOf(false) }
    val chevronRot by animateFloatAsState(
        targetValue   = if (expanded) 180f else 0f,
        animationSpec = tween(280),
        label         = "langChevron"
    )
    val selected  = languages.firstOrNull { it.code == currentLang } ?: languages[0]
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier         = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Language / Bahasa", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(text = "${selected.flag}  ${selected.label}  ·  ${selected.native}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                Icon(
                    imageVector        = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(20.dp).graphicsLayer { rotationZ = chevronRot }
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically(tween(280)) + fadeIn(tween(220)),
                exit    = shrinkVertically(tween(240)) + fadeOut(tween(180))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), thickness = 0.8.dp, modifier = Modifier.padding(bottom = 6.dp))
                    languages.forEach { lang ->
                        val isSelected = lang.code == currentLang
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                .border(
                                    width = if (isSelected) 1.2.dp else 0.6.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectLang(lang.code); expanded = false }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(text = lang.flag, fontSize = 22.sp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = lang.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(text = lang.native, fontSize = 11.sp, color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isSelected) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DailyRewardSettingItem(navController: NavController, context: Context = LocalContext.current) {
    var canClaim   by remember { mutableStateOf(DailyRewardManager.canClaimToday(context)) }
    var adsWatched by remember { mutableIntStateOf(DailyRewardManager.adsWatchedSession(context)) }
    val required   = DailyRewardManager.ADS_REQUIRED

    LaunchedEffect(Unit) {
        while (true) {
            canClaim   = DailyRewardManager.canClaimToday(context)
            adsWatched = DailyRewardManager.adsWatchedSession(context)
            kotlinx.coroutines.delay(1_000L)
        }
    }

    if (remember { PremiumManager.isPremium(context) }) return

    ListItem(
        modifier = Modifier.fillMaxWidth().clickable { navController.navigate("daily_reward") },
        headlineContent = {
            Text(text = stringResource(R.string.setting_daily_reward_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = {
            Text(
                text  = if (canClaim) stringResource(R.string.setting_daily_reward_desc) else stringResource(R.string.reward_title_already),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(imageVector = Icons.Filled.CardGiftcard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                if (canClaim && adsWatched > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary) {
                        Text(text = "$adsWatched/$required", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                } else if (canClaim) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
private fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
    }
}

@Composable
private fun ElegantColorSlider(label: String, value: Float, onValueChange: (Float) -> Unit, maxValue: Float = 1000f) {
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primary)
            Text(text = "${value.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primary)
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = 0f..maxValue,
            modifier      = Modifier.fillMaxWidth(),
            colors        = SliderDefaults.colors(
                thumbColor         = primary,
                activeTrackColor   = primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                activeTickColor    = Color.Transparent,
                inactiveTickColor  = Color.Transparent
            )
        )
    }
}

@Composable
fun SettingsSectionTitle(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = title.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SettingsCard(modifier: Modifier = Modifier, containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = containerColor), shape = RoundedCornerShape(16.dp)) {
        Column(content = content)
    }
}

@Composable
fun SettingsMenuItem(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant, textColor: Color = MaterialTheme.colorScheme.onSurface, trailingContent: @Composable (() -> Unit)? = null) {
    ListItem(
        headlineContent   = { Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = textColor) },
        supportingContent = { Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent    = { Icon(imageVector = icon, contentDescription = null, tint = iconTint) },
        trailingContent   = trailingContent ?: { Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier          = Modifier.fillMaxWidth().clickable(onClick = onClick)
    )
}

@Composable
fun ColorSlider(label: String, value: Float, color: Color, onValueChange: (Float) -> Unit, maxValue: Float = 1000f) {
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
            Text(text = "${value.toInt()}", style = MaterialTheme.typography.bodyMedium, color = primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = 0f..maxValue,
            colors        = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier      = Modifier.padding(vertical = 4.dp)
        )
    }
}
