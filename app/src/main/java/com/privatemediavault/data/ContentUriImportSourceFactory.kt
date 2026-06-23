package com.privatemediavault.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.privatemediavault.domain.model.MediaType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Builds a device-independent [ImportSource] from a content `android.net.Uri` picked by the
 * system photo/video picker, resolving the metadata and content the rest of the import
 * pipeline needs through a [Context]'s `ContentResolver` (and, for videos, a
 * [MediaMetadataRetriever]).
 *
 * This is the concrete `(Uri) -> ImportSource` factory the activity-level wiring hands to
 * the vault view model (and the `AndroidUriMediaRepository` bridge): it keeps every Android
 * `Uri`/`Bitmap`/`MediaMetadataRetriever` dependency out of [DefaultMediaRepository] and
 * [TinkEncryptedFileStore], which operate purely over streams and byte arrays.
 *
 * For each `Uri` it resolves:
 *  - the display name (from [OpenableColumns.DISPLAY_NAME], falling back to the last path
 *    segment),
 *  - the media type (image vs. video, from the resolver's MIME type),
 *  - the original size in bytes (from [OpenableColumns.SIZE]),
 *  - the duration in milliseconds for videos (from [MediaMetadataRetriever]),
 *  - a lazily-opened stream over the original bytes ([ImportSource.openStream]),
 *  - a lazily-generated, down-scaled JPEG thumbnail ([ImportSource.openThumbnail]), and
 *  - a best-effort delete of the original ([ImportSource.deleteOriginal]) used only when
 *    the remove-originals preference is enabled (Req 4.4).
 *
 * Thumbnail generation is pragmatic and never aborts an import: if a frame or bitmap cannot
 * be produced it falls back to a tiny solid placeholder so the media still imports.
 *
 * @param context application context whose `ContentResolver` resolves the picked URIs.
 */
class ContentUriImportSourceFactory(
    private val context: Context,
) : (Uri) -> ImportSource {

    private val contentResolver get() = context.contentResolver

    override fun invoke(uri: Uri): ImportSource {
        val mimeType = contentResolver.getType(uri).orEmpty()
        val mediaType = if (mimeType.startsWith("video")) MediaType.VIDEO else MediaType.IMAGE
        val (displayName, sizeBytes) = queryNameAndSize(uri)
        val durationMs = if (mediaType == MediaType.VIDEO) videoDurationMs(uri) else null

        return ImportSource(
            sourceName = displayName,
            mediaType = mediaType,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            openStream = { openInput(uri) },
            openThumbnail = { ByteArrayInputStream(buildThumbnail(uri, mediaType)) },
            deleteOriginal = {
                runCatching { contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            },
        )
    }

    private fun openInput(uri: Uri): InputStream =
        contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open import source: $uri")

    /**
     * Resolves the display name and size for [uri] from the openable columns, falling back
     * to the last path segment for the name and [UNKNOWN_SIZE] when the size is unavailable.
     */
    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    UNKNOWN_SIZE
                }
                return (name ?: fallbackName(uri)) to size
            }
        }
        return fallbackName(uri) to UNKNOWN_SIZE
    }

    private fun fallbackName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/') ?: "item-${System.currentTimeMillis()}"

    /** Reads the video duration in milliseconds, or `null` when it cannot be determined. */
    private fun videoDurationMs(uri: Uri): Long? = withRetriever(uri) { retriever ->
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }

    /**
     * Produces a small JPEG thumbnail for [uri]: a down-scaled frame for videos and a
     * down-scaled decode for images. Always returns bytes — on any failure it returns a
     * tiny placeholder so a thumbnail problem never fails the whole import (Req 4.3).
     */
    private fun buildThumbnail(uri: Uri, mediaType: MediaType): ByteArray {
        val bitmap = runCatching {
            when (mediaType) {
                MediaType.VIDEO -> videoThumbnail(uri)
                MediaType.IMAGE -> imageThumbnail(uri)
            }
        }.getOrNull() ?: placeholderBitmap()
        return bitmap.toJpegBytes().also { bitmap.recycle() }
    }

    private fun videoThumbnail(uri: Uri): Bitmap? = withRetriever(uri) { retriever ->
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        frame?.let { scaleDown(it).also { scaled -> if (scaled !== it) it.recycle() } }
    }

    private fun imageThumbnail(uri: Uri): Bitmap? {
        // First pass: bounds only, so a large image is never fully decoded into memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInput(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val sampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = openInput(uri).use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null
        return scaleDown(decoded).also { scaled -> if (scaled !== decoded) decoded.recycle() }
    }

    /** Scales [bitmap] so its longest edge is at most [THUMBNAIL_MAX_EDGE_PX]. */
    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge <= THUMBNAIL_MAX_EDGE_PX || longestEdge == 0) return bitmap
        val scale = THUMBNAIL_MAX_EDGE_PX.toFloat() / longestEdge
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /** Smallest power-of-two sample size that brings the longest edge under the cap. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest > THUMBNAIL_MAX_EDGE_PX * 2) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    private fun placeholderBitmap(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    private fun Bitmap.toJpegBytes(): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, out)
            out.toByteArray()
        }

    /**
     * Runs [block] with a [MediaMetadataRetriever] bound to [uri], always releasing it.
     * Returns `null` if the retriever cannot be set up.
     */
    private fun <T> withRetriever(uri: Uri, block: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            block(retriever)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        /** Sentinel size when the resolver does not report a size for the source. */
        const val UNKNOWN_SIZE = 0L
        /** Longest-edge cap (px) for generated thumbnails; keeps the encrypted blob small. */
        const val THUMBNAIL_MAX_EDGE_PX = 256
        /** JPEG quality for the generated thumbnail. */
        const val THUMBNAIL_JPEG_QUALITY = 80
    }
}
