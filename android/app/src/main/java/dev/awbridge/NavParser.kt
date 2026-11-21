package dev.awbridge

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject

/**
 * Turns the Google Maps turn-by-turn notification into distance, instruction,
 * ETA and a small 1-bit maneuver icon.
 *
 * Maps normally fills the standard extras: title = distance to the next turn,
 * text = instruction or street, subText = ETA summary, largeIcon = maneuver
 * arrow. If the extras are empty we inflate the notification layout and read
 * its text views instead.
 */
object NavParser {

    const val ICON_SIZE = 64

    class Info(val distance: String, val instruction: String, val eta: String, val icon: ByteArray?) {
        fun json(): ByteArray = JSONObject()
            .put("a", true)
            .put("d", distance)
            .put("i", instruction)
            .put("e", eta)
            .toString()
            .toByteArray(Charsets.UTF_8)

        fun key(): String = "$distance | $instruction | $eta"
    }

    // "200 m", "1,2 km", "0.3 mi", "500 ft"
    private val distancePattern = Regex("^\\d+([.,]\\d+)?\\s*\\p{L}{1,5}\\.?$")

    fun parse(ctx: Context, n: Notification): Info? {
        val extras = n.extras
        var title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        var sub = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        var drawable: Drawable? = try {
            n.getLargeIcon()?.loadDrawable(ctx)
        } catch (_: Throwable) {
            null
        }

        if (title.isEmpty() && text.isEmpty()) {
            val (texts, image) = readRemoteViews(ctx, n)
            title = texts.getOrNull(0).orEmpty()
            text = texts.getOrNull(1).orEmpty()
            if (sub.isEmpty()) sub = texts.drop(2).joinToString(" · ")
            if (drawable == null) drawable = image
        }
        if (title.isEmpty() && text.isEmpty()) return null

        var distance = title
        var instruction = text
        if (!distancePattern.matches(title) && distancePattern.matches(text)) {
            distance = text
            instruction = title
        }
        val icon = try {
            drawable?.let { iconBits(it) }
        } catch (_: Throwable) {
            null
        }
        return Info(distance.take(40), instruction.take(160), sub.take(80), icon)
    }

    private fun readRemoteViews(ctx: Context, n: Notification): Pair<List<String>, Drawable?> {
        val texts = ArrayList<String>()
        var best: Drawable? = null
        var bestArea = 0

        fun walk(v: View) {
            when (v) {
                is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
                is TextView -> {
                    val s = v.text?.toString()?.trim().orEmpty()
                    if (s.isNotEmpty()) texts.add(s)
                }
                is ImageView -> {
                    val d = v.drawable
                    if (d != null) {
                        val area = d.intrinsicWidth * d.intrinsicHeight
                        if (area > bestArea) {
                            bestArea = area
                            best = d
                        }
                    }
                }
            }
        }

        try {
            @Suppress("DEPRECATION")
            val views = n.bigContentView ?: n.contentView
                ?: Notification.Builder.recoverBuilder(ctx, n).createContentView()
            if (views != null) walk(views.apply(ctx.applicationContext, null))
        } catch (_: Throwable) {
        }
        return Pair(texts, best)
    }

    fun iconBits(drawable: Drawable): ByteArray? {
        val bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
        drawable.draw(Canvas(bmp))
        return iconBits(bmp)
    }

    /**
     * Packs a 64x64 bitmap to 1 bit per pixel. Picks the arrow as "ink" whether
     * the source is a glyph on transparent, a glyph on a solid background, or a
     * glyph inside a filled badge.
     */
    fun iconBits(bmp: Bitmap): ByteArray? {
        val s = ICON_SIZE
        val px = IntArray(s * s)
        bmp.getPixels(px, 0, s, 0, 0, s, s)
        bmp.recycle()

        var transparent = 0
        var minLum = 255
        var maxLum = 0
        for (p in px) {
            if ((p ushr 24) < 128) {
                transparent++
            } else {
                val l = lum(p)
                if (l < minLum) minLum = l
                if (l > maxLum) maxLum = l
            }
        }
        val hasAlpha = transparent > px.size / 10
        val twoTone = maxLum - minLum > 100
        val mid = (minLum + maxLum) / 2

        val on = BooleanArray(px.size)
        if (hasAlpha && !twoTone) {
            for (i in px.indices) on[i] = (px[i] ushr 24) >= 128
        } else {
            // Two tones among the opaque pixels: the rarer tone is the arrow.
            var bright = 0
            var opaque = 0
            for (p in px) {
                if ((p ushr 24) >= 128) {
                    opaque++
                    if (lum(p) > mid) bright++
                }
            }
            val inkIsBright = bright * 2 < opaque
            for (i in px.indices) {
                val p = px[i]
                on[i] = (p ushr 24) >= 128 && ((lum(p) > mid) == inkIsBright)
            }
        }

        val out = ByteArray(2 + px.size / 8)
        out[0] = s.toByte()
        out[1] = s.toByte()
        var ink = 0
        for (i in px.indices) {
            if (on[i]) {
                val at = 2 + (i shr 3)
                out[at] = (out[at].toInt() or (0x80 ushr (i and 7))).toByte()
                ink++
            }
        }
        if (ink == 0 || ink == px.size) return null
        return out
    }

    private fun lum(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
