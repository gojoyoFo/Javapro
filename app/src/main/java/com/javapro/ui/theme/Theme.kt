package com.javapro.ui.theme

import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.javapro.utils.PreferenceManager

@Composable
fun JavaProTheme(
    prefManager: PreferenceManager,
    content: @Composable () -> Unit
) {
    val isDarkPref by prefManager.darkModeFlow.collectAsState(initial = true)
    val context = LocalContext.current

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDarkPref) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDarkPref -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
