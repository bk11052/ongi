package app.familynote.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import app.familynote.util.ColorMath
import kotlin.random.Random

/** The drawing tool. Each one leaves a different mark on the paper. */
enum class Pen(val label: String) {
    /** Thick and waxy: the paper's tooth shows through, like a real crayon. */
    CRAYON("크레파스"),
    /** Thin and grainy, a little lighter. */
    PENCIL("색연필"),
    /** Smooth, even, fully opaque line. */
    MARKER("사인펜"),
}

/**
 * One finger stroke. Points are stored normalized to 0..1 of the canvas so the drawing
 * renders identically on screen and in the saved PNG, whatever their sizes.
 */
class Stroke(
    val color: Int,
    /** Width as a fraction of the canvas's shorter side. */
    val width: Float,
    val eraser: Boolean,
    val pen: Pen = Pen.MARKER,
) {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()

    fun add(x: Float, y: Float) {
        xs += x.coerceIn(0f, 1f)
        ys += y.coerceIn(0f, 1f)
    }

    val size get() = xs.size
}

object StrokeRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /**
     * Paper tooth: white with varying alpha. Tinted per stroke through a color filter, it
     * lets the paper show through in small specks and short streaks, as wax skips over the
     * raised grain of real paper. Fixed seed, so a drawing always renders the same way.
     */
    private fun grain(size: Int, gapChance: Float, seed: Int): Bitmap {
        val rnd = Random(seed)
        val px = IntArray(size * size)
        for (i in px.indices) {
            val r = rnd.nextFloat()
            val a = when {
                r < gapChance -> rnd.nextInt(0, 50)
                r < gapChance + 0.25f -> rnd.nextInt(120, 200)
                else -> rnd.nextInt(200, 256)
            }
            px[i] = Color.argb(a, 255, 255, 255)
        }
        // Smear horizontally a little so gaps form short streaks instead of pure noise.
        for (y in 0 until size) for (x in 1 until size) {
            val i = y * size + x
            if (rnd.nextFloat() < 0.5f) px[i] = px[i - 1]
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun tiled(bmp: Bitmap, scale: Float) = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
        setLocalMatrix(Matrix().apply { setScale(scale, scale) })
    }

    // Crayon grain is coarse (scaled up) so the paper tooth reads at car-screen distance.
    private val crayonShader by lazy { tiled(grain(96, 0.27f, 7), 2.4f) }
    private val pencilShader by lazy { tiled(grain(64, 0.34f, 11), 1.2f) }

    /** Faint paper fibre on the page itself, so the canvas looks like drawing paper. */
    private val paperPaint by lazy {
        Paint().apply {
            shader = tiled(grain(128, 0.5f, 3), 2f)
            colorFilter = PorterDuffColorFilter(0xFF8A7458.toInt(), PorterDuff.Mode.SRC_IN)
            alpha = 14
        }
    }

    /** Draws paper and strokes onto [canvas] of size [w]x[h]. Must be called from one thread. */
    fun draw(canvas: Canvas, w: Int, h: Int, strokes: List<Stroke>) {
        canvas.drawColor(ColorMath.PAPER)
        canvas.drawPaint(paperPaint)
        drawStrokes(canvas, w, h, strokes)
    }

    /** Draws only the strokes (no paper), e.g. the one in progress on top of a cached image. */
    fun drawStrokes(canvas: Canvas, w: Int, h: Int, strokes: List<Stroke>) {
        val unit = minOf(w, h).toFloat()
        for (s in strokes) {
            if (s.size == 0) continue
            val pen = if (s.eraser) Pen.MARKER else s.pen
            paint.color = if (s.eraser) ColorMath.PAPER else s.color
            paint.strokeWidth = s.width * unit * when (pen) {
                Pen.CRAYON -> 1.25f
                Pen.PENCIL -> 0.45f
                Pen.MARKER -> 1f
            }
            when (pen) {
                Pen.MARKER -> { paint.shader = null; paint.colorFilter = null; paint.alpha = 255 }
                Pen.CRAYON -> { paint.shader = crayonShader; paint.colorFilter = PorterDuffColorFilter(s.color, PorterDuff.Mode.SRC_IN) }
                Pen.PENCIL -> { paint.shader = pencilShader; paint.colorFilter = PorterDuffColorFilter(s.color, PorterDuff.Mode.SRC_IN); paint.alpha = 225 }
            }
            if (s.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(s.xs[0] * w, s.ys[0] * h, paint.strokeWidth / 2f, paint)
                paint.style = Paint.Style.STROKE
            } else {
                path.reset()
                path.moveTo(s.xs[0] * w, s.ys[0] * h)
                // Quadratic smoothing through midpoints for a soft line.
                for (i in 1 until s.size) {
                    val px = s.xs[i - 1] * w
                    val py = s.ys[i - 1] * h
                    val cx = s.xs[i] * w
                    val cy = s.ys[i] * h
                    path.quadTo(px, py, (px + cx) / 2f, (py + cy) / 2f)
                }
                path.lineTo(s.xs.last() * w, s.ys.last() * h)
                canvas.drawPath(path, paint)
                if (pen == Pen.CRAYON) {
                    // Thinner passes, nudged sideways, give the wax its uneven, scribbled edge.
                    val full = paint.strokeWidth
                    for ((k, dx, dy) in listOf(Triple(0.6f, 0.22f, -0.15f), Triple(0.45f, -0.2f, 0.18f))) {
                        paint.strokeWidth = full * k
                        canvas.save()
                        canvas.translate(full * dx, full * dy)
                        canvas.drawPath(path, paint)
                        canvas.restore()
                    }
                    paint.strokeWidth = full
                }
            }
            paint.shader = null
            paint.colorFilter = null
            paint.alpha = 255
        }
    }

    fun toBitmap(strokes: List<Stroke>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), w, h, strokes)
        return bmp
    }
}

/** An unfinished drawing kept when the draw screen closes without saving (e.g. the car leaves P). */
data class DrawDraft(val strokes: List<Stroke>, val from: String, val to: String)
