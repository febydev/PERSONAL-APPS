package com.privatemediavault.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The always-dark Material3 color scheme for the vault. Kept dark unconditionally — this is a
 * privacy/premium aesthetic, not a system-following theme — so light mode never leaks the
 * glassmorphism look.
 */
private val VaultColorScheme = darkColorScheme(
    primary = VaultAccent,
    onPrimary = VaultOnAccent,
    primaryContainer = VaultAccentDeep,
    onPrimaryContainer = VaultTextPrimary,
    secondary = VaultSecondary,
    onSecondary = VaultOnAccent,
    secondaryContainer = VaultSurfaceVariant,
    onSecondaryContainer = VaultTextPrimary,
    tertiary = VaultSecondary,
    onTertiary = VaultOnAccent,
    background = VaultBackground,
    onBackground = VaultTextPrimary,
    surface = VaultSurface,
    onSurface = VaultTextPrimary,
    surfaceVariant = VaultSurfaceVariant,
    onSurfaceVariant = VaultTextSecondary,
    error = VaultError,
    onError = VaultOnError,
    outline = GlassBorder,
)

/**
 * Applies the vault's dark Material3 [VaultColorScheme] and [VaultTypography] to [content].
 *
 * Wrap the whole app in this once (at the activity's `setContent`); individual screens then
 * read colors and typography from [MaterialTheme] as usual. Screens should additionally sit
 * on [AppBackground] so the signature gradient shows behind their glass surfaces.
 */
@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VaultColorScheme,
        typography = VaultTypography,
        content = content,
    )
}
