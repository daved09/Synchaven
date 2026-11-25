package de.dave.synchaven.ui.calendar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.dave.synchaven.R
import de.dave.synchaven.ui.theme.onColor
import de.dave.synchaven.ui.theme.parseHexColor
import de.dave.synchaven.util.FMT_TIME
import de.dave.synchaven.util.hourLabel
import de.dave.synchaven.util.ViewEvent
import de.dave.synchaven.util.layoutDay
import de.dave.synchaven.util.layoutRow
import de.dave.synchaven.util.shortName
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val HOUR_HEIGHT = 56.dp
private val LABEL_WIDTH = 44.dp
private val ALL_DAY_LANE = 22.dp

/** Hours shown above the now line when the calendar opens */
private const val NOW_CONTEXT_HOURS = 2f

/**
 * Scroll position shared by several hour grids (pager pages). When the calendar opens it starts a little
 * above the current time, so the now line is in view; it survives returning from other screens.
 */
@Composable
fun rememberSharedTimeGridOffset(): MutableIntState {
    val density = LocalDensity.current
    return rememberSaveable {
        val now = LocalTime.now()
        val hours = (now.hour + now.minute / 60f - NOW_CONTEXT_HOURS).coerceAtLeast(0f)
        // too large values are capped at the maximum by the ScrollState
        mutableIntStateOf(with(density) { (HOUR_HEIGHT * hours).roundToPx() })
    }
}

/**
 * Own scroll state of one page that follows [shared] and, while [active], drives it.
 * Pages can't share one ScrollState: the all-day row differs in height per page, so does the
 * maximum scroll position – a shared state would cap the current page at its neighbor's maximum.
 */
@Composable
fun rememberSyncedTimeGridScroll(shared: MutableIntState, active: Boolean): ScrollState {
    val state = rememberScrollState(shared.intValue)
    LaunchedEffect(state, active) {
        if (active) snapshotFlow { state.value }.collect { shared.intValue = it }
        else snapshotFlow { shared.intValue }.collect { state.scrollTo(it) }
    }
    return state
}

/** Minute grid when moving via drag & drop */
private const val SNAP_MINUTES = 15

/**
 * Week or day view with an hour grid. Tapping a free spot creates an event,
 * long-pressing an event moves it (time and – in the week view – day).
 */
@Composable
fun TimeGridView(
    days: List<LocalDate>,
    events: List<ViewEvent>,
    onDayClick: (LocalDate) -> Unit,
    onSlotClick: (LocalDateTime) -> Unit,
    onAllDayClick: (LocalDate) -> Unit,
    onEventClick: (ViewEvent) -> Unit,
    onEventMove: (ViewEvent, LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
    /** Vertical scroll position – see [rememberSyncedTimeGridScroll], so the hours line up while swiping */
    scroll: ScrollState,
) {
    val today = LocalDate.now()
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        // Header row with weekday and date
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Spacer(Modifier.width(LABEL_WIDTH))
            for (day in days) {
                val isToday = day == today
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = days.size > 1) { onDayClick(day) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        day.dayOfWeek.shortName(), style = MaterialTheme.typography.labelMedium,
                        color = if (isToday) colors.primary else colors.onSurfaceVariant,
                    )
                    Box(
                        Modifier.size(32.dp).background(if (isToday) colors.primary else colors.surface.copy(alpha = 0f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${day.dayOfMonth}", style = MaterialTheme.typography.titleMedium,
                            color = if (isToday) colors.onPrimary else colors.onSurface,
                        )
                    }
                }
            }
        }

        AllDayRow(days, events, onAllDayClick, onEventClick)
        HorizontalDivider(color = colors.outlineVariant)

        val density = LocalDensity.current
        // Time updated every minute for the now line
        val now by produceState(LocalDateTime.now()) {
            while (true) {
                delay(60_000)
                value = LocalDateTime.now()
            }
        }

        Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(Modifier.width(LABEL_WIDTH)) {
                for (h in 0 until 24) {
                    Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth()) {
                        if (h > 0) {
                            Text(
                                hourLabel(h),
                                Modifier.offset(y = (-7).dp).align(Alignment.TopCenter),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).height(HOUR_HEIGHT * 24)) {
                val colWidth = maxWidth / days.size
                val colWidthPx = with(density) { colWidth.toPx() }
                val hourPx = with(density) { HOUR_HEIGHT.toPx() }
                val lineColor = colors.outlineVariant

                // Grid and tapping free times
                Row(
                    Modifier.fillMaxSize().drawBehind {
                        for (h in 1 until 24) {
                            val y = h * hourPx
                            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        }
                    }
                ) {
                    days.forEachIndexed { index, day ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .drawBehind {
                                    if (index > 0) drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
                                }
                                .pointerInput(day) {
                                    detectTapGestures { pos ->
                                        // round to half hours
                                        val halfHours = (pos.y / (hourPx / 2)).toInt().coerceIn(0, 47)
                                        onSlotClick(day.atTime(halfHours / 2, (halfHours % 2) * 30))
                                    }
                                },
                        )
                    }
                }

                // Events
                days.forEachIndexed { index, day ->
                    val items = remember(events, day) { layoutDay(events, day) }
                    for (item in items) {
                        TimedEvent(
                            ev = item.ev,
                            modifier = Modifier
                                .offset(
                                    x = colWidth * index + colWidth * item.col / item.cols,
                                    y = HOUR_HEIGHT * (item.top / 60f),
                                )
                                .width(colWidth / item.cols)
                                .height(HOUR_HEIGHT * (item.height / 60f))
                                .padding(1.dp),
                            hourPx = hourPx,
                            colWidthPx = colWidthPx,
                            maxDayShift = days.size - 1 - index to -index,
                            onClick = { onEventClick(item.ev) },
                            onMove = { newStart -> onEventMove(item.ev, newStart) },
                        )
                    }
                    if (day == now.toLocalDate()) {
                        val minutes = now.hour * 60 + now.minute
                        Box(
                            Modifier
                                .zIndex(2f)
                                .offset(x = colWidth * index, y = HOUR_HEIGHT * (minutes / 60f) - 1.dp)
                                .width(colWidth)
                                .height(2.dp)
                                .background(colors.error)
                        )
                    }
                }
            }
        }
    }
}

