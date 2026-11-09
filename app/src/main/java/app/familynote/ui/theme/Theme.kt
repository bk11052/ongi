package app.familynote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.familynote.R

object Palette {
    val Night = Color(0xFF14161F)
    val NightRaised = Color(0xFF1E2130)
    val Paper = Color(0xFFF6EFE4)
    val PaperShade = Color(0xFFE4D8C4)
    val Ink = Color(0xFF3A3530)
    val Amber = Color(0xFFF2A65A)
    val Moon = Color(0xFFE9E4DA)
    val Dim = Color(0xFF8B8A93)
}

val Handwriting = FontFamily(Font(R.font.nanum_pen_script))

object Type {
    val Title = TextStyle(fontFamily = Handwriting, fontSize = 52.sp, color = Palette.Moon)
    val NoteBody = TextStyle(fontFamily = Handwriting, fontSize = 44.sp, lineHeight = 52.sp, color = Palette.Ink)
    val Caption = TextStyle(fontFamily = Handwriting, fontSize = 32.sp, color = Palette.Moon)
    val Label = TextStyle(fontSize = 22.sp, color = Palette.Moon)
    val Small = TextStyle(fontSize = 18.sp, color = Palette.Dim)
}

@Composable
fun FamilyNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Amber,
            onPrimary = Palette.Night,
            background = Palette.Night,
            surface = Palette.NightRaised,
            onSurface = Palette.Moon,
            onBackground = Palette.Moon,
        ),
        content = content,
    )
}
