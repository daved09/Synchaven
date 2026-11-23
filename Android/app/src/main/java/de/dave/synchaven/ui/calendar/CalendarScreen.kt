package de.dave.synchaven.ui.calendar

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.store.ViewMode
import de.dave.synchaven.ui.common.CollectMessages
import de.dave.synchaven.ui.common.ConfirmDialog
import de.dave.synchaven.ui.common.DatePickerModal
import de.dave.synchaven.ui.common.ErrorBox
import de.dave.synchaven.ui.common.MainBottomBar
import de.dave.synchaven.ui.common.MainTab
import de.dave.synchaven.ui.common.appViewModel
import de.dave.synchaven.util.FMT_DATE_SHORT
import de.dave.synchaven.util.LIST_DAYS
import de.dave.synchaven.util.ViewEvent
import de.dave.synchaven.util.stepDate
import de.dave.synchaven.util.viewRange
import de.dave.synchaven.util.toInstant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onSelectTab: (MainTab) -> Unit,
    /** Neuer Termin ab Zeitpunkt (Epochensekunden), ganztägig oder nicht */
    onNewEvent: (Long, Boolean) -> Unit,
    onEditEvent: (String) -> Unit,
    onOpenContact: (String) -> Unit,
) {
    val vm = appViewModel { CalendarViewModel(it.calendarRepository, it.settingsStore, it.sessionStore, it.authRepository, it.fileTransfer) }
    val s by vm.state.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages.flow, snackbar)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> (s.events + search.results).firstOrNull { it.id == id } }
    var pickDate by remember { mutableStateOf(false) }
    var pendingMove by remember { mutableStateOf<Pair<ViewEvent, LocalDateTime>?>(null) }
    var calendarDialog by remember { mutableStateOf<Calendar?>(null) }
    var newCalendarDialog by remember { mutableStateOf(false) }
    var deleteCalendar by remember { mutableStateOf<Calendar?>(null) }

    // Import/Export: der Kalender, für den die Dateiauswahl geöffnet wurde
    var fileCalendarId by rememberSaveable { mutableStateOf<String?>(null) }
    val fileCalendar = s.calendars.firstOrNull { it.id == fileCalendarId }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null && fileCalendar != null) vm.exportCalendar(fileCalendar, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && fileCalendar != null) vm.importCalendar(fileCalendar, uri)
    }

    fun newEventAt(start: LocalDateTime, allDay: Boolean) = onNewEvent(start.toInstant().epochSecond, allDay)
    fun newEventOn(day: LocalDate) {
        val now = LocalDateTime.now()
        newEventAt(if (day == now.toLocalDate()) now.plusHours(1).truncatedTo(ChronoUnit.HOURS) else day.atTime(9, 0), false)
    }
    fun requestMove(ev: ViewEvent, start: LocalDateTime) {
        if (ev.occ.recurring) pendingMove = ev to start else vm.moveEvent(ev, start)
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = search.active && !drawerState.isOpen) { vm.closeSearch() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            CalendarDrawer(
                mode = s.mode,
                calendars = s.calendars,
                onSelectMode = { vm.setMode(it); scope.launch { drawerState.close() } },
                onToggle = vm::toggleCalendar,
                onAction = { cal, action ->
                    when (action) {
                        CalendarAction.EDIT -> calendarDialog = cal
                        CalendarAction.DELETE -> deleteCalendar = cal
                        CalendarAction.EXPORT -> {
                            fileCalendarId = cal.id
                            exportLauncher.launch("${cal.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")}.ics")
                        }
                        CalendarAction.IMPORT -> {
                            fileCalendarId = cal.id
                            importLauncher.launch(arrayOf("text/calendar", "text/x-vcalendar", "application/octet-stream", "text/plain"))
                        }
                    }
                },
                onNewCalendar = { newCalendarDialog = true },
            )
        },
    ) {
        Scaffold(
            topBar = {
                if (search.active) {
                    SearchTopBar(search.query, vm::onSearchChange, vm::closeSearch)
                } else {
                    CalendarTopBar(
                        title = s.title,
                        mode = s.mode,
                        onMenu = { scope.launch { drawerState.open() } },
                        onTitleClick = { pickDate = true },
                        onToday = vm::today,
                        onSearch = vm::openSearch,
                        onMode = vm::setMode,
                        onRefresh = vm::refresh,
                    )
                }
            },
            bottomBar = { MainBottomBar(MainTab.CALENDAR, onSelectTab) },
            floatingActionButton = {
                if (!search.active) {
                    FloatingActionButton(onClick = { newEventOn(if (s.mode == ViewMode.WEEK) LocalDate.now() else s.date) }) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.calendar_new_event))
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (s.loading || search.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    search.active -> AgendaView(
                        days = null, events = search.results, multiDay = false,
                        emptyText = stringResource(
                            if (search.query.isBlank()) R.string.calendar_search_empty_query else R.string.calendar_search_no_results
                        ),
                        onEventClick = { selectedId = it.id },
                    )
                    s.error != null && s.events.isEmpty() && s.calendars.isEmpty() ->
                        ErrorBox(s.error!!, onRetry = vm::refresh)
                    else -> CalendarContent(
                        s = s,
                        onPageSettled = vm::showPage,
                        onDayClick = { vm.goto(it, ViewMode.DAY) },
                        onNewEventOn = ::newEventOn,
                        onNewEventAt = ::newEventAt,
                        onEventClick = { selectedId = it.id },
                        onEventMove = ::requestMove,
                    )
                }
            }
        }
    }

    selected?.let { ev ->
        EventDetailsSheet(
            ev = ev,
            calendar = s.calendars.firstOrNull { it.id == ev.occ.calendarId },
            onEdit = { selectedId = null; onEditEvent(ev.occ.eventId) },
            onDelete = { scope -> selectedId = null; vm.deleteEvent(ev, scope) },
            onOpenContact = { id -> selectedId = null; onOpenContact(id) },
            onDismiss = { selectedId = null },
        )
    }
    if (pickDate) DatePickerModal(s.date, onPick = { vm.goto(it) }, onDismiss = { pickDate = false })
    pendingMove?.let { (ev, start) ->
        ConfirmDialog(
            title = stringResource(R.string.dialog_move_series_title),
            text = stringResource(R.string.dialog_move_series_text, ev.start.format(FMT_DATE_SHORT)),
            confirmLabel = stringResource(R.string.action_move),
            destructive = false,
            onConfirm = { vm.moveEvent(ev, start) },
            onDismiss = { pendingMove = null },
        )
    }
    if (newCalendarDialog) {
        CalendarDialog(null, onSave = { vm.saveCalendar(null, it) }, onDismiss = { newCalendarDialog = false })
    }
    calendarDialog?.let { cal ->
        CalendarDialog(cal, onSave = { vm.saveCalendar(cal.id, it) }, onDismiss = { calendarDialog = null })
    }
    deleteCalendar?.let { cal ->
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_calendar_title),
            text = stringResource(R.string.dialog_delete_calendar_text, cal.name),
            onConfirm = { vm.deleteCalendar(cal) },
            onDismiss = { deleteCalendar = null },
        )
    }
}

