package com.ladygo.customer.booking

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class SelfieEncoderTest {
    private fun jpeg(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }

    private fun sizeOf(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun aSmallPhotoKeepsItsSizeWhenAlreadyUpright() {
        val out = SelfieEncoder.encode(jpeg(640, 480), 0)

        assertNotNull(out)
        assertEquals(640 to 480, sizeOf(out!!))
    }

    @Test
    fun aQuarterTurnSwapsWidthAndHeightSoThePhotoIsUpright() {
        val out = SelfieEncoder.encode(jpeg(640, 480), 90)

        assertEquals(480 to 640, sizeOf(out!!))
    }

    @Test
    fun aHugePhotoIsDownsampledToTheUploadSize() {
        val out = SelfieEncoder.encode(jpeg(4000, 3000), 0)!!

        val (w, h) = sizeOf(out)
        assertTrue("longest side was ${maxOf(w, h)}", maxOf(w, h) <= 2000)
    }

    @Test
    fun sampleSizeHalvesUntilTheLongestSideIsNearTheTarget() {
        assertEquals(1, SelfieEncoder.sampleSizeFor(1280))
        assertEquals(1, SelfieEncoder.sampleSizeFor(2000))
        assertEquals(2, SelfieEncoder.sampleSizeFor(2560))
        assertEquals(4, SelfieEncoder.sampleSizeFor(5200))
    }
}
