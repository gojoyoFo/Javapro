package com.javapro.ui.screens
import com.javapro.R
import com.javapro.ui.components.SpoofDeviceCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*




import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class GameInfo(val name: String, val packageName: String, val icon: Drawable)

private fun customPackagesFile(context: Context): File =
    File(context.filesDir, "custom_games.txt")

fun loadCustomPackages(context: Context): List<String> {
    val file = customPackagesFile(context)
    if (!file.exists()) return emptyList()
    return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
}

fun saveCustomPackage(context: Context, packageName: String) {
    val file = customPackagesFile(context)
    val existing = loadCustomPackages(context).toMutableList()
    if (!existing.contains(packageName.trim())) {
        existing.add(packageName.trim())
        file.writeText(existing.joinToString("\n"))
    }
}

fun removeCustomPackage(context: Context, packageName: String) {
    val file = customPackagesFile(context)
    val updated = loadCustomPackages(context).filter { it != packageName.trim() }
    file.writeText(updated.joinToString("\n"))
}

suspend fun detectGames(context: android.content.Context): List<GameInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val result = mutableListOf<GameInfo>()
    val gameKeywords = mutableListOf<String>()

    try {
        context.assets.open("game.txt").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val t = line.trim()
                if (t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("[")) {
                    gameKeywords.add(t.lowercase())
                }
            }
        }
    } catch (e: Exception) {}

    val customPkgs = loadCustomPackages(context)

    try {
        val packages = pm.getInstalledPackages(0)
        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            if (pm.getLaunchIntentForPackage(pkg.packageName) == null) continue

            val isGameCat = if (android.os.Build.VERSION.SDK_INT >= 26) appInfo.category == ApplicationInfo.CATEGORY_GAME else false
            val isGameFlag = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            val pkgLower = pkg.packageName.lowercase()
            val isMatch = gameKeywords.any { pkgLower.contains(it) } || pkgLower.contains("game")
            val isCustom = customPkgs.any { it.equals(pkg.packageName, ignoreCase = true) }
            val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            if (isGameCat || isGameFlag || (!isSys && isMatch) || isCustom) {
                val label = try { appInfo.loadLabel(pm).toString() } catch (e: Exception) { pkg.packageName }
                val icon = try { appInfo.loadIcon(pm) } catch (e: Exception) { pm.defaultActivityIcon }
                result.add(GameInfo(label, pkg.packageName, icon))
            }
        }
    } catch (e: Exception) {}
    return@withContext result.sortedBy { it.name.lowercase() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBoostScreen(navController: NavController, lang: String) {
    val context = LocalContext.current
    var games by remember { mutableStateOf(listOf<GameInfo>()) }
    var isLoading by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }
    var customPackages by remember { mutableStateOf(loadCustomPackages(context)) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showManageDialog by remember { mutableStateOf(false) }
    var addInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }

    suspend fun reload() {
        isLoading = true
        games = detectGames(context)
        customPackages = loadCustomPackages(context)
        isLoading = false
    }

    LaunchedEffect(Unit) { reload() }

    val filtered = if (search.isBlank()) games
    else games.filter { it.name.contains(search, true) || it.packageName.contains(search, true) }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; addInput = ""; addError = "" },
            icon = { Icon(Icons.Default.SportsEsports, null, tint = MaterialTheme.colorScheme.primary) },
            title = {
                Text(
                    text = context.getString(R.string.gameboost_add_package_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = context.getString(R.string.gameboost_enter_package),
                        fontSize = 13.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value         = addInput,
                        onValueChange = { addInput = it; addError = "" },
                        placeholder   = { Text("com.example.game", fontSize = 13.sp) },
                        singleLine    = true,
                        isError       = addError.isNotEmpty(),
                        supportingText = if (addError.isNotEmpty()) {{ Text(addError, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }} else null,
                        modifier      = Modifier.fillMaxWidth(),
                        shape         = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pkg = addInput.trim()
                        when {
                            pkg.isEmpty() -> addError = context.getString(R.string.gameboost_package_empty)
                            !pkg.contains(".") -> addError = context.getString(R.string.gameboost_invalid_package)
                            loadCustomPackages(context).contains(pkg) -> addError = context.getString(R.string.gameboost_package_exists)
                            else -> {
                                saveCustomPackage(context, pkg)
                                showAddDialog = false
                                addInput = ""
                                addError = ""
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.action_add))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showAddDialog = false; addInput = ""; addError = "" },
                    shape   = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = {
                Text(
                    text = context.getString(R.string.gameboost_manage_packages_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (customPackages.isEmpty()) {
                    Text(
                        text = context.getString(R.string.gameboost_no_custom_packages),
                        fontSize = 13.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        customPackages.forEach { pkg ->
                            Row(
                                modifier          = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text     = pkg,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color    = MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(
                                    onClick = {
                                        removeCustomPackage(context, pkg)
                                        customPackages = loadCustomPackages(context)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.SportsEsports,
                                        contentDescription = null,
                                        tint     = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f), thickness = 0.5.dp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GameBoost", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                        Text(
                            text = context.getString(R.string.gameboost_games_detected, games.size),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.SportsEsports, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    if (customPackages.isNotEmpty()) {
                        TextButton(onClick = { showManageDialog = true }) {
                            Text(
                                text = context.getString(R.string.gameboost_manage_packages_btn, customPackages.size),
                                fontSize = 12.sp,
                                color    = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value         = search,
                onValueChange = { search = it },
                modifier      = Modifier.fillMaxWidth(),
                placeholder   = { Text(stringResource(R.string.gameboost_search)) },
                leadingIcon   = { Icon(Icons.Default.Search, null) },
                shape         = RoundedCornerShape(14.dp),
                singleLine    = true
            )
            Spacer(Modifier.height(12.dp))
            SpoofDeviceCard(navController = navController)
            Spacer(Modifier.height(4.dp))
            if (isLoading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered) { game ->
                        val isCustom = customPackages.any { it.equals(game.packageName, ignoreCase = true) }
                        GameCard(game, lang, isCustom) {
                            navController.navigate("gameboost_detail/${game.packageName}/$lang")
                        }
                    }
                    item { Spacer(Modifier.height(90.dp)) }
                }
            }
        }
    }
}

@Composable
private fun GameCard(game: GameInfo, lang: String, isCustom: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border   = BorderStroke(
            width = 1.dp,
            brush = if (isCustom)
                Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.tertiary.copy(0.6f), MaterialTheme.colorScheme.secondary.copy(0.3f)))
            else
                Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary.copy(0.4f), MaterialTheme.colorScheme.secondary.copy(0.2f)))
        )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val bmp = remember(game.packageName) {
                try { game.icon.toBitmap(128, 128).asImageBitmap() } catch (e: Exception) { null }
            }
            if (bmp != null) {
                Image(bmp, null, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(
                    Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    Alignment.Center
                ) { Icon(Icons.Default.SportsEsports, null) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(game.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(game.packageName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isCustom) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.status_custom),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color    = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}