/** Seitenzahl des Pagers – von der Mitte aus lässt sich praktisch unbegrenzt in beide Richtungen blättern */
private const val PAGE_COUNT = 20_000
private const val START_PAGE = PAGE_COUNT / 2

/**
 * Kalenderansicht als Pager: Beim Wischen folgt der Zeitraum dem Finger, der vorige bzw. nächste ist
 * daneben schon zu sehen. Sprünge (Heute, Datumswahl, Ansichtswechsel) beginnen einen neuen Pager.
 */
@Composable
private fun CalendarContent(
    s: CalendarUiState,
    onPageSettled: (LocalDate) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onNewEventOn: (LocalDate) -> Unit,
    onNewEventAt: (LocalDateTime, Boolean) -> Unit,
    onEventClick: (ViewEvent) -> Unit,
    onEventMove: (ViewEvent, LocalDateTime) -> Unit,
) {
    // Gemeinsame Scrollposition aller Wochen-/Tagesseiten
    val gridScroll = rememberTimeGridScroll(s.settings.dayStartHour)
    val onSettled by rememberUpdatedState(onPageSettled)
    AnimatedContent(
        targetState = s.anchor,
        transitionSpec = {
            if (initialState.mode != targetState.mode) fadeIn() togetherWith fadeOut()
            else {
                val forward = targetState.forward
                slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn() togetherWith
                    slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut()
            }
        },
        label = "calendar",
    ) { anchor ->
        fun dateOf(page: Int) = stepDate(anchor.mode, anchor.date, page - START_PAGE)
        val pager = rememberPagerState(initialPage = START_PAGE) { PAGE_COUNT }
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.drop(1).collect { onSettled(dateOf(it)) }
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            // Nachbarseiten vorab aufbauen, damit das Wischen ohne Ruckeln beginnt
            beyondViewportPageCount = 1,
            key = { it },
        ) { page ->
            val date = dateOf(page)
            val range = viewRange(anchor.mode, date, s.settings.weekStart)
            when (anchor.mode) {
                ViewMode.MONTH -> MonthView(
                    range = range, month = date, events = s.events,
                    weekStart = s.settings.weekStart, showWeekNumbers = s.settings.showWeekNumbers,
                    onDayClick = onDayClick, onDayLongClick = onNewEventOn, onEventClick = onEventClick,
                )
                ViewMode.WEEK, ViewMode.DAY -> TimeGridView(
                    days = range.days, events = s.events, dayStartHour = s.settings.dayStartHour,
                    onDayClick = onDayClick,
                    onSlotClick = { onNewEventAt(it, false) },
                    onAllDayClick = { onNewEventAt(it.atStartOfDay(), true) },
                    onEventClick = onEventClick,
                    onEventMove = onEventMove,
                    scroll = gridScroll,
                )
                ViewMode.LIST -> AgendaView(
                    days = range.days, events = s.events,
                    emptyText = pluralStringResource(R.plurals.calendar_list_empty, LIST_DAYS.toInt(), LIST_DAYS),
                    onEventClick = onEventClick,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTopBar(
    title: UiText,
    mode: ViewMode,
    onMenu: () -> Unit,
    onTitleClick: () -> Unit,
    onToday: () -> Unit,
    onSearch: () -> Unit,
    onMode: (ViewMode) -> Unit,
    onRefresh: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, stringResource(R.string.calendar_open_drawer)) } },
        title = {
            Box(
                Modifier.clickable(onClick = onTitleClick),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title.asString(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Outlined.ArrowDropDown, stringResource(R.string.calendar_pick_date))
                }
            }
        },
        actions = {
            IconButton(onClick = onToday) { Icon(Icons.Outlined.Today, stringResource(R.string.calendar_today)) }
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, stringResource(R.string.action_search)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (m in ViewMode.entries) {
                        DropdownMenuItem(
                            text = { Text(stringResource(m.labelRes)) },
                            leadingIcon = { Icon(m.icon, null) },
                            onClick = { menu = false; onMode(m) },
                            enabled = m != mode,
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_refresh)) },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        onClick = { menu = false; onRefresh() },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.calendar_search_close)) } },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.calendar_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_clear)) }
        },
    )
}
