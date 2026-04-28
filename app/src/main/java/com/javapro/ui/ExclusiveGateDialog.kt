package com.javapro.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import com.javapro.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

@Composable
fun ExclusiveGateDialog(
    lang        : String,
    onWatchAds  : () -> Unit,
    onUpgrade   : () -> Unit,
    onDismiss   : () -> Unit
) {
    val context = LocalContext.current

    // FIX BUG 2: Buat context baru dengan locale sesuai lang preference,
    // bukan system locale. Ini agar stringResource() di dalam dialog
    // mengambil string dari bahasa yang dipilih user, bukan bahasa device.
    val localizedContext = remember(lang) {
        val locale = Locale(lang)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.createConfigurationContext(config)
    }

    CompositionLocalProvider(LocalContext provides localizedContext) {
        ExclusiveGateDialogContent(
            onWatchAds = onWatchAds,
            onUpgrade  = onUpgrade,
            onDismiss  = onDismiss
        )
    }
}

@Composable
private fun ExclusiveGateDialogContent(
    onWatchAds : () -> Unit,
    onUpgrade  : () -> Unit,
    onDismiss  : () -> Unit
) {
    val accentViolet = Color(0xFFCE93D8)

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape          = RoundedCornerShape(28.dp),
            color          = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier       = Modifier
                .padding(horizontal = 20.dp)
                .border(BorderStroke(0.8.dp, accentViolet.copy(0.35f)), RoundedCornerShape(28.dp))
        ) {
            Column(
                modifier            = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(accentViolet.copy(0.12f))
                        .border(BorderStroke(1.5.dp, accentViolet.copy(0.35f)), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Default.Lock,
                        contentDescription = null,
                        tint               = accentViolet,
                        modifier           = Modifier.size(30.dp)
                    )
                }

                Text(
                    text       = stringResource(R.string.excl_title),
                    fontWeight = FontWeight.ExtraBold,
                    fontStyle  = FontStyle.Italic,
                    fontSize   = 20.sp,
                    color      = MaterialTheme.colorScheme.onSurface,
                    textAlign  = TextAlign.Center
                )

                Text(
                    text       = stringResource(R.string.excl_gate_desc_new),
                    fontSize   = 13.sp,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign  = TextAlign.Center,
                    lineHeight = 20.sp
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f))

                Surface(
                    onClick  = onUpgrade,
                    shape    = CircleShape,
                    color    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier              = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.WorkspacePremium,
                            null,
                            tint     = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text       = stringResource(R.string.action_upgrade_premium),
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                Surface(
                    onClick  = onWatchAds,
                    shape    = CircleShape,
                    color    = accentViolet,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier              = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CardGiftcard,
                            null,
                            tint     = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text       = stringResource(R.string.excl_gate_go_to_reward),
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text(
                        text     = stringResource(R.string.excl_gate_maybe_later),
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
