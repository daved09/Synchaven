package de.dave.synchaven.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.dave.synchaven.R
import de.dave.synchaven.ui.theme.parseHexColor
import de.dave.synchaven.util.FMT_TIME
import de.dave.synchaven.util.FMT_WEEKDAY_DATE
import de.dave.synchaven.util.ViewEvent
import java.time.LocalDate

/**
 * Event list grouped by day. Multi-day events appear on every day they touch
 * ([multiDay]); in search results only on their first day.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgendaView(
    days: List<LocalDate>?,
    events: List<ViewEvent>,
    emptyText: String,
    onEventClick: (ViewEvent) -> Unit,
    modifier: Modifier = Modifier,
    multiDay: Boolean = true,
    /** A task's check-off icon was tapped */
    onTaskToggle: ((ViewEvent) -> Unit)? = null,
) {
    val groups = remember(days, events, multiDay) {
        if (multiDay && days != null) {
            days.map { d -> d to events.filter { it.touches(d) } }.filter { it.second.isNotEmpty() }
        } else {
            events.groupBy { it.start.toLocalDate() }.toList().sortedBy { it.first }
        }
    }
    if (groups.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val today = LocalDate.now()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        for ((day, list) in groups) {
            stickyHeader(key = "h$day") {
                Text(
                    if (day == today) stringResource(R.string.agenda_today, day.format(FMT_WEEKDAY_DATE))
                    else day.format(FMT_WEEKDAY_DATE),
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(list, key = { "$day/${it.id}" }) { ev -> AgendaItem(ev, day, onEventClick, onTaskToggle) }
            item(key = "s$day") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun AgendaItem(ev: ViewEvent, day: LocalDate, onClick: (ViewEvent) -> Unit, onTaskToggle: ((ViewEvent) -> Unit)?) {
    val time = when {
        ev.isTask && ev.allDay -> stringResource(R.string.all_day)
        ev.isTask -> ev.start.format(FMT_TIME)
        ev.allDay -> stringResource(R.string.all_day)
        ev.start.toLocalDate() < day && ev.lastDay > day -> stringResource(R.string.all_day)
        ev.start.toLocalDate() < day -> stringResource(R.string.agenda_until, ev.end.format(FMT_TIME))
        ev.lastDay > day -> stringResource(R.string.agenda_from, ev.start.format(FMT_TIME))
        else -> stringResource(R.string.time_range, ev.start.format(FMT_TIME), ev.end.format(FMT_TIME))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick(ev) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ev.isTask) {
            // Task: check-off icon in the list's color, can be tapped directly
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .then(if (onTaskToggle != null) Modifier.clickable { onTaskToggle(ev) } else Modifier),
                contentAlignment = Alignment.Center,
            ) { TaskCheckIcon(ev.completed, parseHexColor(ev.color)) }
        } else {
            Box(
                Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(parseHexColor(ev.color))
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                ev.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = taskDecoration(ev.completed),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (ev.completed) 0.6f else 1f),
            )
            val sub = if (ev.occ.location.isBlank()) time else stringResource(R.string.agenda_subtitle, time, ev.occ.location)
            Text(
                sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
