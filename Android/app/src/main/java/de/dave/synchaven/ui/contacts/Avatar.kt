package de.dave.synchaven.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.ui.theme.onColor
import de.dave.synchaven.ui.theme.parseHexColor
import de.dave.synchaven.util.avatarColor
import de.dave.synchaven.util.initials

/** Runder Avatar mit Initialen in einer aus der UID abgeleiteten Farbe */
@Composable
fun Avatar(c: Contact, sizeDp: Int) {
    val color = parseHexColor(c.avatarColor())
    Box(Modifier.size(sizeDp.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            c.initials(),
            color = onColor(color),
            style = if (sizeDp >= 72) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
        )
    }
}
