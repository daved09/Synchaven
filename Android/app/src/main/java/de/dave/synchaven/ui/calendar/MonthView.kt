package de.dave.synchaven.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.sp
import de.dave.synchaven.R
import de.dave.synchaven.ui.theme.onColor
import de.dave.synchaven.ui.theme.parseHexColor
import de.dave.synchaven.util.DateRange
import de.dave.synchaven.util.FMT_TIME
import de.dave.synchaven.util.RowItem
import de.dave.synchaven.util.ViewEvent
import de.dave.synchaven.util.clampRow
import de.dave.synchaven.util.isoWeek
import de.dave.synchaven.util.layoutRow
import de.dave.synchaven.util.weekdayNames
import java.time.DayOfWeek
import java.time.LocalDate

private val DAY_HEADER = 24.dp
private val LANE = 18.dp
private val LANE_GAP = 2.dp
private val WEEK_NUMBER_WIDTH = 24.dp

/** Monatsraster; Tippen auf einen Tag öffnet die Tagesansicht, langes Drücken legt dort einen Termin an. */
@Composable
fun MonthView(
    range: DateRange,
    month: LocalDate,
    events: List<ViewEvent>,
    weekStart: DayOfWeek,
    showWeekNumbers: Boolean,
    onDayClick: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
    onEventClick: (ViewEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weeks = remember(range) { range.days.chunked(7) }
    val today = LocalDate.now()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (showWeekNumbers) Spacer(Modifier.width(WEEK_NUMBER_WIDTH))
            for (name in weekdayNames(weekStart)) {
                Text(
                    name, Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        for (week in weeks) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (showWeekNumbers) {
                    Text(
                        "${isoWeek(week.first())}",
                        Modifier.width(WEEK_NUMBER_WIDTH).padding(top = 4.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                WeekRow(week, month, today, events, onDayClick, onDayLongClick, onEventClick, Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekRow(
    week: List<LocalDate>,
    month: LocalDate,
    today: LocalDate,
    events: List<ViewEvent>,
    onDayClick: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
    onEventClick: (ViewEvent) -> Unit,
    modifier: Modifier,
) {
    val items = remember(events, week.first()) { layoutRow(events, week.first(), 7) }
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val cell = maxWidth / 7
        val maxLanes = ((maxHeight - DAY_HEADER) / (LANE + LANE_GAP)).toInt().coerceAtLeast(1)
        val clamped = remember(items, maxLanes) { clampRow(items, 7, maxLanes) }

        // Tageszellen mit Datum
        Row(Modifier.fillMaxSize()) {
            week.forEach { day ->
                val inMonth = day.month == month.month
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (inMonth) MaterialTheme.colorScheme.surface
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        )
                        .combinedClickable(onClick = { onDayClick(day) }, onLongClick = { onDayLongClick(day) }),
                ) {
                    DayNumber(day, isToday = day == today, inMonth = inMonth, Modifier.align(Alignment.TopCenter))
                }
            }
        }

        // Termine als Balken bzw. Zeilen
        for (item in clamped.visible) {
            EventChip(
                item,
                Modifier
                    .offset(x = cell * item.start, y = DAY_HEADER + (LANE + LANE_GAP) * item.lane)
                    .width(cell * (item.end - item.start))
                    .height(LANE)
                    .padding(horizontal = 1.dp),
                onClick = { onEventClick(item.ev) },
            )
        }
        // „+N“ für ausgeblendete Termine
        clamped.more.forEachIndexed { day, count ->
            if (count > 0) {
                Text(
                    stringResource(R.string.calendar_more, count),
                    Modifier
                        .offset(x = cell * day, y = DAY_HEADER + (LANE + LANE_GAP) * (maxLanes - 1))
                        .width(cell)
                        .height(LANE)
                        .clip(RoundedCornerShape(4.dp))
                        .combinedClickable(onClick = { onDayClick(week[day]) })
                        .wrapContentSize(Alignment.CenterStart)
                        .padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DayNumber(day: LocalDate, isToday: Boolean, inMonth: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .padding(top = 2.dp)
            .size(20.dp)
            .background(if (isToday) colors.primary else colors.surface.copy(alpha = 0f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${day.dayOfMonth}",
            fontSize = 12.sp,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isToday -> colors.onPrimary
                inMonth -> colors.onSurface
                else -> colors.outline
            },
        )
    }
}

/** Ganztägige/mehrtägige Termine als gefüllter Balken, andere als Punkt mit Uhrzeit */
@Composable
private fun EventChip(item: RowItem, modifier: Modifier, onClick: () -> Unit) {
    val color = parseHexColor(item.ev.color)
    val shape = RoundedCornerShape(
        topStart = if (item.contBefore) 0.dp else 4.dp, bottomStart = if (item.contBefore) 0.dp else 4.dp,
        topEnd = if (item.contAfter) 0.dp else 4.dp, bottomEnd = if (item.contAfter) 0.dp else 4.dp,
    )
    if (item.ev.isBar) {
        Text(
            item.ev.title,
            modifier
                .clip(shape)
                .background(color)
                .combinedClickable(onClick = onClick)
                .padding(horizontal = 4.dp)
                .wrapContentSize(Alignment.CenterStart),
            color = onColor(color),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    } else {
        Row(
            modifier.clip(RoundedCornerShape(4.dp)).combinedClickable(onClick = onClick).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(3.dp))
            Text(
                stringResource(R.string.event_time_title, item.ev.start.format(FMT_TIME), item.ev.title),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
