package org.jianyu.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF356859),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E9DF),
    onPrimaryContainer = Color(0xFF17382F),
    secondary = Color(0xFF8B5E34),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E1CC),
    onSecondaryContainer = Color(0xFF422A14),
    tertiary = Color(0xFF59637A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E5F2),
    onTertiaryContainer = Color(0xFF182033),
    background = Color(0xFFF8F6F0),
    onBackground = Color(0xFF1B1C19),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF1B1C19),
    surfaceDim = Color(0xFFE0DED7),
    surfaceBright = Color(0xFFFFFBF5),
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Color(0xFFF6F3EC),
    surfaceContainer = Color(0xFFF0EDE5),
    surfaceContainerHigh = Color(0xFFEAE6DC),
    surfaceContainerHighest = Color(0xFFE3DFD5),
    surfaceVariant = Color(0xFFEAE6DC),
    onSurfaceVariant = Color(0xFF484942),
    outline = Color(0xFF79766E),
    outlineVariant = Color(0xFFCAC6BC),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = Color(0xFF356859),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8D2C1),
    onPrimary = Color(0xFF0D372B),
    primaryContainer = Color(0xFF245043),
    onPrimaryContainer = Color(0xFFC5EBDD),
    secondary = Color(0xFFE6BE96),
    onSecondary = Color(0xFF472A0F),
    secondaryContainer = Color(0xFF604126),
    onSecondaryContainer = Color(0xFFFFDCB7),
    tertiary = Color(0xFFBEC7DF),
    onTertiary = Color(0xFF293146),
    tertiaryContainer = Color(0xFF41495E),
    onTertiaryContainer = Color(0xFFDDE4FA),
    background = Color(0xFF121411),
    onBackground = Color(0xFFE3E3DD),
    surface = Color(0xFF191C18),
    onSurface = Color(0xFFE3E3DD),
    surfaceDim = Color(0xFF101210),
    surfaceBright = Color(0xFF363A36),
    surfaceContainerLowest = Color(0xFF0C0E0C),
    surfaceContainerLow = Color(0xFF171A17),
    surfaceContainer = Color(0xFF1D211D),
    surfaceContainerHigh = Color(0xFF272B27),
    surfaceContainerHighest = Color(0xFF323632),
    surfaceVariant = Color(0xFF414842),
    onSurfaceVariant = Color(0xFFC1C8C1),
    outline = Color(0xFF8B938D),
    outlineVariant = Color(0xFF414842),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = Color(0xFFA8D2C1),
)

private val JianyuTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

private val JianyuShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun JianyuTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, typography = JianyuTypography, shapes = JianyuShapes, content = content)
}
