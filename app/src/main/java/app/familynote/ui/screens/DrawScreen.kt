package app.familynote.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.familynote.ui.ChipRow
import app.familynote.ui.FamilyMembers
import app.familynote.ui.SoftButton
import app.familynote.ui.Stroke
import app.familynote.ui.StrokeRenderer
import app.familynote.ui.theme.Palette
import app.familynote.ui.theme.Type

/** Crayon box: bright, friendly colors plus a soft "pencil" ink. */
private val CrayonColors = listOf(
    0xFFE5533D, 0xFFF28C28, 0xFFF5C518, 0xFF4CAF50,
    0xFF42A5F5, 0xFF8E6CC8, 0xFFF48FB1, 0xFF3A3530,
).map { it.toInt() }

/** Brush widths as a fraction of the canvas's shorter side. */
private val BrushWidths = listOf(0.008f, 0.018f, 0.036f)

/** Longest side of the saved PNG. */
private const val SAVE_LONG_SIDE = 1600

@Composable
fun DrawScreen(
    onCancel: () -> Unit,
    /** Saves; the last argument reports success so the button can be re-enabled on failure. */
    onSave: (Bitmap, from: String, to: String, done: (Boolean) -> Unit) -> Unit,
) {
    val strokes = remember { mutableStateListOf<Stroke>() }
    var current by remember { mutableStateOf<Stroke?>(null) }
    // Bumped on every new point so the canvas redraws while a stroke grows.
    var tick by remember { mutableIntStateOf(0) }
    var color by remember { mutableIntStateOf(CrayonColors[0]) }
    var width by remember { mutableStateOf(BrushWidths[1]) }
    var eraser by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf("딸") }
    var to by remember { mutableStateOf("아빠") }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }

    // Finished strokes are rendered once into this bitmap; each frame then draws the bitmap
    // plus only the stroke in progress, so drawing stays smooth however busy the picture gets.
    var committed by remember { mutableStateOf<Bitmap?>(null) }
    fun recommit() {
        val w = canvasSize.width
        val h = canvasSize.height
        if (w <= 0 || h <= 0) return
        val finished = strokes.filter { it !== current }
        val bmp = committed?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { committed = it }
        StrokeRenderer.draw(android.graphics.Canvas(bmp), w, h, finished)
        tick++
    }

    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .onSizeChanged {
                    canvasSize = it
                    committed = null
                    recommit()
                }
                .pointerInput(Unit) {
                    // Raw handling: the stroke starts exactly at touch-down, so quick dots and
                    // short marks (common in children's drawings) are never lost to touch slop.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (size.width == 0 || size.height == 0) return@awaitEachGesture
                        val s = Stroke(color, width, eraser)
                        s.add(down.position.x / size.width, down.position.y / size.height)
                        down.consume()
                        current = s
                        strokes += s
                        tick++
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            s.add(change.position.x / size.width, change.position.y / size.height)
                            change.consume()
                            tick++
                        }
                        current = null
                        recommit()
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION") tick // read so new points trigger a redraw
                drawIntoCanvas { c ->
                    val w = size.width.toInt()
                    val h = size.height.toInt()
                    val cached = committed
                    if (cached != null && cached.width == w && cached.height == h) {
                        c.nativeCanvas.drawBitmap(cached, 0f, 0f, null)
                        current?.let { StrokeRenderer.drawStrokes(c.nativeCanvas, w, h, listOf(it)) }
                    } else {
                        StrokeRenderer.draw(c.nativeCanvas, w, h, strokes)
                    }
                }
            }
            if (strokes.isEmpty()) {
                Text(
                    "여기에 그려 주세요",
                    style = Type.Caption.copy(color = Palette.PaperShade),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        Column(
            Modifier.width(360.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("크레파스", style = Type.Small)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in CrayonColors.chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (c in row) {
                            val on = !eraser && c == color
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(if (on) 4.dp else 0.dp, Palette.Moon, CircleShape)
                                    .clickable { color = c; eraser = false },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                BrushWidths.forEachIndexed { i, w ->
                    val on = w == width
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (on) Palette.NightRaised else Color.Transparent)
                            .clickable { width = w },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size((10 + i * 10).dp).clip(CircleShape).background(Palette.Moon))
                    }
                }
                SoftButton(if (eraser) "지우개 ✓" else "지우개", onClick = { eraser = !eraser })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SoftButton("되돌리기", enabled = strokes.isNotEmpty(), onClick = {
                    strokes.removeLastOrNull()
                    recommit()
                })
                SoftButton("모두 지우기", enabled = strokes.isNotEmpty(), onClick = {
                    strokes.clear()
                    recommit()
                })
            }
            ChipRow("누가", FamilyMembers, from) { from = it }
            ChipRow("누구에게", FamilyMembers, to) { to = it }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SoftButton("취소", Modifier.weight(1f), onClick = onCancel)
                SoftButton(
                    "남기기",
                    Modifier.weight(1f),
                    primary = true,
                    enabled = strokes.isNotEmpty() && !saving && canvasSize.width > 0,
                ) {
                    saving = true
                    val scale = SAVE_LONG_SIDE.toFloat() / maxOf(canvasSize.width, canvasSize.height)
                    val w = (canvasSize.width * scale).toInt().coerceAtLeast(1)
                    val h = (canvasSize.height * scale).toInt().coerceAtLeast(1)
                    onSave(StrokeRenderer.toBitmap(strokes.toList(), w, h), from, to) { ok ->
                        if (!ok) saving = false
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
