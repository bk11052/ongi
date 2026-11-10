package app.familynote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.familynote.ui.theme.Palette
import app.familynote.ui.theme.Type
import app.familynote.util.Korean

/** A crayon-orange ink for the address line, warmer than the UI amber on paper. */
private val WarmInk = Color(0xFFC0632A)
private val HeartRed = Color(0xFFD9483B)

/** Small fixed tilts so the name stickers look hand-placed, not laid out by a grid. */
private val StickerTilts = listOf(-2.2f, 1.6f, -1.1f, 2.4f, -1.7f)

/**
 * "Who is this for, and from whom" written like the corner of a letter: a small sheet of
 * paper with "To." and "From." in handwriting and the family names as little stickers.
 * The finished address reads back as a sentence at the bottom ("♥ 딸이 아빠에게").
 */
@Composable
fun LetterAddress(
    from: String,
    to: String,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .rotate(-0.8f)
            .shadow(10.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Paper)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AddressLine("To.", to, onTo)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.PaperShade))
        AddressLine("From.", from, onFrom)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = HeartRed)) { append("♥ ") }
                append("${Korean.subject(from)} ${to}에게")
            },
            style = Type.NoteBody.copy(fontSize = 34.sp, color = WarmInk),
            modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddressLine(label: String, selected: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = Type.NoteBody.copy(fontSize = 32.sp),
            modifier = Modifier.width(78.dp).padding(top = 6.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FamilyMembers.forEachIndexed { i, name ->
                NameSticker(name, name == selected, StickerTilts[i % StickerTilts.size]) { onSelect(name) }
            }
        }
    }
}

@Composable
private fun NameSticker(name: String, on: Boolean, tilt: Float, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Palette.Amber else Palette.PaperShade.copy(alpha = 0.55f), tween(250), label = "sticker")
    Box(
        Modifier
            .rotate(if (on) tilt * 1.4f else tilt)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            buildAnnotatedString {
                if (on) withStyle(SpanStyle(color = HeartRed)) { append("♥ ") }
                append(name)
            },
            style = Type.NoteBody.copy(fontSize = 32.sp, lineHeight = 38.sp, color = Palette.Ink),
        )
    }
}
