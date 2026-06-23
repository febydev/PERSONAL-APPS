package com.privatemediavault.ui.blur

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertFalse
import java.util.Random

/**
 * Property-based test for [BlurCore], covering the blur correctness property from the
 * design.
 *
 * The Android [BlurRenderer] marshals `Bitmap` pixels into [BlurCore], whose blur math is
 * pure JVM and runs under jqwik without an Android runtime. Source images are generated as
 * packed-ARGB `IntArray`s with varied dimensions and content (uniform random pixels, smooth
 * gradients, and high-contrast edge/text-like patterns) so the property is exercised across
 * the full spectrum of fine-detail energy the poster-frame blur must destroy.
 */
class BlurCorePropertyTest {

    // Feature: private-media-vault, Property 13: Blurred rendering is not discernible
    // Validates: Requirements 6.2
    // For any source image, isDiscernible(original, blurred) is false for the produced
    // blurred output: the poster-frame blur collapses recoverable fine detail (edges, text,
    // texture) below the discernibility threshold.
    @Property(tries = 100)
    fun `poster-frame blur output is never discernible`(
        @ForAll("sourceImages") image: ImageCase
    ) {
        val blurred = BlurCore.blurredPosterFrame(image.pixels, image.width, image.height)

        assertFalse(
            "blurred output must not be discernible for a ${image.width}x${image.height} " +
                "${image.content} image (discernibility=" +
                "${BlurCore.discernibility(image.pixels, blurred, image.width, image.height)})",
            BlurCore.isDiscernible(image.pixels, blurred, image.width, image.height)
        )
    }

    /** A generated source image: packed-ARGB pixels plus its dimensions and content kind. */
    data class ImageCase(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val content: Content,
    ) {
        override fun toString(): String = "${width}x$height $content"
    }

    enum class Content { RANDOM, GRADIENT, EDGES }

    /**
     * Generates images with dimensions in [MIN_DIM, MAX_DIM] (a few dozen px per side, per
     * the impl's note that each blur block spans several pixels), a content kind, and a seed
     * that deterministically fills the pixel buffer for that kind.
     */
    @Provide
    fun sourceImages(): Arbitrary<ImageCase> {
        val widths = Arbitraries.integers().between(MIN_DIM, MAX_DIM)
        val heights = Arbitraries.integers().between(MIN_DIM, MAX_DIM)
        val contents = Arbitraries.of(*Content.values())
        val seeds = Arbitraries.longs()
        return Combinators.combine(widths, heights, contents, seeds)
            .`as` { w, h, content, seed -> ImageCase(buildPixels(w, h, content, seed), w, h, content) }
    }

    private fun buildPixels(width: Int, height: Int, content: Content, seed: Long): IntArray {
        val rnd = Random(seed)
        return when (content) {
            // Worst case for any low-pass filter: independent random pixels carry maximal
            // high-frequency energy. A heavy blur must still flatten it.
            Content.RANDOM -> IntArray(width * height) { argb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }

            // Smooth diagonal gradient: low fine-detail energy. The blur must keep it
            // indiscernible (and the impl reports zero detail for near-flat fields).
            Content.GRADIENT -> IntArray(width * height) { i ->
                val x = i % width
                val y = i / width
                val v = ((x + y) * 255) / ((width - 1) + (height - 1)).coerceAtLeast(1)
                argb(v, v, v)
            }

            // High-contrast edges / text-like strokes: random-width black/white stripes plus
            // sharp rectangular blocks. This is the strongest discernible detail a blur must
            // remove to satisfy Req 6.2.
            Content.EDGES -> buildEdges(width, height, rnd)
        }
    }

    private fun buildEdges(width: Int, height: Int, rnd: Random): IntArray {
        val stripe = 1 + rnd.nextInt(4)
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            // Alternating stripes form sharp repeating edges (text/texture-like).
            if (((x / stripe) + (y / stripe)) % 2 == 0) WHITE else BLACK
        }
        // Stamp a couple of solid high-contrast blocks for letterform-like detail.
        repeat(2 + rnd.nextInt(3)) {
            val bw = 1 + rnd.nextInt(width)
            val bh = 1 + rnd.nextInt(height)
            val ox = rnd.nextInt(width)
            val oy = rnd.nextInt(height)
            val color = if (rnd.nextBoolean()) WHITE else BLACK
            for (yy in oy until minOf(oy + bh, height)) {
                for (xx in ox until minOf(ox + bw, width)) {
                    pixels[yy * width + xx] = color
                }
            }
        }
        return pixels
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private companion object {
        const val MIN_DIM = 16
        const val MAX_DIM = 96
        val WHITE = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
        const val BLACK = 0xFF shl 24
    }
}
