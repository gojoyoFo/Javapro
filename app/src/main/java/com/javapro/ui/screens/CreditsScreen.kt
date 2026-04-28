package com.javapro.ui.screens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*




import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.R

data class Contributor(
    val name: String,
    val username: String,
    val imageRes: Int,
    val role: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(navController: NavController, lang: String = "en") {
    val context = LocalContext.current

    val contributors = listOf(
        Contributor("Anomaly Arc",       "anomaly_arc",            R.drawable.profile_anomaly),
        Contributor("Fahrezone",         "fahrezone",              R.drawable.profile_fahrez),
        Contributor("Diky",              "Nekotor1999",            R.drawable.profile_diky),
        Contributor("Rapli",             "ErOneSoul",              R.drawable.profile_rapli)
    )

    val gradientBg = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.background
        )
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text          = stringResource(R.string.credits_title),
                        fontWeight    = FontWeight.ExtraBold,
                        fontStyle     = FontStyle.Italic,
                        fontSize      = 20.sp,
                        color         = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBg)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {

            CreditSectionLabel(stringResource(R.string.credits_main_dev_label))
            Spacer(Modifier.height(8.dp))

            CreditCardLarge(
                imageRes    = R.drawable.profile_developer,
                name        = "Java_nih_deks",
                badgeText   = stringResource(R.string.credits_main_dev_badge),
                telegramHandle = null
            )

            Spacer(Modifier.height(20.dp))
            CreditSectionLabel(stringResource(R.string.credits_co_dev_label))
            Spacer(Modifier.height(8.dp))

            CreditCardLarge(
                imageRes       = R.drawable.profile_zuan,
                name           = "zuanvfx",
                badgeText      = stringResource(R.string.credits_co_dev_badge),
                telegramHandle = "zuanvfx01"
            )

            Spacer(Modifier.height(12.dp))

            CreditCardLarge(
                imageRes       = R.drawable.profile_muhammad_rizki,
                name           = "Muhammad Rizki",
                badgeText      = stringResource(R.string.credits_co_dev_badge),
                telegramHandle = "RiProG"
            )

            Spacer(Modifier.height(28.dp))

            HorizontalDivider(
                thickness = 0.8.dp,
                color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(20.dp),
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text(
                        text       = stringResource(R.string.credits_special_thanks_title),
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color      = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text  = stringResource(R.string.credits_special_thanks_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            CreditSectionLabel(stringResource(R.string.credits_contributors_label))
            Spacer(Modifier.height(10.dp))

            CreditCardLarge(
                imageRes       = R.drawable.profile_kanagawa,
                name           = "Kanagawa Yamada",
                badgeText      = "Kontributor",
                telegramHandle = "KanagawaYamadaVTeacher"
            )

            Spacer(Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                contributors.forEach { person ->
                    ContributorCard(person) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/${person.username}"))
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text      = stringResource(R.string.credits_footer),
                style     = MaterialTheme.typography.labelSmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier  = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CreditSectionLabel(text: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier              = Modifier.padding(start = 2.dp)
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text          = text,
            fontSize      = 10.sp,
            fontWeight    = FontWeight.ExtraBold,
            fontStyle     = FontStyle.Italic,
            color         = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
private fun CreditCardLarge(
    imageRes: Int,
    name: String,
    badgeText: String,
    telegramHandle: String?
) {
    val context = LocalContext.current

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border    = androidx.compose.foundation.BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier          = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier           = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment   = Alignment.Center
            ) {
                Image(
                    painter            = painterResource(id = imageRes),
                    contentDescription = name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier              = Modifier.weight(1f),
                verticalArrangement   = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text       = name,
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text     = badgeText,
                        style    = MaterialTheme.typography.labelMedium,
                        color    = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                if (telegramHandle != null) {
                    FilledTonalButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$telegramHandle"))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        shape           = RoundedCornerShape(50),
                        contentPadding  = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier        = Modifier.height(34.dp),
                        colors          = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor   = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(text = "@$telegramHandle", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ContributorCard(data: Contributor, onLinkClick: () -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border    = androidx.compose.foundation.BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter            = painterResource(id = data.imageRes),
                    contentDescription = data.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier            = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text       = data.name,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                if (data.role != null) {
                    Text(
                        text  = data.role,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FilledTonalButton(
                onClick         = onLinkClick,
                shape           = RoundedCornerShape(50),
                contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier        = Modifier.height(32.dp),
                colors          = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor   = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = "@${data.username}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
