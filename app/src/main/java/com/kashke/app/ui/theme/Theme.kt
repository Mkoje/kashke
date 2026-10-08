package com.kashke.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF0C1F3D)
private val NavySecondary = Color(0xFF173B68)
private val GrowthGreen = Color(0xFF1DBB73)
private val SuccessSoft = Color(0xFFDCFCE7)
private val Surface = Color(0xFFF7F9FC)
private val TextPrimary = Color(0xFF172033)
private val TextSoft = Color(0xFF5F6C7B)
private val WarningRed = Color(0xFFD64B4B)

private val LightColors = lightColorScheme(
    primary = Navy,
    secondary = GrowthGreen,
    tertiary = NavySecondary,
    background = Surface,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    primaryContainer = Color(0xFFE1EAF6), onPrimaryContainer = Navy,
    secondaryContainer = SuccessSoft, onSecondaryContainer = Color(0xFF124C32),
    surfaceVariant = Color(0xFFEDF1F6), onSurfaceVariant = TextSoft,
    outline = Color(0xFF747F8E), outlineVariant = Color(0xFFD9E0E9),
    error = WarningRed
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4CDF0), onPrimary = Color(0xFF102C4C),
    primaryContainer = Color(0xFF253F60), onPrimaryContainer = Color(0xFFD7E7FF),
    secondary = Color(0xFF76DAAB), onSecondary = Color(0xFF003823),
    secondaryContainer = Color(0xFF174D36), onSecondaryContainer = Color(0xFFB4F4CF),
    tertiary = Color(0xFFAACCE8), onTertiary = Color(0xFF153549),
    background = Color(0xFF0E1520), onBackground = Color(0xFFE4EAF2),
    surface = Color(0xFF151F2D), onSurface = Color(0xFFE4EAF2),
    surfaceVariant = Color(0xFF263241), onSurfaceVariant = Color(0xFFC1CDDA),
    outline = Color(0xFF8996A6), outlineVariant = Color(0xFF3D4A59),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)
private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

enum class AppThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }
val LocalAppThemeMode = androidx.compose.runtime.staticCompositionLocalOf { AppThemeMode.SYSTEM }
val LocalSetAppThemeMode = androidx.compose.runtime.staticCompositionLocalOf<(AppThemeMode) -> Unit> { {} }

@Composable
fun KashKeTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = androidx.compose.runtime.remember(context) { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var mode by androidx.compose.runtime.remember(preferences) {
        androidx.compose.runtime.mutableStateOf(runCatching {
            AppThemeMode.valueOf(preferences.getString("theme", AppThemeMode.SYSTEM.name)!!)
        }.getOrDefault(AppThemeMode.SYSTEM))
    }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (mode) { AppThemeMode.SYSTEM -> systemDark; AppThemeMode.DARK -> true; AppThemeMode.LIGHT -> false }
    androidx.compose.runtime.SideEffect {
        (context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAppThemeMode provides mode,
        LocalSetAppThemeMode provides { selected ->
            mode = selected
            preferences.edit().putString("theme", selected.name).apply()
        }
    ) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
    }
}