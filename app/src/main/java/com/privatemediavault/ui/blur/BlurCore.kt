package com.privatemediavault.ui.blur

import kotlin.math.max
import kotlin.math.min

/**
 * Pure, Android-free blur math operating on packed ARGB pixel arrays.
 *
 * Extracting the logic here lets Property 13 ("blurred rendering is not discernible")
 * run on the JVM with jqwik, since `android.graphics.Bitmap` cannot be instantiated in
 * a plain unit test. The Android [BlurRenderer] implementation simply marshals
 * `Bitmap` pixels into these functions.
 *
 * Pixels are laid out row-major: index `y * width + x`, each `Int` packed as
 * `0xAARRGGBB` to match `Bitmap.getPixels`.
 *
 * Discernibility model: content is "discernible" when recognisable fine detail (edges,
 * text, texture) survives the blur. Fine detail is high-frequency information, captured
 * here by the discrete Laplacian (a second difference). The Laplacian is zero for flat
 * regions and smooth gradients, so those carry no discernible content; it is large at
 * edges and texture. A heavy low-pass blur collapses that high-frequency energy, so the
 * ratio of blurred-to-original detail energy is small and the output is not discernible.
 */
object BlurCore {

    /**
     * Maximum number of averaging blocks along each axis for the poster-frame blur.
     * Fewer blocks means a stronger blur; capped low so realistic media is reduced to a
     * coarse colour field with no recoverable detail.
     */
    const val POSTER_BLOCKS: Int = 6

    /**
     * Discernibility threshold. The output is considered discernible when the fraction
     * of the original's fine-detail energy that survives the blur exceeds this value.
     */
    const val DISCERNIBLE_THRESHOLD: Double = 0.25

    /** Guards divide-by-zero when an image carries effectively no fine detail. */
    private const val EPSILON: Double = 1e-9

    /**
     * Produces a heavily blurred copy of [pixels] using a downscale-then-upscale
     * strategy: average the image into a small block grid, then bilinearly upscale it
     * back to full size. This is the API < 31 fallback path.
     *
     * The block count per axis is `min(POSTER_BLOCKS, dimension / 4)` (at least one), so
     * each block always spans several pixels and smaller images are blurred at least as
     * aggressively as larger ones.
     */
    fun blurredPosterFrame(pixels: IntArray, width: Int, height: Int): IntArray {
        require(width >= 0 && height >= 0) { "dimensions must be non-negative" }
        require(pixels.size == width * height) { "pixel count must equal width * height" }
        if (width == 0 || height == 0) return IntArray(0)

        val blocksX = max(1, min(POSTER_BLOCKS, width / 4))
        val blocksY = max(1, min(POSTER_BLOCKS, height / 4))
        return downscaleUpscale(pixels, width, height, blocksX, blocksY)
    }

