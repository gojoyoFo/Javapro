package com.javapro.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.javapro.R
import com.javapro.utils.PremiumManager
import com.javapro.utils.PreferenceManager
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private const val FIRESTORE_APP_ID = "javapro"
private const val FIRESTORE_PATH = "artifacts/$FIRESTORE_APP_ID/public/data/cloud_configs"
private const val DAILY_DOWNLOAD_LIMIT_FREE    = 2
private const val DAILY_DOWNLOAD_LIMIT_PREMIUM = 5
private const val PREF_CLOUD = "cloud_config_prefs"

data class CloudConfig(
    val id           : String  = "",
    val name         : String  = "",
    val game         : String  = "",
    val device       : String  = "",
    val author       : String  = "",
    val downloads    : Long    = 0L,
    val tweaks       : Map<String, Any> = emptyMap(),
    val uploadedAt   : Long    = 0L
)

private fun getTodayDownloadCount(context: Context): Int {
    val prefs = context.getSharedPreferences(PREF_CLOUD, Context.MODE_PRIVATE)
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    val savedDay = prefs.getString("dl_day", "")
    return if (savedDay == today) prefs.getInt("dl_count", 0) else 0
}

private fun incrementDownloadCount(context: Context) {
    val prefs = context.getSharedPreferences(PREF_CLOUD, Context.MODE_PRIVATE)
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    val savedDay = prefs.getString("dl_day", "")
    val current = if (savedDay == today) prefs.getInt("dl_count", 0) else 0
    prefs.edit().putString("dl_day", today).putInt("dl_count", current + 1).apply()
}

