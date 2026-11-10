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
import app.familynote.ui.LetterAddress
import app.familynote.ui.SoftButton
import app.familynote.ui.theme.Palette
import app.familynote.ui.theme.Type

private const val MAX_LEN = 200

@Composable
fun WriteScreen(
    replyTo: Note?,
    /** Who is replying: the current driver (they are the one sitting in front of the screen). */
    driver: String,
    onListen: (onText: (String, Boolean) -> Unit, onError: (String) -> Unit) -> Unit,
    onStopListening: () -> Unit,
    onCancel: () -> Unit,
    /** Saves; the last argument reports success so the button can be re-enabled on failure. */
    onSave: (text: String, from: String, to: String, done: (Boolean) -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // A reply goes back to whoever wrote the original note.
    var from by remember { mutableStateOf(if (replyTo != null) driver else "아빠") }
    var to by remember { mutableStateOf(replyTo?.fromMember ?: "딸") }
    var saving by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fun startListening() {
        // Speech is appended after whatever was already typed.
        val base = text.trimEnd().let { if (it.isEmpty()) it else "$it " }
        listening = true
        hint = "듣는 중…"
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
        if (granted) startListening() else hint = "받아쓰기를 사용하려면 마이크 권한이 필요합니다"
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
                    if (replyTo != null) "${replyTo.fromMember}에게 답장" else "내용",
                    style = Type.NoteBody.copy(color = Palette.PaperHint),
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
            LetterAddress(from = from, to = to, onFrom = { from = it }, onTo = { to = it })
            Text("${text.length} / $MAX_LEN", style = Type.Small)
            SoftButton(if (listening) "받아쓰기 중지" else "받아쓰기") {
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
                SoftButton("보내기", Modifier.weight(1f), primary = true, enabled = text.isNotBlank() && !saving) {
                    saving = true
                    onStopListening()
                    onSave(text, from, to) { ok -> if (!ok) saving = false }
                }
            }
        }
    }
}
