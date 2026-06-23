package com.privatemediavault.ui.blur

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Encapsulates the blur strategy so transport/storage and UI remain decoupled
 * (design: BlurRenderer). The vault renders every Media Item in Blurred State by
 * default; this type produces that obscured rendering and provides a QA helper to
 * assert the original content is no longer discernible (Req 6.1, 6.2).
 *
 * Two rendering paths exist:
 *  - [blurModifier] is the preferred GPU path. On API 31+ Compose's `Modifier.blur`
 *    is backed by a hardware `RenderEffect`.
 *  - [blurredPosterFrame] is the fallback for API < 31, where `Modifier.blur` is a
 *    no-op. It produces a pre-blurred bitmap to display in place of the original.
 */
interface BlurRenderer {

    /**
     * Returns a [Modifier] that blurs the composable it is applied to by [radius].
     *
     * Backed by a hardware `RenderEffect` on API 31+. On older API levels Compose
     * ignores this modifier, so callers targeting those devices must instead display
     * the output of [blurredPosterFrame].
     */
    fun blurModifier(radius: Dp): Modifier

    /**
     * Produces a heavily blurred copy of [decrypted] for the API < 31 fallback path.
     *
     * The returned bitmap obscures the original such that its visual content is no
     * longer discernible (Req 6.2): [isDiscernible] returns `false` for the pair
     * `(decrypted, result)`.
     */
    fun blurredPosterFrame(decrypted: Bitmap): Bitmap

    /**
     * QA/test helper. Returns `true` when the original visual content of [original]
     * is still discernible in [blurred].
     *
     * "Discernible" means recognisable fine detail (edges, text, texture) survives the
     * blur. This is measured by comparing high-frequency (Laplacian) detail energy of
     * [blurred] against [original]; smooth gradients and flat regions carry no such
     * detail and are therefore never considered discernible. See [BlurCore].
     */
    fun isDiscernible(original: Bitmap, blurred: Bitmap): Boolean
}
