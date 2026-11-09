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
import androidx.compose.material3.Text
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
fun Modifier.ambientGlow(color: Color, strength: Float = 0.32f): Modifier = drawBehind {
    if (color.alpha == 0f) return@drawBehind
    val edge = size.minDimension * 0.22f
    val c = color.copy(alpha = strength)
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
        Text(label, style = Type.Small)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (o in options) {
                val on = o == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (on) Palette.Amber else Palette.NightRaised)
                        .clickable { onSelect(o) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
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
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Palette.NightRaised.copy(alpha = 0.5f)
        primary -> Palette.Amber
        else -> Palette.NightRaised
    }
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Type.Label.copy(color = if (primary && enabled) Palette.Night else Palette.Moon),
        )
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
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.NightRaised)
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(title, style = Type.Label.copy(fontSize = Type.Label.fontSize * 1.2f))
            Text(body, style = Type.Small)
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
