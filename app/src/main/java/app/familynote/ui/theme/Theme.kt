package app.familynote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val Dim = Color(0xFFA7A6B0)
    /** Hint text on paper: readable, but clearly not ink. */
    val PaperHint = Color(0xFFA89A84)
}

val Handwriting = FontFamily(Font(R.font.gaegu_regular))

/** Clean, rounded Korean sans for everything that is not a note. */
val UiFont = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
)

object Type {
    // UI text is plain and calm; the handwriting font is kept for the notes themselves.
    val Title = TextStyle(fontFamily = UiFont, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Palette.Moon)
    val NoteBody = TextStyle(fontFamily = Handwriting, fontSize = 38.sp, lineHeight = 50.sp, color = Palette.Ink)
    val Caption = TextStyle(fontFamily = UiFont, fontSize = 28.sp, color = Palette.Moon)
    val Label = TextStyle(fontFamily = UiFont, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Palette.Moon)
    val Small = TextStyle(fontFamily = UiFont, fontSize = 22.sp, color = Palette.Dim)
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
