package com.ladygo.driver.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/**
 * The little pictures on the maps, drawn in code so they are crisp at every density and need no image files:
 * a green dot for the pickup, a rose pin for the destination and a cute pink scooter with a helmeted rider for
 * the driver. Marker icons are cached per process, so a recomposition never redraws them.
 */
object MapIcons {
    private const val PICKUP_DP = 26
    private const val PIN_W_DP = 38
    private const val PIN_H_DP = 50
    private const val SCOOTER_W_DP = 76
    private const val SCOOTER_H_DP = 64

    private val PLUM = 0xFF672B6E.toInt()
    private val ROSE = 0xFFD44A80.toInt()
    private val PINK = 0xFFFF8FB4.toInt()
    private val GREEN = 0xFF1F8A5B.toInt()
    private val DARK = 0xFF3B1F2C.toInt()
    private val SKIN = 0xFFF4B9A0.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    private val cache = HashMap<String, BitmapDescriptor>()

    @Composable
    fun pickup(): BitmapDescriptor {
        val context = LocalContext.current
        return remember { cached("pickup", context) { drawPickup(context) } }
    }

    @Composable
    fun destination(): BitmapDescriptor {
        val context = LocalContext.current
        return remember { cached("destination", context) { drawPin(context) } }
    }

    /** [facingRight] flips the picture so the scooter always looks where it is heading. */
    @Composable
    fun scooter(facingRight: Boolean): BitmapDescriptor {
        val context = LocalContext.current
        return remember(
            facingRight,
        ) { cached(if (facingRight) "scooter_r" else "scooter_l", context) { drawScooter(context, facingRight) } }
    }

    private fun cached(
        key: String,
        context: Context,
        draw: () -> Bitmap,
    ): BitmapDescriptor =
        cache.getOrPut(key + context.resources.displayMetrics.density) {
            // The descriptor factory only works once the Maps SDK is up; a screen can ask for an icon before its map exists.
            MapsInitializer.initialize(context.applicationContext)
            BitmapDescriptorFactory.fromBitmap(draw())
        }

    private fun px(
        context: Context,
        dp: Int,
    ): Int = (dp * context.resources.displayMetrics.density).toInt()

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun drawPickup(context: Context): Bitmap {
        val size = px(context, PICKUP_DP)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val mid = size / 2f
        c.drawCircle(mid, mid, mid, paint(WHITE))
        c.drawCircle(mid, mid, mid * 0.78f, paint(GREEN))
        c.drawCircle(mid, mid, mid * 0.32f, paint(WHITE))
        return bitmap
    }

    /** A teardrop pin whose tip is the bottom-centre of the bitmap (anchor it there). */
    private fun drawPin(context: Context): Bitmap {
        val w = px(context, PIN_W_DP)
        val h = px(context, PIN_H_DP)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val r = w / 2f
        val body =
            Path().apply {
                addCircle(r, r, r * 0.94f, Path.Direction.CW)
                moveTo(r - r * 0.62f, r + r * 0.7f)
                lineTo(r, h.toFloat())
                lineTo(r + r * 0.62f, r + r * 0.7f)
                close()
            }
        c.drawPath(body, paint(WHITE))
        val inner =
            Path().apply {
                addCircle(r, r, r * 0.8f, Path.Direction.CW)
                moveTo(r - r * 0.5f, r + r * 0.62f)
                lineTo(r, h - r * 0.16f)
                lineTo(r + r * 0.5f, r + r * 0.62f)
                close()
            }
        c.drawPath(inner, paint(ROSE))
        c.drawCircle(r, r, r * 0.34f, paint(WHITE))
        return bitmap
    }

    /** A side-on pink scooter with a rider in a plum helmet. Drawn facing right; mirrored for left. */
    private fun drawScooter(
        context: Context,
        facingRight: Boolean,
    ): Bitmap {
        val w = px(context, SCOOTER_W_DP)
        val h = px(context, SCOOTER_H_DP)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        if (!facingRight) c.scale(-1f, 1f, w / 2f, h / 2f)
        val u = w / 76f
        // soft shadow under the wheels
        c.drawOval(RectF(8 * u, h - 9 * u, 68 * u, h - 2 * u), paint(0x33000000))
        // rider: legs, body, head, helmet
        c.drawRoundRect(RectF(26 * u, 28 * u, 40 * u, 46 * u), 5 * u, 5 * u, paint(PLUM))
        c.drawRoundRect(RectF(30 * u, 17 * u, 46 * u, 36 * u), 7 * u, 7 * u, paint(ROSE))
        c.drawCircle(42 * u, 11 * u, 8 * u, paint(SKIN))
        c.drawArc(RectF(33 * u, 1 * u, 51 * u, 19 * u), 180f, 180f, true, paint(PLUM))
        c.drawCircle(46 * u, 12 * u, 1.3f * u, paint(DARK))
        // arm to the handlebar
        c.drawRoundRect(RectF(42 * u, 21 * u, 56 * u, 26 * u), 2.5f * u, 2.5f * u, paint(SKIN))
        // scooter body, front shield, seat
        val shield =
            Path().apply {
                moveTo(52 * u, 26 * u)
                lineTo(60 * u, 26 * u)
                lineTo(57 * u, h - 14 * u)
                lineTo(48 * u, h - 14 * u)
                close()
            }
        c.drawPath(shield, paint(PINK))
        c.drawRoundRect(RectF(12 * u, h - 24 * u, 56 * u, h - 13 * u), 6 * u, 6 * u, paint(PINK))
        c.drawRoundRect(RectF(14 * u, h - 28 * u, 36 * u, h - 22 * u), 3 * u, 3 * u, paint(DARK))
        // handlebar and headlight
        c.drawRoundRect(RectF(54 * u, 20 * u, 62 * u, 24 * u), 2 * u, 2 * u, paint(DARK))
        c.drawCircle(61 * u, 28 * u, 2.6f * u, paint(0xFFFFE08A.toInt()))
        // wheels
        for (cx in listOf(18f, 55f)) {
            c.drawCircle(cx * u, h - 11 * u, 8 * u, paint(DARK))
            c.drawCircle(cx * u, h - 11 * u, 4 * u, paint(0xFFFFE3EC.toInt()))
            c.drawCircle(cx * u, h - 11 * u, 1.4f * u, paint(ROSE))
        }
        return bitmap
    }
}
