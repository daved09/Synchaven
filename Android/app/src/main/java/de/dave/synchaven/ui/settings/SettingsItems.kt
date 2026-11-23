package de.dave.synchaven.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
internal fun <T> ChoiceItem(
    title: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = leadingIcon,
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        trailingContent = {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((v, label) in options) {
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(v) })
                }
            }
        },
        modifier = Modifier.clickable { open = true },
    )
}
