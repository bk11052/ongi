package app.familynote.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import app.familynote.data.FamilySettings
import app.familynote.data.Note
import app.familynote.data.NoteKind
import app.familynote.ui.ChipRow
import app.familynote.ui.ConfirmDialog
import app.familynote.ui.FamilyMembers
import app.familynote.ui.NewDot
import app.familynote.ui.PaperNote
import app.familynote.ui.SoftButton
import app.familynote.ui.dateLabel
import app.familynote.ui.NOTE_RATIO
import app.familynote.ui.rememberNoteBitmap
import app.familynote.ui.theme.Palette
import app.familynote.ui.theme.Type

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    notes: List<Note>,
    /** Notes waiting for the driver (or the whole family). */
    waiting: Int,
    driver: String,
    onDriverChange: (String) -> Unit,
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
    var choosingDriver by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("쪽지", style = Type.Title)
                if (waiting > 0) {
                    Text(
                        "새 쪽지 ${waiting}장",
                        style = Type.Label.copy(color = Palette.Night),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Palette.Amber)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                // Who is driving, shown like a profile: tap to switch.
                SoftButton(driver, icon = Icons.Outlined.Person, onClick = { choosingDriver = true })
                Spacer(Modifier.width(10.dp))
                SoftButton(if (hasDraft) "이어 그리기" else "그리기", icon = Icons.Outlined.Create, onClick = onDraw)
                SoftButton("쓰기", icon = Icons.Outlined.Email, onClick = onWrite)
                SoftButton("슬라이드쇼", icon = Icons.Outlined.PlayArrow, primary = true, enabled = notes.isNotEmpty(), onClick = { onGallery(null) })
            }
        }
        Spacer(Modifier.height(28.dp))
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "쪽지 없음\n그리기나 쓰기로 첫 쪽지를 보내 보세요.",
                    style = Type.Caption,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 420.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    val bmp = rememberNoteBitmap(note.imagePath, 520, loadBitmap)
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
                                Modifier.fillMaxWidth().aspectRatio(NOTE_RATIO).shadow(6.dp, RoundedCornerShape(12.dp)),
                                textSizeScale = 1.05f,
                            )
                            if (!note.seen && FamilySettings.isFor(note, driver)) {
                                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { NewDot(18.dp) }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${note.fromMember} → ${note.toMember} · ${note.dateLabel()}",
                                style = Type.Small,
                                modifier = Modifier.weight(1f),
                            )
                            // Visible, but quiet: deleting is rare and always asks first.
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .clickable { pendingDelete = note },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = "쪽지 지우기", tint = Palette.Dim, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (choosingDriver) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { choosingDriver = false }) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Palette.NightRaised)
                    .padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("운전자", style = Type.Label)
                Text("주차하면 운전자에게 온 쪽지를 먼저 표시합니다", style = Type.Small)
                ChipRow("", FamilyMembers.filter { it != FamilySettings.WHOLE_FAMILY }, driver) {
                    onDriverChange(it)
                    choosingDriver = false
                }
                SoftButton("닫기", onClick = { choosingDriver = false })
            }
        }
    }

    pendingDelete?.let { note ->
        ConfirmDialog(
            preview = {
                val bmp = rememberNoteBitmap(note.imagePath, 480, loadBitmap)
                PaperNote(note, bmp, Modifier.width(420.dp).aspectRatio(NOTE_RATIO), textSizeScale = 0.8f)
            },
            title = "이 쪽지를 삭제하겠습니까?",
            body = "${note.fromMember} → ${note.toMember} · ${note.dateLabel()}\n삭제한 쪽지는 복구할 수 없습니다.",
            confirmText = "삭제",
            dismissText = "취소",
            onConfirm = { onDelete(note); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}
