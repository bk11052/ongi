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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.familynote.ui.theme.Palette
import app.familynote.ui.theme.Type

/**
 * Recipient and sender, laid out like a mail compose header: a grouped panel with a
 * "받는 사람" row and a "보낸 사람" row, each a row of selectable name pills.
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
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.NightRaised)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AddressLine("받는 사람", to, onTo)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        AddressLine("보낸 사람", from, onFrom)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddressLine(label: String, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = Type.Small)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FamilyMembers.forEach { name -> NamePill(name, name == selected) { onSelect(name) } }
        }
    }
}

@Composable
private fun NamePill(name: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Palette.Amber else Palette.Night, tween(200), label = "pill")
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, style = Type.Label.copy(color = if (on) Palette.Night else Palette.Moon))
    }
}
