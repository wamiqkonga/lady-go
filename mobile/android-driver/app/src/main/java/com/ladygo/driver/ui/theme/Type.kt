package com.ladygo.driver.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ladygo.driver.R

/** Nunito (bundled variable font, OFL - mobile/THIRD_PARTY_FONTS.md): rounded, friendly, very legible. */
@OptIn(ExperimentalTextApi::class)
val Nunito =
    FontFamily(
        listOf(
            FontWeight.Normal,
            FontWeight.Medium,
            FontWeight.SemiBold,
            FontWeight.Bold,
            FontWeight.ExtraBold,
        ).map { weight ->
            Font(R.font.nunito_variable, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        },
    )

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight,
) = TextStyle(fontFamily = Nunito, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

val LadyGoTypography =
    Typography(
        displayLarge = style(40, 48, FontWeight.Bold),
        displayMedium = style(34, 42, FontWeight.Bold),
        displaySmall = style(30, 38, FontWeight.Bold),
        headlineLarge = style(28, 36, FontWeight.Bold),
        headlineMedium = style(24, 32, FontWeight.SemiBold),
        headlineSmall = style(22, 30, FontWeight.SemiBold),
        titleLarge = style(20, 28, FontWeight.SemiBold),
        titleMedium = style(18, 26, FontWeight.SemiBold),
        titleSmall = style(16, 24, FontWeight.SemiBold),
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = style(16, 24, FontWeight.Normal),
        bodySmall = style(14, 20, FontWeight.Normal),
        labelLarge = style(16, 20, FontWeight.SemiBold),
        labelMedium = style(14, 20, FontWeight.SemiBold),
        labelSmall = style(12, 16, FontWeight.Medium),
    )