/** Bar for all-day and multi-day events */
@Composable
private fun AllDayRow(
    days: List<LocalDate>,
    events: List<ViewEvent>,
    onAllDayClick: (LocalDate) -> Unit,
    onEventClick: (ViewEvent) -> Unit,
) {
    val bars = remember(events) { events.filter { it.isBar } }
    val items = remember(bars, days.first()) { layoutRow(bars, days.first(), days.size) }
    val lanes = (items.maxOfOrNull { it.lane } ?: -1) + 1
    Row(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.calendar_all_day_short), Modifier.width(LABEL_WIDTH).padding(top = 4.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(Modifier.weight(1f).height(ALL_DAY_LANE * maxOf(lanes, 1) + 4.dp)) {
            val colWidth = maxWidth / days.size
            Row(Modifier.fillMaxSize()) {
                for (day in days) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { onAllDayClick(day) })
                }
            }
            for (item in items) {
                val ev = item.ev
                val color = parseHexColor(ev.color)
                Row(
                    Modifier
                        .offset(x = colWidth * item.start, y = ALL_DAY_LANE * item.lane + 2.dp)
                        .width(colWidth * (item.end - item.start))
                        .height(ALL_DAY_LANE - 2.dp)
                        .padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (ev.completed) color.copy(alpha = 0.55f) else color)
                        .clickable { onEventClick(ev) }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ev.isTask) {
                        TaskCheckIcon(ev.completed, onColor(color), size = 14.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        ev.title,
                        color = onColor(color),
                        style = MaterialTheme.typography.labelMedium,
                        textDecoration = taskDecoration(ev.completed),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimedEvent(
    ev: ViewEvent,
    modifier: Modifier,
    hourPx: Float,
    colWidthPx: Float,
    /** allowed day shift to the right or left */
    maxDayShift: Pair<Int, Int>,
    onClick: () -> Unit,
    onMove: (LocalDateTime) -> Unit,
) {
    val color = parseHexColor(ev.color)
    val textColor = onColor(color)
    var drag by remember(ev.id) { mutableStateOf<Offset?>(null) }
    val snapPx = hourPx * SNAP_MINUTES / 60f

    Box(
        modifier
            .zIndex(if (drag != null) 3f else 1f)
            .offset {
                val d = drag ?: return@offset IntOffset.Zero
                // Snap to the grid and whole days while dragging
                val days = (d.x / colWidthPx).roundToInt().coerceIn(maxDayShift.second, maxDayShift.first)
                IntOffset((days * colWidthPx).roundToInt(), ((d.y / snapPx).roundToInt() * snapPx).roundToInt())
            }
            .then(if (drag != null) Modifier.shadow(6.dp, RoundedCornerShape(6.dp)) else Modifier)
            .clip(RoundedCornerShape(6.dp))
            .background(if (ev.completed) color.copy(alpha = 0.55f) else color)
            .then(if (drag != null) Modifier.border(2.dp, textColor, RoundedCornerShape(6.dp)) else Modifier)
            .pointerInput(ev.id) {
                if (ev.occ.readOnly) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { drag = Offset.Zero },
                    onDrag = { change, amount ->
                        change.consume()
                        drag = (drag ?: Offset.Zero) + amount
                    },
                    onDragEnd = {
                        val d = drag ?: Offset.Zero
                        drag = null
                        val days = (d.x / colWidthPx).roundToInt().coerceIn(maxDayShift.second, maxDayShift.first)
                        val minutes = (d.y / snapPx).roundToInt() * SNAP_MINUTES
                        if (days != 0 || minutes != 0) {
                            onMove(ev.start.plusDays(days.toLong()).plusMinutes(minutes.toLong()))
                        }
                    },
                    onDragCancel = { drag = null },
                )
            }
            .pointerInput(ev.id) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        val d = drag
        val startTime = if (d != null) {
            ev.start.plusMinutes(((d.y / snapPx).roundToInt() * SNAP_MINUTES).toLong()).toLocalTime()
        } else ev.start.toLocalTime()
        val duration = Duration.between(ev.start, ev.end).toMinutes()
        val timeText = stringResource(R.string.time_range, startTime.format(FMT_TIME), startTime.plusMinutes(duration).format(FMT_TIME))
        if (ev.isTask) {
            // Tasks: check-off icon, title and due date in one row
            Row(verticalAlignment = Alignment.CenterVertically) {
                TaskCheckIcon(ev.completed, textColor, size = 14.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.event_title_time, ev.title, startTime.format(FMT_TIME)), color = textColor,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                    textDecoration = taskDecoration(ev.completed), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (duration < 45) {
            // Short events: title and time in one row
            Text(
                stringResource(R.string.event_title_time, ev.title, startTime.format(FMT_TIME)), color = textColor,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column {
                Text(
                    ev.title, color = textColor, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(timeText, color = textColor.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
