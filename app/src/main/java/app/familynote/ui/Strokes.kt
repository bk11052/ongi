package app.familynote.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.familynote.util.ColorMath

/**
 * One finger stroke. Points are stored normalized to 0..1 of the canvas so the drawing
 * renders identically on screen and in the saved PNG, whatever their sizes.
 */
class Stroke(
    val color: Int,
    /** Width as a fraction of the canvas's shorter side. */
    val width: Float,
    val eraser: Boolean,
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

    /** Draws paper and strokes onto [canvas] of size [w]x[h]. Must be called from one thread. */
    fun draw(canvas: Canvas, w: Int, h: Int, strokes: List<Stroke>) {
        canvas.drawColor(ColorMath.PAPER)
        drawStrokes(canvas, w, h, strokes)
    }

    /** Draws only the strokes (no paper), e.g. the one in progress on top of a cached image. */
    fun drawStrokes(canvas: Canvas, w: Int, h: Int, strokes: List<Stroke>) {
        val unit = minOf(w, h).toFloat()
        for (s in strokes) {
            if (s.size == 0) continue
            paint.color = if (s.eraser) ColorMath.PAPER else s.color
            paint.strokeWidth = s.width * unit
            if (s.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(s.xs[0] * w, s.ys[0] * h, paint.strokeWidth / 2f, paint)
                paint.style = Paint.Style.STROKE
                continue
            }
            path.reset()
            path.moveTo(s.xs[0] * w, s.ys[0] * h)
            // Quadratic smoothing through midpoints for a soft crayon-like line.
            for (i in 1 until s.size) {
                val px = s.xs[i - 1] * w
                val py = s.ys[i - 1] * h
                val cx = s.xs[i] * w
                val cy = s.ys[i] * h
                path.quadTo(px, py, (px + cx) / 2f, (py + cy) / 2f)
            }
            path.lineTo(s.xs.last() * w, s.ys.last() * h)
            canvas.drawPath(path, paint)
        }
    }

    fun toBitmap(strokes: List<Stroke>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), w, h, strokes)
        return bmp
    }
}
