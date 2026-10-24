package com.ladygo.customer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colours Material 3 has no slot for: success/verified and warning (design-system.md section 2). */
@Immutable
data class ExtraColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val heroStart: Color,
    val heroEnd: Color,
)

private val LightExtra =
    ExtraColors(
        success = SageTeal,
        onSuccess = Color.White,
        successContainer = SageContainer,
        onSuccessContainer = Color(0xFF0B3B2F),
        warning = Amber,
        warningContainer = AmberContainer,
        onWarningContainer = Color(0xFF3F2900),
        heroStart = Plum,
        heroEnd = PlumDeep,
    )
private val DarkExtra =
    ExtraColors(
        success = SageTealDark,
        onSuccess = Color(0xFF00382B),
        successContainer = SageContainerDark,
        onSuccessContainer = Color(0xFFC6F0E1),
        warning = AmberDark,
        warningContainer = AmberContainerDark,
        onWarningContainer = Color(0xFFFFE2A8),
        heroStart = Color(0xFF4A1D5A),
        heroEnd = Color(0xFF2B0F37),
    )

private val LocalExtraColors = staticCompositionLocalOf { LightExtra }

object LadyGoTheme {
    val extra: ExtraColors
        @Composable @ReadOnlyComposable
        get() = LocalExtraColors.current
}

private val LightColors =
    lightColorScheme(
        primary = Plum,
        onPrimary = Color.White,
        primaryContainer = BlushLilac,
        onPrimaryContainer = Color(0xFF3A0F3F),
        secondary = Rose,
        onSecondary = Color.White,
        secondaryContainer = RoseContainer,
        onSecondaryContainer = Color(0xFF3D0A1D),
        tertiary = SageTeal,
        onTertiary = Color.White,
        tertiaryContainer = SageContainer,
        onTertiaryContainer = Color(0xFF0B3B2F),
        error = ErrorRed,
        onError = Color.White,
        errorContainer = ErrorContainer,
        onErrorContainer = Color(0xFF410E0B),
        background = BackgroundLight,
        onBackground = OnSurfaceLight,
        surface = SurfaceLight,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        outlineVariant = OutlineLight,
    )

private val DarkColors =
    darkColorScheme(
        primary = PlumDark,
        onPrimary = Color(0xFF3A0F3F),
        primaryContainer = BlushLilacDark,
        onPrimaryContainer = Color(0xFFF6D9F7),
        secondary = RoseDark,
        onSecondary = Color(0xFF5E1A33),
        secondaryContainer = RoseContainerDark,
        onSecondaryContainer = Color(0xFFFFD9E1),
        tertiary = SageTealDark,
        onTertiary = Color(0xFF00382B),
        tertiaryContainer = SageContainerDark,
        onTertiaryContainer = Color(0xFFC6F0E1),
        error = ErrorRedDark,
        onError = Color(0xFF601410),
        errorContainer = ErrorContainerDark,
        onErrorContainer = Color(0xFFFFDAD6),
        background = BackgroundDark,
        onBackground = OnSurfaceDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        outlineVariant = OutlineDark,
    )

private val LadyGoShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

@Composable
fun LadyGoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalExtraColors provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = LadyGoTypography,
            shapes = LadyGoShapes,
            content = content,
        )
    }
}
