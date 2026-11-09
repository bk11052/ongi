package app.familynote.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import app.familynote.data.Note
import app.familynote.ui.ConfirmDialog
import app.familynote.ui.NewDot
import app.familynote.ui.PaperNote
import app.familynote.ui.SoftButton
import app.familynote.ui.dateLabel
import app.familynote.ui.rememberNoteBitmap
import app.familynote.ui.theme.Type

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    notes: List<Note>,
    loadBitmap: suspend (String, Int) -> Bitmap?,
    onDraw: () -> Unit,
    onWrite: () -> Unit,
    /** Opens the gallery, starting at the given note (null = newest). */
    onGallery: (String?) -> Unit,
    /** An unfinished drawing is waiting to be continued. */
    hasDraft: Boolean,
    onDelete: (Note) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Note?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("우리 쪽지함", style = Type.Title)
                val unseen = notes.count { !it.seen }
                Text(
                    if (unseen > 0) "새 쪽지 ${unseen}장이 기다리고 있어요" else "차 안에 남겨진 마음들",
                    style = Type.Small,
                )
                if (notes.isNotEmpty()) {
                    Text("쪽지를 길게 누르면 지울 수 있어요", style = Type.Small.copy(fontSize = Type.Small.fontSize * 0.85f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SoftButton(if (hasDraft) "그리던 그림 이어 그리기" else "그림 남기기", onClick = onDraw)
                SoftButton("글 남기기", onClick = onWrite)
                SoftButton("쪽지 보기", primary = true, enabled = notes.isNotEmpty(), onClick = { onGallery(null) })
            }
        }
        Spacer(Modifier.height(28.dp))
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "아직 쪽지가 없어요.\n다음 나들이 때 아이와 함께 첫 그림을 남겨 보세요.",
                    style = Type.Caption,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    // A slight, stable tilt per note makes the grid feel like pinned paper.
                    val tilt = ((note.id.hashCode() % 5) - 2) * 0.8f
                    val bmp = rememberNoteBitmap(note.imagePath, 480, loadBitmap)
                    Column(
                        Modifier.combinedClickable(
                            onClick = { onGallery(note.id) },
                            onLongClick = { pendingDelete = note },
                        ),
                    ) {
                        Box {
                            PaperNote(
                                note,
                                bmp,
                                Modifier.fillMaxWidth().aspectRatio(4f / 3f).rotate(tilt),
                                textSizeScale = 0.9f,
                            )
                            if (!note.seen) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { NewDot() }
                        }
                        Text(
                            "${note.fromMember} → ${note.toMember} · ${note.dateLabel()}",
                            style = Type.Small,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { note ->
        ConfirmDialog(
            title = "이 쪽지를 지울까요?",
            body = "${note.fromMember} → ${note.toMember} · ${note.dateLabel()}\n지운 쪽지는 되돌릴 수 없어요.",
            confirmText = "지우기",
            dismissText = "그대로 두기",
            onConfirm = { onDelete(note); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}
