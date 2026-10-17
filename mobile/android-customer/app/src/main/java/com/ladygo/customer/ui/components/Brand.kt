package com.ladygo.customer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ladygo.customer.ui.theme.LadyGoTheme

/**
 * The Lady Go mark: a protective shield with a heart and a rose spark, on a soft rounded tile. Drawn in code (no
 * asset). It springs in once, then the heart beats and the spark glows (skipped when [animated] is false).
 */
@Composable
fun LogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    animated: Boolean = true,
) {
    val entrance = remember { Animatable(if (animated) 0.6f else 1f) }
    LaunchedEffect(animated) {
        if (animated) entrance.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val pulse = rememberInfiniteTransition(label = "logo")
    val beat by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (animated) 1.16f else 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "beat",
    )
    val glow by pulse.animateFloat(
        initialValue = if (animated) 0.4f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "glow",
    )
    Box(
        modifier =
            modifier
                .size(size)
                .scale(entrance.value)
                .clip(RoundedCornerShape(size * 0.3f))
                .background(Color.White.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = LadyGoTheme.extra.heroEnd,
            modifier = Modifier.size(size * 0.26f).scale(beat),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = size * 0.14f, end = size * 0.14f)
                    .size(size * 0.12f)
                    .alpha(glow)
                    .background(Color(0xFFFF8FAB), CircleShape),
        )
    }
}

/**
 * Gradient hero used on the first screens (login, onboarding): the brand, a promise and room to breathe.
 * Content below it sits on the normal background with rounded top corners overlapping the hero.
 */
@Composable
fun BrandHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(LadyGoTheme.extra.heroStart, LadyGoTheme.extra.heroEnd)))
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 56.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LogoMark()
            Text(title, style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.88f))
        }
    }
}

/** A small circular avatar with initials (we never show a stranger's photo unless the user opted in). */
@Composable
fun InitialsAvatar(
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val initials =
        name
            .orEmpty()
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials.ifEmpty { "?" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
