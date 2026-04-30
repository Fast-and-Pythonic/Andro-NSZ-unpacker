package com.androNSZ.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AppDropdownMenuItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val iconColor = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = if (destructive) MaterialTheme.colorScheme.error
                    else LocalContentColor.current

    // Disable the Material3 global 48dp minimum touch target so items wrap their content.
    // Without this, clickable() enforces 48dp height regardless of our padding.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        CompositionLocalProvider(LocalContentColor provides textColor) {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (leadingIcon != null) Arrangement.spacedBy(12.dp) else Arrangement.Start
            ) {
                if (leadingIcon != null) {
                    CompositionLocalProvider(LocalContentColor provides iconColor) {
                        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            leadingIcon()
                        }
                    }
                }
                content()
            }
        }
    }
}
