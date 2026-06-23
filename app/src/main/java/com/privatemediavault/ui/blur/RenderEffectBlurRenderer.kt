package com.privatemediavault.ui.blur

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Default [BlurRenderer]. On API 31+ the [blurModifier] path renders via a hardware
 * `RenderEffect`; on older devices callers fall back to [blurredPosterFrame].
 *
 * All pixel math is delegated to the Android-free [BlurCore] so the indiscernibility
 * guarantee (Req 6.2) is verified by Property 13 on the JVM.
 */
class RenderEffectBlurRenderer : BlurRenderer {

    override fun blurModifier(radius: Dp): Modifier = Modifier.blur(radius)

    override fun blurredPosterFrame(decrypted: Bitmap): Bitmap {
        val width = decrypted.width
        val height = decrypted.height
        if (width == 0 || height == 0) {
            return decrypted.copy(SAFE_CONFIG, false)
        }
        val pixels = IntArray(width * height)
        decrypted.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurred = BlurCore.blurredPosterFrame(pixels, width, height)
        return Bitmap.createBitmap(blurred, width, height, SAFE_CONFIG)
    }

    override fun isDiscernible(original: Bitmap, blurred: Bitmap): Boolean {
        // Comparable detail requires matching dimensions; differing sizes are treated as
        // a non-comparison and reported as not discernible.
        if (original.width != blurred.width || original.height != blurred.height) return false
        val width = original.width
        val height = original.height
        if (width == 0 || height == 0) return false

        val originalPixels = IntArray(width * height)
        val blurredPixels = IntArray(width * height)
        original.getPixels(originalPixels, 0, width, 0, 0, width, height)
        blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)
        return BlurCore.isDiscernible(originalPixels, blurredPixels, width, height)
    }

    companion object {
        /** Radius for the default blurred state; high enough that content is obscured (Req 6.2). */
        val DEFAULT_BLUR_RADIUS: Dp = 30.dp

        private val SAFE_CONFIG = Bitmap.Config.ARGB_8888
    }
}
