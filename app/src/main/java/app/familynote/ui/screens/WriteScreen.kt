package app.familynote.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import app.familynote.data.Note
import app.familynote.ui.ChipRow
import app.familynote.ui.FamilyMembers
import app.familynote.ui.SoftButton
import app.familynote.ui.theme.Palette
import app.familynote.ui.theme.Type

private const val MAX_LEN = 200

@Composable
fun WriteScreen(
    replyTo: Note?,
    onListen: (onText: (String, Boolean) -> Unit, onError: (String) -> Unit) -> Unit,
    onStopListening: () -> Unit,
    onCancel: () -> Unit,
    onSave: (text: String, from: String, to: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // A reply goes back to whoever wrote the original note.
    var from by remember { mutableStateOf(replyTo?.toMember ?: "아빠") }
    var to by remember { mutableStateOf(replyTo?.fromMember ?: "딸") }
    var saving by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fun startListening() {
        // Speech is appended after whatever was already typed.
        val base = text.trimEnd().let { if (it.isEmpty()) it else "$it " }
        listening = true
        hint = "듣고 있어요…"
        onListen(
            { heard, final ->
                text = (base + heard).take(MAX_LEN)
                if (final) {
                    listening = false
                    hint = null
                }
            },
            { message ->
                listening = false
                hint = message
            },
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else hint = "마이크 권한이 있어야 말로 쓸 수 있어요"
    }

    DisposableEffect(Unit) { onDispose { onStopListening() } }

    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.Paper)
                .padding(40.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    if (replyTo != null) "${replyTo.fromMember}에게 답장을 써 주세요" else "마음을 적어 주세요",
                    style = Type.NoteBody.copy(color = Palette.PaperShade),
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= MAX_LEN) text = it },
                textStyle = Type.NoteBody,
                cursorBrush = SolidColor(Palette.Amber),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ChipRow("누가", FamilyMembers, from) { from = it }
            ChipRow("누구에게", FamilyMembers, to) { to = it }
            Text("${text.length} / $MAX_LEN", style = Type.Small)
            SoftButton(if (listening) "그만 듣기" else "말로 쓰기") {
                when {
                    listening -> {
                        onStopListening()
                        listening = false
                        hint = null
                    }
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED -> startListening()
                    else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            hint?.let { Text(it, style = Type.Small) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SoftButton("취소", Modifier.weight(1f), onClick = onCancel)
                SoftButton("남기기", Modifier.weight(1f), primary = true, enabled = text.isNotBlank() && !saving) {
                    saving = true
                    onSave(text, from, to)
                }
            }
        }
    }
}
