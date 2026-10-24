package com.ladygo.customer.booking

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * Turns the camera's raw JPEG into the small, upright JPEG that is uploaded. Done in memory (the photo is
 * never written to a file): the camera sensor's JPEG is un-rotated and often several MB, which would
 * both display sideways to the driver and exceed the server's upload limit.
 */
object SelfieEncoder {
    private const val MAX_SIDE_PX = 1280
    private const val JPEG_QUALITY = 85

    /** `null` if [jpeg] cannot be decoded. [rotationDegrees] is the camera's reported rotation to upright. */
    fun encode(
        jpeg: ByteArray,
        rotationDegrees: Int,
    ): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight)) }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
        val upright =
            if (rotationDegrees % FULL_TURN == 0) {
                decoded
            } else {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
        return ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    internal fun sampleSizeFor(longestSide: Int): Int {
        var sample = 1
        while (longestSide / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        return sample
    }

    private const val FULL_TURN = 360
}
