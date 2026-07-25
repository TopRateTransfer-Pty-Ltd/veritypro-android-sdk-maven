package com.example.veritypro_sdk.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class CustomColors(
    val textSecondary: Color,
    val border: Color,
    val iconTint: Color,
    val subTitle: Color,
    val description: Color,
    val surface: Color,
    val containerBorder: Color,
    val icon: Color,
    val background: Color,
    val powered: Color,
    val content: Color,
    val title: Color,
    val privacyColor: Color,
)

private val LightCustomColors = CustomColors(
    textSecondary = Color(0xFF666666),
    border = Color(0xFFE0E0E0),
    iconTint = Color(0xFF444444),
    subTitle = Color(0xFF717680),
    description = Color(0XFF535862),
    surface = Color(0XFFFAFAFA),
    containerBorder = Color(0XFFD5D7DA),
    icon = Color(0XFF023074),
    background = Color(0xFFFFFFFF),
    powered = Color(0XFFA4A7AE),
    content = Color(0XFFFFFFFF),
    title = Color(0XFF141414),
    privacyColor = Color(0XFF023074)
)

private val DarkCustomColors = CustomColors(
    textSecondary = Color(0xFFAAAAAA),
    border = Color(0xFF444444),
    iconTint = Color(0xFFCCCCCC),
    subTitle = Color(0xFFFFFFFF),
    description = Color(0xFFD5D7DA),
    surface = Color(0XFF252B37),
    containerBorder = Color(0XFFD5D7DA),
    icon = Color(0XFFFFFFFF),
    background = Color(0XFF252B37),
    powered = Color(0XFFD5D7DA),
    content = Color(0XFFFFFFFF),
    title = Color(0XFFFFFFFF),
    privacyColor = Color(0XFF2B7AEF)







)

private val LightColorPalette = lightColorScheme(
    primary = Color(0xFF2B7AEF),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFF6200EE),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141414),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surfaceVariant = Color(0XFFFAFAFA)
)

private val DarkColorPalette = darkColorScheme(
    primary = Color(0xFF2B7AEF),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFFBB86FC),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFFFFFFF),
    background = Color(0XFF252B37),
    onBackground = Color(0xFFFFFFFF),
    surfaceVariant = Color(0XFF252B37)
)

val LocalCustomColors = staticCompositionLocalOf { LightCustomColors }

val MaterialTheme.customColors: CustomColors
    @Composable
    @ReadOnlyComposable
    get() = LocalCustomColors.current

// Verification-SDK redesign (B1): generated design-token colors (D1). New screens bind to
// MaterialTheme.verityColors; legacy screens keep using customColors during convergence.
val LocalVerityColors = staticCompositionLocalOf { VerityLightColors }

val MaterialTheme.verityColors: VerityColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVerityColors.current

@Composable
fun VerityProTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val useDarkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDarkTheme -> DarkColorPalette
        else -> LightColorPalette
    }

    val customColors = if (useDarkTheme) DarkCustomColors else LightCustomColors
    // B1: generated verification-SDK token colors, theme-selected.
    val verityColors = if (useDarkTheme) VerityDarkColors else VerityLightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            window?.statusBarColor = colorScheme.primary.toArgb()
            window?.navigationBarColor = colorScheme.surface.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = Shapes,
        content = {
            CompositionLocalProvider(
                LocalCustomColors provides customColors,
                LocalVerityColors provides verityColors
            ) {
                content()
            }
        }
    )
}









