package app.familynote.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.familynote.data.Note
import app.familynote.data.NoteKind
import app.familynote.ui.PaperNote
import app.familynote.ui.SoftButton
import app.familynote.ui.dateLabel
import app.familynote.ui.rememberNoteBitmap
import app.familynote.ui.theme.Type
import kotlinx.coroutines.delay

/** How long each note stays before drifting to the next. */
private const val HOLD_MS = 6000L
private const val CROSSFADE_MS = 1200
private const val ENTRY_FADE_MS = 1600

@Composable
fun GalleryScreen(
    notes: List<Note>,
    startId: String?,
    resting: Boolean,
    loadBitmap: suspend (String, Int) -> Bitmap?,
    onNoteShown: (Note) -> Unit,
    onToggleRest: () -> Unit,
    onRead: (Note) -> Unit,
    onReply: (Note) -> Unit,
    onClose: () -> Unit,
) {
    // Freeze the order when the gallery opens; "seen" updates must not reshuffle the show.
    // New notes come first (oldest first, so they read in order), then older ones to revisit.
    val newIds = remember { notes.filter { !it.seen }.map { it.id }.toSet() }
    val order = remember {
        notes.filter { it.id in newIds }.sortedBy { it.createdAt }.map { it.id } +
            notes.filter { it.id !in newIds }.map { it.id }
    }
    val byId = notes.associateBy { it.id }
    val shown = order.mapNotNull { byId[it] }
    if (shown.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    var index by remember { mutableIntStateOf(order.indexOf(startId).coerceAtLeast(0)) }
    var paused by remember { mutableStateOf(false) }
    val safeIndex = index % shown.size
    val note = shown[safeIndex]
    // pointerInput below is keyed on Unit, so read the latest note through this.
    val currentNote by rememberUpdatedState(note)

    val entry = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entry.animateTo(1f, tween(ENTRY_FADE_MS)) }

    LaunchedEffect(note.id) { onNoteShown(note) }
    LaunchedEffect(safeIndex, paused, shown.size) {
        if (paused || shown.size < 2) return@LaunchedEffect
        delay(HOLD_MS)
        index = (safeIndex + 1) % shown.size
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(entry.value)
            .pointerInput(Unit) {
                detectTapGestures {
                    paused = !paused
                    // Pausing on a note reads it aloud; tapping again resumes the show.
                    if (paused) onRead(currentNote)
                }
            },
    ) {
        // Keyed on the id: a note being marked "seen" must not trigger another cross-fade.
        Crossfade(targetState = note.id, animationSpec = tween(CROSSFADE_MS), label = "note") { id ->
            byId[id]?.let { SlowZoomPaper(it, loadBitmap) }
        }

        // Caption: always faintly visible, brighter when paused.
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (note.id in newIds) "새 쪽지" else "다시 보는 쪽지",
                style = Type.Small,
                modifier = Modifier.alpha(if (paused) 1f else 0.7f),
            )
            Text(
                "From. ${note.fromMember}  ·  ${note.dateLabel()}",
                style = Type.Caption,
                modifier = Modifier.alpha(if (paused) 1f else 0.7f),
            )
        }

        AnimatedVisibility(
            visible = paused,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.TopEnd).padding(28.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SoftButton("다시 듣기", onClick = { onRead(note) })
                SoftButton("답장하기", onClick = { onReply(note) })
                SoftButton(if (resting) "쉼표 끄기" else "쉼표 켜기", primary = !resting, onClick = onToggleRest)
                SoftButton("닫기", onClick = onClose)
            }
        }
        AnimatedVisibility(
            visible = paused,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.TopStart).padding(28.dp),
        ) {
            Text("${safeIndex + 1} / ${shown.size}", style = Type.Small)
        }
    }
}

@Composable
private fun SlowZoomPaper(note: Note, loadBitmap: suspend (String, Int) -> Bitmap?) {
    val scale = remember(note.id) { Animatable(1f) }
    LaunchedEffect(note.id) {
        scale.animateTo(1.05f, tween((HOLD_MS + CROSSFADE_MS).toInt(), easing = LinearEasing))
    }
    val bmp = rememberNoteBitmap(note.imagePath, 1600, loadBitmap)
    val ratio = if (note.kind == NoteKind.DRAWING && bmp != null && bmp.height > 0) {
        bmp.width.toFloat() / bmp.height
    } else {
        4f / 3f
    }
    Box(Modifier.fillMaxSize().padding(top = 56.dp, bottom = 120.dp), contentAlignment = Alignment.Center) {
        PaperNote(
            note,
            bmp,
            Modifier
                .fillMaxHeight()
                .aspectRatio(ratio)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .shadow(24.dp),
            textSizeScale = 1.8f,
        )
    }
}
