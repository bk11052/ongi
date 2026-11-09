package app.familynote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.familynote.ui.theme.Type

/**
 * The only thing shown while the car is not in P: static text, no touch targets.
 * [unseen] is how many notes are waiting, so the driver knows without interacting.
 */
@Composable
fun DrivingScreen(unseen: Int, connecting: Boolean, hasDraft: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (connecting) {
                Text("잠시만요", style = Type.Title)
            } else {
                Text("운전에 집중하세요", style = Type.Title)
                Text(
                    if (unseen > 0) "새 쪽지 ${unseen}장은 도착하면 보여드릴게요" else "도착하면 다시 만나요",
                    style = Type.Caption,
                    textAlign = TextAlign.Center,
                )
                if (hasDraft) Text("그리던 그림은 그대로 남겨 둘게요", style = Type.Small, textAlign = TextAlign.Center)
            }
        }
    }
}