private fun hasLocalTweaks(context: Context): Boolean {
    val prefs = context.getSharedPreferences("GameBoostPrefs", Context.MODE_PRIVATE)
    return prefs.all.any { it.key.startsWith("boost_") && it.value == true }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudConfigsScreen(
    navController  : NavController,
    packageName    : String,
    prefManager    : PreferenceManager
) {
    val context       = LocalContext.current
    val scope         = rememberCoroutineScope()
    val isPremium     = remember { PremiumManager.isPremium(context) }
    val dailyLimit    = if (isPremium) DAILY_DOWNLOAD_LIMIT_PREMIUM else DAILY_DOWNLOAD_LIMIT_FREE

    var configs           by remember { mutableStateOf<List<CloudConfig>>(emptyList()) }
    var isLoading         by remember { mutableStateOf(true) }
    var showUploadSheet   by remember { mutableStateOf(false) }
    var applyingId        by remember { mutableStateOf<String?>(null) }
    var todayCount        by remember { mutableStateOf(getTodayDownloadCount(context)) }
    var listenerReg       by remember { mutableStateOf<ListenerRegistration?>(null) }
    val hasLocal          = remember { hasLocalTweaks(context) }

    // State untuk double-ad gate (free user)
    var pendingConfig     by remember { mutableStateOf<CloudConfig?>(null) }
    var adWatchedCount    by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val reg = FirebaseFirestore.getInstance()
            .collection(FIRESTORE_PATH)
            .addSnapshotListener { snap, _ ->
                snap ?: return@addSnapshotListener
                configs = snap.documents.mapNotNull { doc ->
                    try {
                        CloudConfig(
                            id        = doc.id,
                            name      = doc.getString("name") ?: return@mapNotNull null,
                            game      = doc.getString("game") ?: "",
                            device    = doc.getString("device") ?: "",
                            author    = doc.getString("author") ?: "Anonymous",
                            downloads = doc.getLong("downloads") ?: 0L,
                            tweaks    = (doc.get("tweaks") as? Map<String, Any>) ?: emptyMap(),
                            uploadedAt = doc.getLong("uploadedAt") ?: 0L
                        )
                    } catch (_: Exception) { null }
                }.sortedByDescending { it.downloads }
                isLoading = false
            }
        listenerReg = reg
        onDispose { listenerReg?.remove() }
    }

    // Helper: jalankan download setelah iklan selesai
    fun doApply(config: CloudConfig) {
        scope.launch {
            applyingId = config.id
            withContext(Dispatchers.IO) {
                applyCloudConfig(config, packageName, context, prefManager)
                incrementDownloadCount(context)
                try {
                    FirebaseFirestore.getInstance()
                        .document("$FIRESTORE_PATH/${config.id}")
                        .update("downloads", config.downloads + 1)
                        .await()
                } catch (_: Exception) {}
            }
            todayCount = getTodayDownloadCount(context)
            applyingId = null
            pendingConfig = null
            adWatchedCount = 0
            Toast.makeText(
                context,
                context.getString(R.string.cloud_apply_success),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Helper: trigger download dengan double-ad gate untuk free user
    fun onDownloadClick(config: CloudConfig, activity: android.app.Activity) {
        if (isPremium) {
            // Premium: langsung download tanpa iklan
            scope.launch {
                applyingId = config.id
                withContext(Dispatchers.IO) {
                    applyCloudConfig(config, packageName, context, prefManager)
                    incrementDownloadCount(context)
                    try {
                        FirebaseFirestore.getInstance()
                            .document("$FIRESTORE_PATH/${config.id}")
                            .update("downloads", config.downloads + 1)
                            .await()
                    } catch (_: Exception) {}
                }
                todayCount = getTodayDownloadCount(context)
                applyingId = null
                Toast.makeText(context, context.getString(R.string.cloud_apply_success), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Free user: wajib nonton 2 iklan
        pendingConfig = config
        adWatchedCount = 0

        fun showSecondAd() {
            com.javapro.ads.AdManager.showRewardedForCloudConfig(
                activity    = activity,
                onCompleted = { doApply(config) },
                onSkipped   = {
                    pendingConfig = null
                    adWatchedCount = 0
                    Toast.makeText(context, context.getString(R.string.cloud_watch_ad_warning), Toast.LENGTH_SHORT).show()
                }
            )
        }

        com.javapro.ads.AdManager.showRewardedForCloudConfig(
            activity    = activity,
            onCompleted = {
                adWatchedCount = 1
                showSecondAd()
            },
            onSkipped   = {
                pendingConfig = null
                adWatchedCount = 0
                Toast.makeText(context, context.getString(R.string.cloud_watch_ad_warning), Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showUploadSheet) {
        UploadConfigSheet(
            packageName = packageName,
            context     = context,
            onDismiss   = { showUploadSheet = false },
            onUploaded  = {
                showUploadSheet = false
                Toast.makeText(context, context.getString(R.string.cloud_upload_success), Toast.LENGTH_SHORT).show()
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.cloud_title),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize   = 17.sp
                        )
                        Text(
                            stringResource(R.string.cloud_subtitle),
                            fontSize = 11.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Download,
                                null,
                                tint     = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                "$todayCount/$dailyLimit",
                                fontSize   = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color      = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = hasLocal,
                enter   = fadeIn(tween(300)),
                exit    = fadeOut(tween(300))
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color    = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    Button(
                        onClick  = { showUploadSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        shape    = RoundedCornerShape(16.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.upload_config),
                            fontWeight = FontWeight.Bold,
                            fontSize   = 14.sp
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color    = MaterialTheme.colorScheme.primary
                    )
                }
                configs.isEmpty() -> {
                    Column(
                        modifier              = Modifier.align(Alignment.Center),
                        horizontalAlignment   = Alignment.CenterHorizontally,
                        verticalArrangement   = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.CloudOff,
                            null,
                            modifier = Modifier.size(56.dp),
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f)
                        )
                        Text(
                            stringResource(R.string.cloud_empty),
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier            = Modifier.fillMaxSize(),
                        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            // Banner info — beda teks untuk free vs premium
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape    = RoundedCornerShape(16.dp),
                                color    = if (!isPremium)
                                    MaterialTheme.colorScheme.errorContainer.copy(0.35f)
                                else
                                    MaterialTheme.colorScheme.primaryContainer.copy(0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        if (!isPremium) Icons.Default.Videocam else Icons.Default.Info,
                                        null,
                                        tint     = if (!isPremium) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        if (!isPremium)
                                            stringResource(R.string.cloud_free_limit_info, DAILY_DOWNLOAD_LIMIT_FREE)
                                        else
                                            stringResource(R.string.cloud_limit_info, DAILY_DOWNLOAD_LIMIT_PREMIUM),
                                        fontSize   = 11.sp,
                                        color      = if (!isPremium) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                        items(configs, key = { it.id }) { config ->
                            val activity = context as? android.app.Activity
                            CloudConfigCard(
                                config      = config,
                                isApplying  = applyingId == config.id,
                                canDownload = todayCount < dailyLimit,
                                isPremium   = isPremium,
                                onApply     = {
                                    if (activity != null) {
                                        onDownloadClick(config, activity)
                                    }
                                }
                            )
                        }
                        item { Spacer(Modifier.height(if (hasLocal) 80.dp else 16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudConfigCard(
    config      : CloudConfig,
    isApplying  : Boolean,
    canDownload : Boolean,
    isPremium   : Boolean,
    onApply     : () -> Unit
) {
    val accentColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(24.dp),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier         = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    accentColor.copy(0.25f),
                                    MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Tune,
                        null,
                        tint     = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        config.name,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize   = 15.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        config.game,
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CloudInfoChip(Icons.Default.PhoneAndroid, config.device)
                CloudInfoChip(Icons.Default.Person, config.author)
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Default.Download,
                        null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        "${config.downloads}",
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Button(
                onClick  = onApply,
                enabled  = canDownload && !isApplying,
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = if (!isPremium && canDownload) MaterialTheme.colorScheme.secondary
                                            else MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                if (isApplying) {
                    CircularProgressIndicator(
                        modifier   = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color      = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    when {
                        !canDownload -> {
                            Icon(Icons.Default.Block, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cloud_limit_reached), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        !isPremium -> {
                            Icon(Icons.Default.Videocam, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cloud_watch_ads_download), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        else -> {
                            Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cloud_apply_btn), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudInfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    if (text.isBlank()) return
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(10.dp))
            Text(text, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumGateSheet(
    onDismiss : () -> Unit,
    onUpgrade : () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest  = onDismiss,
        containerColor    = MaterialTheme.colorScheme.surface,
        dragHandle        = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(0.3f),
                                MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.WorkspacePremium,
                    null,
                    tint     = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                stringResource(R.string.cloud_premium_title),
                fontWeight = FontWeight.ExtraBold,
                fontSize   = 20.sp,
                color      = MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(R.string.cloud_premium_desc),
                fontSize   = 13.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            listOf(
                R.string.cloud_perk_1,
                R.string.cloud_perk_2,
                R.string.cloud_perk_3
            ).forEach { res ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(stringResource(res), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick  = onUpgrade,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = RoundedCornerShape(16.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Stars, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.cloud_upgrade_btn),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize   = 15.sp
                )
            }

            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.cloud_maybe_later),
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadConfigSheet(
    packageName : String,
    context     : Context,
    onDismiss   : () -> Unit,
    onUploaded  : () -> Unit
) {
    val scope         = rememberCoroutineScope()
    var configName    by remember { mutableStateOf("") }
    var deviceName    by remember { mutableStateOf(android.os.Build.MODEL) }
    var authorName    by remember { mutableStateOf("") }
    var isUploading   by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surface,
        dragHandle       = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.upload_config),
                fontWeight = FontWeight.ExtraBold,
                fontSize   = 18.sp
            )

            OutlinedTextField(
                value         = configName,
                onValueChange = { configName = it },
                label         = { Text(stringResource(R.string.cloud_config_name)) },
                modifier      = Modifier.fillMaxWidth(),
                shape         = RoundedCornerShape(12.dp),
                singleLine    = true
            )
            OutlinedTextField(
                value         = authorName,
                onValueChange = { authorName = it },
                label         = { Text(stringResource(R.string.cloud_author_name)) },
                modifier      = Modifier.fillMaxWidth(),
                shape         = RoundedCornerShape(12.dp),
                singleLine    = true
            )
            OutlinedTextField(
                value         = deviceName,
                onValueChange = { deviceName = it },
                label         = { Text(stringResource(R.string.cloud_device_name)) },
                modifier      = Modifier.fillMaxWidth(),
                shape         = RoundedCornerShape(12.dp),
                singleLine    = true
            )

            Button(
                onClick  = {
                    if (configName.isBlank()) return@Button
                    scope.launch {
                        isUploading = true
                        withContext(Dispatchers.IO) {
                            val tweakPrefs = context.getSharedPreferences("GameBoostPrefs", Context.MODE_PRIVATE)
                            val tweakMap   = tweakPrefs.all
                                .filter { it.key.endsWith("_$packageName") }
                                .mapValues { it.value?.toString() ?: "" }

                            val pkgLabel = try {
                                context.packageManager.getApplicationLabel(
                                    context.packageManager.getApplicationInfo(packageName, 0)
                                ).toString()
                            } catch (_: Exception) { packageName }

                            val data = hashMapOf(
                                "name"       to configName.trim(),
                                "game"       to pkgLabel,
                                "device"     to deviceName.trim(),
                                "author"     to authorName.trim().ifBlank { "Anonymous" },
                                "downloads"  to 0L,
                                "tweaks"     to tweakMap,
                                "uploadedAt" to System.currentTimeMillis()
                            )
                            try {
                                FirebaseFirestore.getInstance()
                                    .collection(FIRESTORE_PATH)
                                    .add(data)
                                    .await()
                            } catch (_: Exception) {}
                        }
                        isUploading = false
                        onUploaded()
                    }
                },
                enabled  = configName.isNotBlank() && !isUploading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = RoundedCornerShape(14.dp)
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cloud_upload_btn), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private suspend fun applyCloudConfig(
    config      : CloudConfig,
    packageName : String,
    context     : Context,
    prefManager : PreferenceManager
) {
    val prefs  = context.getSharedPreferences("GameBoostPrefs", Context.MODE_PRIVATE)
    val editor = prefs.edit()

    config.tweaks.forEach { (key, value) ->
        val normalizedKey = if (key.endsWith("_$packageName")) key
        else "${key}_$packageName"

        when (value) {
            is Boolean -> editor.putBoolean(normalizedKey, value)
            is String  -> {
                val boolVal = value.toBooleanStrictOrNull()
                if (boolVal != null) editor.putBoolean(normalizedKey, boolVal)
                else editor.putString(normalizedKey, value)
            }
            else       -> editor.putString(normalizedKey, value.toString())
        }
    }
    editor.apply()

    val killBg      = prefs.getBoolean("killbg_$packageName", false)
    val gameMode    = prefs.getBoolean("gamemode_$packageName", false)
    val anim        = prefs.getBoolean("anim_$packageName", false)
    val touch       = prefs.getBoolean("touch_$packageName", false)
    val prioritize  = prefs.getBoolean("prioritize_$packageName", false)
    val memOpt      = prefs.getBoolean("memopt_$packageName", false)
    val renderAhead = prefs.getBoolean("renderahead_$packageName", false)
    val raVal       = prefs.getString("renderaheadval_$packageName", "1") ?: "1"
    val sustained   = prefs.getBoolean("sustained_$packageName", false)

    if (killBg)     TweakExecutor.execute("am kill-all")
    if (gameMode)   TweakExecutor.execute("cmd game mode 2 $packageName")
    if (anim) {
        TweakExecutor.execute("settings put global window_animation_scale 0.5")
        TweakExecutor.execute("settings put global transition_animation_scale 0.5")
        TweakExecutor.execute("settings put global animator_duration_scale 0.5")
    }
    if (touch) {
        TweakExecutor.execute("cmd device_config put input_native_boot palm_rejection_enabled 0")
        TweakExecutor.execute("settings put system touch_blocking_period 0")
    }
    if (prioritize) {
        TweakExecutor.execute("cmd game set --mode 2 $packageName")
        TweakExecutor.execute("cmd device_config put game_overlay \"$packageName\" \"mode=2\"")
    }
    if (memOpt)      TweakExecutor.execute("echo 3 > /proc/sys/vm/drop_caches")
    if (renderAhead) TweakExecutor.execute("cmd game set --render_ahead $raVal $packageName")
    if (sustained)   TweakExecutor.execute("settings put global sustained_performance_mode 1")
}
