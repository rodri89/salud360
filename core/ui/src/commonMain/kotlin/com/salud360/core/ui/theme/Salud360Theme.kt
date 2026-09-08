package com.salud360.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Salud360Colors.TealStart,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EBF1),
    onPrimaryContainer = Salud360Colors.TealEnd,
    secondary = Salud360Colors.Indigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E5F7),
    onSecondaryContainer = Salud360Colors.Indigo,
    tertiary = Salud360Colors.SkyEnd,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCEFEA),
    background = Salud360Colors.Background,
    onBackground = Salud360Colors.OnBackground,
    surface = Salud360Colors.Surface,
    onSurface = Salud360Colors.OnBackground,
    surfaceVariant = Salud360Colors.SurfaceVariant,
    onSurfaceVariant = Color(0xFF4F5B5E),
    outline = Color(0xFFB9C4C7),
    error = Salud360Colors.Danger,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EC1D6),
    onPrimary = Salud360Colors.TealEnd,
    primaryContainer = Salud360Colors.TealMid,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFA9B4FF),
    onSecondary = Color(0xFF1A2370),
    secondaryContainer = Color(0xFF2B3585),
    onSecondaryContainer = Color(0xFFE2E5F7),
    tertiary = Color(0xFF7ADCCF),
    onTertiary = Color(0xFF00382F),
    background = Salud360Colors.DarkBackground,
    onBackground = Salud360Colors.DarkOnBackground,
    surface = Salud360Colors.DarkSurface,
    onSurface = Salud360Colors.DarkOnBackground,
    surfaceVariant = Salud360Colors.DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB8C6CA),
    outline = Color(0xFF5B6E73),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3A0000),
)

/** Tipografía inspirada en Nunito/Roboto de SB Admin 2, con escala Material 3. */
val Salud360Typography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 16.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

/** Formas: los botones originales son "píldoras" (border-radius 120px), las tarjetas tienen esquinas suaves. */
val Salud360Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Permite a las pantallas saber si están en modo oscuro sin consultar el sistema. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun Salud360Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Salud360Typography,
            shapes = Salud360Shapes,
            content = content,
        )
    }
}