    /**
     * Averages [pixels] into a [blocksX] x [blocksY] grid, then bilinearly interpolates
     * that grid back to [width] x [height]. Exposed for tests that want explicit control
     * over blur strength.
     */
    fun downscaleUpscale(
        pixels: IntArray,
        width: Int,
        height: Int,
        blocksX: Int,
        blocksY: Int,
    ): IntArray {
        if (width == 0 || height == 0) return IntArray(0)
        val bx = max(1, min(blocksX, width))
        val by = max(1, min(blocksY, height))

        // Accumulate per-block channel sums.
        val sumA = LongArray(bx * by)
        val sumR = LongArray(bx * by)
        val sumG = LongArray(bx * by)
        val sumB = LongArray(bx * by)
        val count = LongArray(bx * by)
        for (y in 0 until height) {
            val cellY = (y * by) / height
            for (x in 0 until width) {
                val cellX = (x * bx) / width
                val cell = cellY * bx + cellX
                val p = pixels[y * width + x]
                sumA[cell] = sumA[cell] + ((p ushr 24) and 0xFF).toLong()
                sumR[cell] = sumR[cell] + ((p ushr 16) and 0xFF).toLong()
                sumG[cell] = sumG[cell] + ((p ushr 8) and 0xFF).toLong()
                sumB[cell] = sumB[cell] + (p and 0xFF).toLong()
                count[cell] = count[cell] + 1L
            }
        }

        // Block averages form the small image.
        val smallA = IntArray(bx * by)
        val smallR = IntArray(bx * by)
        val smallG = IntArray(bx * by)
        val smallB = IntArray(bx * by)
        for (i in 0 until bx * by) {
            val n = max(1L, count[i])
            smallA[i] = (sumA[i] / n).toInt()
            smallR[i] = (sumR[i] / n).toInt()
            smallG[i] = (sumG[i] / n).toInt()
            smallB[i] = (sumB[i] / n).toInt()
        }

        // Bilinear upscale from block centres back to full resolution.
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val srcY = ((y + 0.5) * by / height) - 0.5
            val y0 = clamp(kotlin.math.floor(srcY).toInt(), 0, by - 1)
            val y1 = clamp(y0 + 1, 0, by - 1)
            val fy = clampUnit(srcY - kotlin.math.floor(srcY))
            for (x in 0 until width) {
                val srcX = ((x + 0.5) * bx / width) - 0.5
                val x0 = clamp(kotlin.math.floor(srcX).toInt(), 0, bx - 1)
                val x1 = clamp(x0 + 1, 0, bx - 1)
                val fx = clampUnit(srcX - kotlin.math.floor(srcX))

                val i00 = y0 * bx + x0
                val i01 = y0 * bx + x1
                val i10 = y1 * bx + x0
                val i11 = y1 * bx + x1

                val a = bilerp(smallA[i00], smallA[i01], smallA[i10], smallA[i11], fx, fy)
                val r = bilerp(smallR[i00], smallR[i01], smallR[i10], smallR[i11], fx, fy)
                val g = bilerp(smallG[i00], smallG[i01], smallG[i10], smallG[i11], fx, fy)
                val b = bilerp(smallB[i00], smallB[i01], smallB[i10], smallB[i11], fx, fy)
                out[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /**
     * Returns the fraction of the original's fine-detail energy that survives in
     * [blurred], in `[0, 1]`. `0` means all recoverable detail was removed; values near
     * `1` mean detail was preserved. Returns `0` when [original] carries no fine detail
     * (flat field or smooth gradient), since there is nothing to discern.
     */
    fun discernibility(
        original: IntArray,
        blurred: IntArray,
        width: Int,
        height: Int,
    ): Double {
        require(original.size == width * height) { "original size must equal width * height" }
        require(blurred.size == width * height) { "blurred size must equal width * height" }
        if (width < 3 || height < 3) return 0.0 // no interior pixels => no measurable detail

        val originalEnergy = laplacianEnergy(original, width, height)
        if (originalEnergy < EPSILON) return 0.0
        val blurredEnergy = laplacianEnergy(blurred, width, height)
        return min(1.0, blurredEnergy / originalEnergy)
    }

    /**
     * Returns `true` when [blurred] still preserves more than [threshold] of the
     * original's fine detail.
     */
    fun isDiscernible(
        original: IntArray,
        blurred: IntArray,
        width: Int,
        height: Int,
        threshold: Double = DISCERNIBLE_THRESHOLD,
    ): Boolean = discernibility(original, blurred, width, height) > threshold

    /** Mean squared discrete Laplacian of luminance over interior pixels. */
    private fun laplacianEnergy(pixels: IntArray, width: Int, height: Int): Double {
        var sum = 0.0
        var n = 0L
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val c = luminance(pixels[y * width + x])
                val up = luminance(pixels[(y - 1) * width + x])
                val down = luminance(pixels[(y + 1) * width + x])
                val left = luminance(pixels[y * width + (x - 1)])
                val right = luminance(pixels[y * width + (x + 1)])
                val lap = 4.0 * c - up - down - left - right
                sum += lap * lap
                n += 1
            }
        }
        return if (n == 0L) 0.0 else sum / n
    }

    /** ITU-R BT.601 luminance of a packed ARGB pixel. */
    private fun luminance(p: Int): Double {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    private fun bilerp(c00: Int, c01: Int, c10: Int, c11: Int, fx: Double, fy: Double): Int {
        val top = c00 + (c01 - c00) * fx
        val bottom = c10 + (c11 - c10) * fx
        val value = top + (bottom - top) * fy
        return clamp(Math.round(value).toInt(), 0, 255)
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))

    private fun clampUnit(v: Double): Double = max(0.0, min(1.0, v))
}
