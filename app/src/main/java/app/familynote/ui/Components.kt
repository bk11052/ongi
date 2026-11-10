package app.familynote.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.familynote.data.Note
import app.familynote.data.NoteKind
import app.familynote.ui.theme.Palette
import app.familynote.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Family members offered as sender/recipient. Kept simple on purpose. */
val FamilyMembers = listOf("아빠", "엄마", "딸", "아들", "우리 가족")

private val dateFormat = SimpleDateFormat("M월 d일", Locale.KOREA)
fun Note.dateLabel(): String = dateFormat.format(Date(createdAt))

/**
 * Soft light along the screen edges in the current ambient color, so the cabin light
 * change is also visible on screen (and in recordings). Drawn behind the content so it
 * never tints the notes themselves.
 */
fun Modifier.ambientGlow(color: Color, strength: Float = 0.42f): Modifier = drawBehind {
    if (color.alpha == 0f) return@drawBehind
    // Lift the color toward white a little: over the dark background a low-alpha warm color
    // otherwise reads as muddy brown rather than light.
    val edge = size.minDimension * 0.22f
    val c = androidx.compose.ui.graphics.lerp(color, Color.White, 0.18f).copy(alpha = strength)
    val clear = Color.Transparent
    drawRect(Brush.verticalGradient(listOf(c, clear), startY = 0f, endY = edge))
    drawRect(Brush.verticalGradient(listOf(clear, c), startY = size.height - edge, endY = size.height))
    drawRect(Brush.horizontalGradient(listOf(c, clear), startX = 0f, endX = edge))
    drawRect(Brush.horizontalGradient(listOf(clear, c), startX = size.width - edge, endX = size.width))
}

@Composable
fun animatedGlowColor(argb: Int?): Color {
    val target = argb?.let { Color(it) } ?: Color.Transparent
    val c by animateColorAsState(target, animationSpec = tween(1000), label = "glow")
    return c
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label.isNotEmpty()) Text(label, style = Type.Small)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (o in options) {
                val on = o == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .background(if (on) Palette.Amber else Palette.NightRaised)
                        .border(2.dp, if (on) Palette.Amber else Palette.Dim.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                        .clickable { onSelect(o) }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    Text(o, style = Type.Label.copy(color = if (on) Palette.Night else Palette.Moon))
                }
            }
        }
    }
}

@Composable
fun SoftButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    /** Smaller padding, for tool pickers. */
    compact: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Palette.NightRaised.copy(alpha = 0.5f)
        primary -> Palette.Amber
        else -> Palette.NightRaised
    }
    val fg = if (primary && enabled) Palette.Night else Palette.Moon
    Row(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 20.dp else 28.dp, vertical = if (compact) 14.dp else 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(28.dp))
        Text(text, style = Type.Label.copy(color = fg), maxLines = 1, softWrap = false)
    }
}

/** Width / height of a stored drawing, read from the file header only (cheap, no decode). */
@Composable
fun rememberImageRatio(path: String?): Float? = remember(path) { path?.let(ImageRatios::get) }

/** Process-wide cache, so each file header is read at most once (saved PNGs never change). */
private object ImageRatios {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Float>()
    fun get(path: String): Float? = cache[path] ?: run {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth > 0 && o.outHeight > 0) (o.outWidth.toFloat() / o.outHeight).also { cache[path] = it } else null
    }
}

@Composable
fun rememberNoteBitmap(path: String?, maxSide: Int, load: suspend (String, Int) -> Bitmap?): Bitmap? {
    val bmp by produceState<Bitmap?>(null, path, maxSide) {
        value = path?.let { load(it, maxSide) }
    }
    return bmp
}

/** A note as a piece of paper: the drawing itself, or handwritten text on paper. */
@Composable
fun PaperNote(
    note: Note,
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    textSizeScale: Float = 1f,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.Paper),
        contentAlignment = Alignment.Center,
    ) {
        when {
            note.kind == NoteKind.DRAWING && bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "${note.fromMember}의 그림",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            note.kind == NoteKind.TEXT -> Text(
                note.text.orEmpty(),
                style = Type.NoteBody.copy(
                    fontSize = Type.NoteBody.fontSize * textSizeScale,
                    lineHeight = Type.NoteBody.lineHeight * textSizeScale,
                    textAlign = TextAlign.Center,
                    // Break between words, never inside one ("놀아/요").
                    lineBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase),
                ),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

/** A large, calm confirmation dialog with well-separated buttons (car-screen distance). */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Optional picture of what the decision is about (e.g. the note to delete). */
    preview: (@Composable () -> Unit)? = null,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.NightRaised)
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(title, style = Type.Label.copy(fontSize = Type.Label.fontSize * 1.3f))
            preview?.invoke()
            Text(body, style = Type.Label.copy(color = Palette.Dim))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                // The safe choice is the prominent one.
                SoftButton(dismissText, primary = true, onClick = onDismiss)
                SoftButton(confirmText, onClick = onConfirm)
            }
        }
    }
}

@Composable
fun NewDot(size: Dp = 14.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Palette.Amber)
            .border(2.dp, Palette.Night, CircleShape),
    )
}
