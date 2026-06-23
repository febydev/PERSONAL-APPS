package com.privatemediavault.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Default corner radius for glass surfaces — large and soft for the premium look. */
val GlassCornerRadius: Dp = 24.dp

/**
 * The signature full-screen background: a vertical gradient from the deep near-black
 * [VaultBackgroundTop] down to the charcoal-violet [VaultBackgroundBottom]. Place screen
 * content inside its [content] slot so every screen shares the same backdrop behind its
 * glass surfaces.
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(VaultBackgroundTop, VaultBackgroundBottom),
                ),
            ),
        content = content,
    )
}

/**
 * The reusable glassmorphism treatment: a translucent fill, a 1px hairline light border, a
 * large corner radius, and (optionally) a faint diagonal top-light sheen. Compose it onto any
 * layout to give it the frosted-glass card look.
 *
 * @param shape       the clip/border shape; defaults to a rounded rectangle at [cornerRadius].
 * @param cornerRadius corner radius used when [shape] is left at its default.
 * @param fill        the translucent fill color (white-alpha by default).
 * @param borderColor the hairline edge color (white-alpha by default).
 * @param sheen       when `true`, overlays a subtle top-left light gradient for depth.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(GlassCornerRadius),
    cornerRadius: Dp = GlassCornerRadius,
    fill: Color = GlassFill,
    borderColor: Color = GlassBorder,
    sheen: Boolean = true,
): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .then(
        if (sheen) {
            Modifier.drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(GlassSheen, Color.Transparent),
                        start = Offset.Zero,
                        end = Offset(size.width * 0.6f, size.height * 0.6f),
                    ),
                )
            }
        } else {
            Modifier
        },
    )
    .border(1.dp, borderColor, shape)

/**
 * A frosted-glass card: a soft drop shadow, the [glass] treatment, and an inner [padding].
 * The default entry point for grouping content into a premium section card.
 *
 * @param onContentPadding inner padding applied around [content]; set to `0.dp` for edge-to-edge
 *   children (e.g. a thumbnail that should fill the card).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassCornerRadius),
    fill: Color = GlassFill,
    borderColor: Color = GlassBorder,
    sheen: Boolean = true,
    shadowElevation: Dp = 12.dp,
    onContentPadding: Dp = 20.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(shadowElevation, shape, clip = false)
            .glass(shape = shape, fill = fill, borderColor = borderColor, sheen = sheen)
            .padding(onContentPadding),
        content = content,
    )
}

/**
 * A lighter-weight glass surface without a drop shadow or default padding — use it for
 * controls (keypad keys, FABs, inline pills) where the card chrome would be too heavy.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassCornerRadius),
    fill: Color = GlassFill,
    borderColor: Color = GlassBorder,
    sheen: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.glass(shape = shape, fill = fill, borderColor = borderColor, sheen = sheen),
        content = content,
    )
}
