package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = SleekPrimary,
    secondary = SleekPrimaryContainer,
    background = SleekBackground,
    surface = SleekSurfaceVariant,
    onPrimary = Color.White,
    onBackground = SleekOnBackground,
    onSurface = SleekOnBackground,
    outline = SleekOutline,
    outlineVariant = SleekOutlineVariant
  )

private val LightColorScheme =
  lightColorScheme(
    primary = SleekPrimary,
    secondary = SleekPrimaryContainer,
    background = SleekBackground,
    surface = SleekSurfaceVariant,
    onPrimary = Color.White,
    onBackground = SleekOnBackground,
    onSurface = SleekOnBackground,
    outline = SleekOutline,
    outlineVariant = SleekOutlineVariant
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Set dynamic color to false by default to enforce our beautiful custom theme
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
