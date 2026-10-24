package com.ladygo.driver.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val ButtonHeight = 56.dp
private val ButtonContentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)

@Composable
private fun ButtonLabel(
    text: String,
    icon: ImageVector?,
    loading: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
                color = ButtonDefaults.buttonColors().contentColor,
            )
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** The one main action on a screen. Pill-shaped, 56 dp tall, full width by default. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = CircleShape,
        contentPadding = ButtonContentPadding,
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonHeight),
    ) { ButtonLabel(text, icon, loading) }
}

/** A calmer, outlined action beside or under the primary one. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.5.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        contentPadding = ButtonContentPadding,
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonHeight),
    ) { ButtonLabel(text, icon, loading = false) }
}

/** Low-emphasis text action (back, cancel, log out). Still a 48 dp target. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) { ButtonLabel(text, icon, loading = false) }
}

/** Call 112: the only red element in the app besides errors - clear, never alarming. */
@Composable
fun EmergencyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        contentPadding = ButtonContentPadding,
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonHeight),
    ) { ButtonLabel(text, icon, loading = false) }
}
