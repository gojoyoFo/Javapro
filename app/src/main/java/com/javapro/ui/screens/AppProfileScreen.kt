package com.javapro.ui.screens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*




import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import com.javapro.R
import com.javapro.utils.AppInfo
import com.javapro.utils.AppProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppProfileScreen(
    navController : NavController,
    lang          : String,
    onShowAd      : () -> Unit = {}
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var appList     by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(true) }

    val strRefreshing  = stringResource(R.string.status_refreshing)
    val strLoadingApps = stringResource(R.string.status_loading_apps)
    val strNoApps      = stringResource(R.string.appprofile_no_apps)
    val strNoResults   = stringResource(R.string.appprofile_search)

    fun loadApps() {
        scope.launch {
            isLoading = true
            try {
                val apps = withContext(Dispatchers.IO) { AppProfileManager.getInstalledApps(context) }
                appList   = apps
                isLoading = false
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadApps() }

    val filteredList = remember(searchQuery, appList) {
        if (searchQuery.isEmpty()) appList
        else appList.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appprofile_app_list), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick  = { if (!isLoading) { loadApps(); Toast.makeText(context, strRefreshing, Toast.LENGTH_SHORT).show() } },
                        enabled  = !isLoading
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value         = searchQuery,
                onValueChange = { searchQuery = it },
                modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder   = { Text(stringResource(R.string.appprofile_search)) },
                leadingIcon   = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
                shape         = RoundedCornerShape(14.dp),
                singleLine    = true,
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                )
            )

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(strLoadingApps, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (filteredList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text  = if (searchQuery.isEmpty()) strNoApps else strNoResults,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text     = stringResource(R.string.appprofile_app_count, filteredList.size),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color    = MaterialTheme.colorScheme.secondary
                )
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(items = filteredList, key = { it.packageName }) { app ->
                        AppItemCard(app = app, lang = lang, onClick = {
                            navController.navigate("app_detail/${app.packageName}/$lang")
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun AppItemCard(app: AppInfo, lang: String = "en", onClick: () -> Unit) {
    val strPerformance = stringResource(R.string.status_performance)
    val strPowersave   = stringResource(R.string.status_powersave)
    val strBalance     = stringResource(R.string.status_balance)

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clickable { onClick() },
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val iconBitmap = remember(app.packageName) {
                try { app.icon.toBitmap(64, 64).asImageBitmap() } catch (e: Exception) { null }
            }
            if (iconBitmap != null) {
                Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(
                    Modifier.size(50.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(app.name.take(1).uppercase(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                Text(app.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                val (label, color) = when (app.profile) {
                    "performance" -> strPerformance to MaterialTheme.colorScheme.error
                    "powersave"   -> strPowersave   to MaterialTheme.colorScheme.secondary
                    else          -> strBalance      to MaterialTheme.colorScheme.tertiary
                }
                Surface(
                    color  = color.copy(alpha = 0.15f),
                    shape  = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
                ) {
                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
    }
}
