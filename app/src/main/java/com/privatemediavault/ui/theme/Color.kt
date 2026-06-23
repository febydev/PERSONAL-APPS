package com.privatemediavault.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Private Media Vault palette: a refined, always-dark "glassmorphism" scheme tuned for a
 * premium privacy aesthetic. The two background stops drive the [AppBackground] gradient
 * (deep near-black -> charcoal-violet); the violet accent carries primary actions, and the
 * white-alpha tints feed the translucent glass surfaces and the text hierarchy.
 */

// --- Background gradient stops ---
/** Top of the screen gradient: a deep, near-black with the faintest violet bias. */
val VaultBackgroundTop = Color(0xFF0B0B12)

/** Bottom of the screen gradient: a charcoal-violet that lifts the dark base. */
val VaultBackgroundBottom = Color(0xFF15131F)

/** A flat fallback background used where a single solid color is required. */
val VaultBackground = Color(0xFF0E0D16)

// --- Accent / primary ---
/** Primary accent: a tasteful violet/indigo used for emphasis, dots, and key actions. */
val VaultAccent = Color(0xFF8B7CFF)

/** A slightly deeper accent for pressed/secondary container tints. */
val VaultAccentDeep = Color(0xFF6F5FE0)

/** Foreground drawn on top of the accent (near-black for legible contrast). */
val VaultOnAccent = Color(0xFF120F22)

/** Subtle secondary accent, used sparingly for secondary chips/containers. */
val VaultSecondary = Color(0xFFB7AEE8)

// --- Surfaces ---
/** Nominal surface color sitting just above the background. */
val VaultSurface = Color(0xFF15131F)

/** A raised surface variant for cards/sections that need a touch more presence. */
val VaultSurfaceVariant = Color(0xFF211E2E)

// --- Text ---
/** Full-strength text on dark surfaces. */
val VaultTextPrimary = Color(0xFFFFFFFF)

/** Muted text (~70% white) for secondary copy and supporting labels. */
val VaultTextSecondary = Color(0xB3FFFFFF)

// --- Error ---
/** A soft, desaturated red that reads as a warning without clashing with the violet. */
val VaultError = Color(0xFFFF6B6B)

/** Foreground drawn on top of the error color. */
val VaultOnError = Color(0xFF1A0F12)

// --- Glass tints (white-alpha) ---
/** Translucent fill for glass surfaces (~10% white). */
val GlassFill = Color(0x1AFFFFFF)

/** A slightly stronger glass fill for elevated/active glass elements (~14% white). */
val GlassFillStrong = Color(0x24FFFFFF)

/** Hairline light border for glass edges (~16% white). */
val GlassBorder = Color(0x29FFFFFF)

/** Top-light sheen start used for the faint diagonal highlight on glass (~22% white). */
val GlassSheen = Color(0x38FFFFFF)
