package app.familynote.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.familynote.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.sin

/** A short wavy line drawn with the pen itself, on paper: the button shows what the pen does. */
@Composable
fun PenSwatch(pen: Pen, color: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bmp = remember(pen, color) {
        val w = 220; val h = 96
        val s = Stroke(color, 0.16f, eraser = false, pen = pen)
        for (i in 0..40) {
            val t = i / 40f
            s.add(0.12f + 0.76f * t, 0.5f + 0.22f * sin(t * 2 * PI).toFloat())
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            StrokeRenderer.draw(android.graphics.Canvas(it), w, h, listOf(s))
        }
    }
    Box(
        modifier
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 4.dp else 0.dp, Palette.Amber, RoundedCornerShape(16.dp))
            .background(Palette.Paper)
            .clickable(onClick = onClick)
            .semantics { contentDescription = pen.label },
        contentAlignment = Alignment.Center,
    ) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.padding(6.dp))
    }
}

/** Eraser pictogram: a tilted two-tone eraser block. */
@Composable
fun EraserIcon(modifier: Modifier = Modifier.size(40.dp), tint: Color = Palette.Moon) {
    Canvas(modifier) {
        rotate(-35f) {
            val w = size.width * 0.9f
            val h = size.height * 0.42f
            val left = (size.width - w) / 2
            val top = (size.height - h) / 2
            val r = CornerRadius(h * 0.22f)
            drawRoundRect(Color(0xFFF48FB1), Offset(left, top), Size(w * 0.55f, h), r)
            drawRoundRect(tint, Offset(left, top), Size(w, h), r, style = DrawStroke(width = size.minDimension * 0.07f))
            drawLine(tint, Offset(left + w * 0.55f, top), Offset(left + w * 0.55f, top + h), strokeWidth = size.minDimension * 0.07f)
        }
    }
}
