package app.familynote.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure ARGB color helpers (no android.graphics dependency, so they run in JVM unit tests).
 */
object ColorMath {

    const val PAPER: Int = 0xFFF6EFE4.toInt()
    const val WARM_DEFAULT: Int = 0xFFF2A65A.toInt()

    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Returns [h in 0..360), s in 0..1, l in 0..1]. */
    fun toHsl(c: Int): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        var h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        if (h < 0f) h += 360f
        return floatArrayOf(h, s.coerceIn(0f, 1f), l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return argb(
            255,
            ((r1 + m) * 255f).roundToInt(),
            ((g1 + m) * 255f).roundToInt(),
            ((b1 + m) * 255f).roundToInt(),
        )
    }

    private fun distanceSq(a: Int, b: Int): Int {
        val dr = red(a) - red(b)
        val dg = green(a) - green(b)
        val db = blue(a) - blue(b)
        return dr * dr + dg * dg + db * db
    }

    /**
     * Picks the color that best represents a drawing.
     *
     * Paper-colored, transparent and dull pixels are ignored; the remaining pixels are
     * grouped into 12 hue buckets weighted by chroma squared, and the average color of the
     * heaviest bucket is returned. Chroma (not HSL saturation) is used so that pale crayon
     * skin tones and beige walls, which have high HSL saturation but little actual color,
     * cannot outvote the picture's real main color. Returns null when there is no colorful ink.
     */
    fun pickAccent(pixels: IntArray, paper: Int = PAPER): Int? {
        val buckets = 12
        val weight = FloatArray(buckets)
        val sumR = FloatArray(buckets)
        val sumG = FloatArray(buckets)
        val sumB = FloatArray(buckets)
        for (p in pixels) {
            if (alpha(p) < 128) continue
            if (distanceSq(p, paper) < 40 * 40) continue
            val chroma = (max(red(p), max(green(p), blue(p))) - min(red(p), min(green(p), blue(p)))) / 255f
            if (chroma < 0.3f) continue
            val hsl = toHsl(p)
            if (hsl[2] < 0.12f) continue
            val i = ((hsl[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            val w = chroma * chroma
            weight[i] += w
            sumR[i] += red(p) * w
            sumG[i] += green(p) * w
            sumB[i] += blue(p) * w
        }
        var best = -1
        for (i in 0 until buckets) if (weight[i] > 0f && (best < 0 || weight[i] > weight[best])) best = i
        if (best < 0) return null
        val w = weight[best]
        return argb(
            255,
            (sumR[best] / w).roundToInt(),
            (sumG[best] / w).roundToInt(),
            (sumB[best] / w).roundToInt(),
        )
    }

    /**
     * Turns any color into one that looks good as cabin ambient light:
     * saturated enough to read as a color, never dim, never glaring white.
     */
    fun toAmbient(c: Int): Int {
        val hsl = toHsl(c)
        if (hsl[1] < 0.15f) return WARM_DEFAULT
        val s = hsl[1].coerceIn(0.55f, 0.9f)
        val l = hsl[2].coerceIn(0.5f, 0.62f)
        return fromHsl(hsl[0], s, l)
    }

    fun lerp(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(a: Int, b: Int) = (a + (b - a) * k).roundToInt()
        return argb(
            ch(alpha(from), alpha(to)),
            ch(red(from), red(to)),
            ch(green(from), green(to)),
            ch(blue(from), blue(to)),
        )
    }

    /** Intermediate colors for a gradual change, excluding [from] and ending exactly at [to]. */
    fun steps(from: Int, to: Int, count: Int): List<Int> {
        require(count >= 1)
        return (1..count).map { i -> if (i == count) to else lerp(from, to, i / count.toFloat()) }
    }
}
