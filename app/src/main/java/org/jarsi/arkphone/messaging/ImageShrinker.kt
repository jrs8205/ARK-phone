package org.jarsi.arkphone.messaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** Re-encodes an image as a JPEG under a byte budget; scales down in steps. */
class ImageShrinker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val MAX_DIMENSION = 2048
        private const val QUALITY_START = 90
        private const val QUALITY_FLOOR = 40
        private const val QUALITY_STEP = 10
    }

    suspend fun shrink(source: Uri, maxBytes: Int): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        var bitmap = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@runCatching null
        // Re-encoding drops the EXIF block, so a rotation stored as
        // metadata has to be baked into the pixels here.
        val rotation = exifRotationDegrees(source)
        if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        while (true) {
            var quality = QUALITY_START
            while (quality >= QUALITY_FLOOR) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= maxBytes) return@runCatching out.toByteArray()
                quality -= QUALITY_STEP
            }
            if (bitmap.width <= 1 || bitmap.height <= 1) return@runCatching null
            bitmap = bitmap.scale(
                (bitmap.width / 2).coerceAtLeast(1),
                (bitmap.height / 2).coerceAtLeast(1),
            )
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }.getOrNull()

    private fun exifRotationDegrees(source: Uri): Float = runCatching {
        context.contentResolver.openInputStream(source)?.use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
    }.getOrNull() ?: 0f

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > MAX_DIMENSION || height / sampleSize > MAX_DIMENSION) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
