package de.dave.synchaven.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.SyncResult
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import de.dave.synchaven.data.api.ApiException
import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.api.apiCall
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.ExdateRequest
import java.time.Instant
import java.util.TimeZone

private const val TAG = "CalendarSync"

/** The account's calendar in the Android calendar (a row in CalendarContract.Calendars). */
private data class LocalCalendar(val id: Long, val serverId: String)

/** Event or exception of an event in the Android calendar. */
private data class LocalEvent(
    val id: Long,
    val serverId: String?,
    val hash: String?,
    val dirty: Boolean,
    val deleted: Boolean,
    val originalId: Long?,
    val originalServerId: String?,
    val originalInstanceTime: Long?,
    val status: Int?,
    val title: String,
    val description: String,
    val location: String,
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val allDay: Boolean,
    val timezone: String?,
    val rrule: String?,
    val exdate: String?,
)

/**
 * Syncs the server's calendars and events with the Android calendar – in both directions.
 * Local changes (DIRTY/DELETED) are uploaded first, then the server's state is taken over.
 *
 * Mapping: calendar `_SYNC_ID` = the server's calendar ID; event `_SYNC_ID` = event ID, `SYNC_DATA1` =
 * checksum of the server state last taken over. Series are transferred with RRULE/EXDATE; modified
 * or cancelled single events (exceptions in Android) become separate events or EXDATEs of the series
 * on the server – as with the iCalendar import.
 */
class CalendarSyncer(
    private val account: Account,
    private val provider: ContentProviderClient,
    private val api: SyncHavenApi,
    private val result: SyncResult,
    private val untitled: String,
) {
    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    private val calendarsUri = Calendars.CONTENT_URI.asSyncAdapter()
    private val eventsUri = Events.CONTENT_URI.asSyncAdapter()
    private fun eventUri(id: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, id).asSyncAdapter()

    /** Errors of single events that were skipped (the first message is shown) */
    var firstItemError: Throwable? = null
        private set

    suspend fun sync() {
        val remote = apiCall { api.calendars() }
        var local = localCalendars()

        // 1. Upload local changes (only for calendars that still exist on the server)
        val remoteIds = remote.map { it.id }.toSet()
        for (cal in local) if (cal.serverId in remoteIds) upload(cal)

        // 2. Create, update, remove calendars
        syncCalendars(remote, local)
        local = localCalendars()

        // 3. Download events
        val byServerId = local.associateBy { it.serverId }
        for (cal in remote) {
            val lc = byServerId[cal.id] ?: continue
            download(lc, apiCall { api.calendarEvents(cal.id) })
        }
    }

    // ---------- Calendars ----------

    private fun localCalendars(): List<LocalCalendar> =
        provider.query(
            calendarsUri, arrayOf(Calendars._ID, Calendars._SYNC_ID),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?", arrayOf(account.name, account.type), null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val serverId = c.getString(1)
                    if (serverId != null) add(LocalCalendar(c.getLong(0), serverId))
                    else provider.delete(ContentUris.withAppendedId(Calendars.CONTENT_URI, c.getLong(0)).asSyncAdapter(), null, null)
                }
            }
        } ?: emptyList()

    private fun syncCalendars(remote: List<Calendar>, local: List<LocalCalendar>) {
        val localById = local.associateBy { it.serverId }
        for (cal in remote) {
            val values = ContentValues().apply {
                put(Calendars.NAME, cal.id)
                put(Calendars.CALENDAR_DISPLAY_NAME, cal.name)
                put(Calendars.CALENDAR_COLOR, parseColor(cal.color))
                put(
                    Calendars.CALENDAR_ACCESS_LEVEL,
                    if (cal.isBirthdays) Calendars.CAL_ACCESS_READ else Calendars.CAL_ACCESS_OWNER,
                )
                put(Calendars.OWNER_ACCOUNT, account.name)
                put(Calendars.SYNC_EVENTS, 1)
                put(Calendars.ALLOWED_REMINDERS, "${CalendarContract.Reminders.METHOD_DEFAULT},${CalendarContract.Reminders.METHOD_ALERT}")
                put(Calendars.ALLOWED_AVAILABILITY, "${Events.AVAILABILITY_BUSY},${Events.AVAILABILITY_FREE}")
            }
            val existing = localById[cal.id]
            if (existing == null) {
                values.put(Calendars.ACCOUNT_NAME, account.name)
                values.put(Calendars.ACCOUNT_TYPE, account.type)
                values.put(Calendars._SYNC_ID, cal.id)
                values.put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
                // Only take over visibility when creating – afterwards the calendar app on the device decides
                values.put(Calendars.VISIBLE, if (cal.visible) 1 else 0)
                provider.insert(calendarsUri, values)
            } else {
                provider.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, existing.id).asSyncAdapter(), values, null, null)
            }
        }
        val remoteIds = remote.map { it.id }.toSet()
        for (cal in local) {
            if (cal.serverId !in remoteIds) {
                provider.delete(ContentUris.withAppendedId(Calendars.CONTENT_URI, cal.id).asSyncAdapter(), null, null)
            }
        }
    }

    // ---------- Reading events ----------

    private val eventColumns = arrayOf(
        Events._ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.DIRTY, Events.DELETED, Events.ORIGINAL_ID,
        Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.STATUS, Events.TITLE, Events.DESCRIPTION,
        Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE,
        Events.RRULE, Events.EXDATE,
    )

    private fun Cursor.toLocalEvent() = LocalEvent(
        id = getLong(0),
        serverId = getString(1),
        hash = getString(2),
        dirty = getInt(3) != 0,
        deleted = getInt(4) != 0,
        originalId = if (isNull(5)) null else getLong(5),
        originalServerId = getString(6),
        originalInstanceTime = if (isNull(7)) null else getLong(7),
        status = if (isNull(8)) null else getInt(8),
        title = getString(9).orEmpty(),
        description = getString(10).orEmpty(),
        location = getString(11).orEmpty(),
        dtStart = getLong(12),
        dtEnd = if (isNull(13)) null else getLong(13),
        duration = getString(14),
        allDay = getInt(15) != 0,
        timezone = getString(16),
        rrule = getString(17),
        exdate = getString(18),
    )

    private fun queryEvents(selection: String, args: Array<String>): List<LocalEvent> =
        provider.query(eventsUri, eventColumns, selection, args, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.toLocalEvent()) }
        } ?: emptyList()

    private fun serverIdOf(localId: Long): String? =
        provider.query(eventUri(localId), arrayOf(Events._SYNC_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // ---------- Upload ----------

    private suspend fun upload(cal: LocalCalendar) {
        val changed = queryEvents(
            "${Events.CALENDAR_ID} = ? AND (${Events.DIRTY} = 1 OR ${Events.DELETED} = 1)", arrayOf(cal.id.toString()),
        )
        // Series/single events first, then exceptions – they need the server ID of their series
        val (exceptions, events) = changed.partition { it.originalId != null || it.originalServerId != null }
        for (ev in events) itemSafe { uploadEvent(cal, ev) }
        // Exceptions of a series that was just deleted are gone along with it
        val deletedSeries = events.filter { it.deleted }.flatMap { listOfNotNull(it.id.toString(), it.serverId) }.toSet()
        for (ev in exceptions) {
            if (ev.originalId?.toString() in deletedSeries || ev.originalServerId in deletedSeries) continue
            itemSafe { uploadException(cal, ev) }
        }
    }

    private suspend fun uploadEvent(cal: LocalCalendar, ev: LocalEvent) {
        if (ev.deleted) {
            if (ev.serverId != null) {
                try {
                    apiCall { api.deleteEvent(ev.serverId) }
                } catch (e: ApiException) {
                    if (e.status != 404) throw e
                }
                result.stats.numDeletes++
            }
            deleteLocalWithExceptions(ev.id)
            return
        }
        val input = ev.toInput(cal.serverId)
        val saved = if (ev.serverId == null) {
            apiCall { api.createEvent(input) }.also { result.stats.numInserts++ }
        } else {
            try {
                apiCall { api.updateEvent(ev.serverId, input) }
            } catch (e: ApiException) {
                // Deleted on the server in the meantime: don't discard the change from the device, create it again
                if (e.status != 404) throw e
                apiCall { api.createEvent(input) }
            }.also { result.stats.numUpdates++ }
        }
        provider.update(eventUri(ev.id), eventValues(saved, cal.id), null, null)
    }

    /**
     * Exception of a series: cancelled → EXDATE on the series; modified → separate event on the server and
     * EXDATE on the series (create first, then exclude, so that errors are more likely to produce a duplicate than a
     * loss). The local exception is removed afterwards; the download brings the series and the new event.
     */
    private suspend fun uploadException(cal: LocalCalendar, ev: LocalEvent) {
        val masterId = ev.originalServerId ?: ev.originalId?.let(::serverIdOf)
        val instance = ev.originalInstanceTime
        if (ev.deleted || masterId == null || instance == null) {
            // Deleted exception (occurrence restored) or series not yet on the server
            if (ev.deleted || masterId == null && ev.originalId == null) provider.delete(eventUri(ev.id), null, null)
            return
        }
        if (ev.status != Events.STATUS_CANCELED) {
            val input = ev.toInput(cal.serverId).copy(rrule = null, exdates = emptyList())
            apiCall { api.createEvent(input) }
            result.stats.numInserts++
        }
        try {
            apiCall { api.addExdate(masterId, ExdateRequest(Instant.ofEpochMilli(instance).toString())) }
            result.stats.numUpdates++
        } catch (e: ApiException) {
            if (e.status != 404 && e.status != 400) throw e
        }
        provider.delete(eventUri(ev.id), null, null)
    }

    private fun LocalEvent.toInput(calendarServerId: String): EventInput {
        val end = androidEventEnd(dtStart, dtEnd, duration, allDay)
        val tz = timezone?.takeIf { it.isNotBlank() } ?: "UTC"
        val recurring = !rrule.isNullOrBlank()
        return EventInput(
            calendarId = calendarServerId,
            title = title.trim().ifEmpty { untitled }.take(500),
            description = description,
            location = location,
            start = apiTime(dtStart, allDay),
            end = apiTime(end, allDay),
            allDay = allDay,
            timezone = tz,
            rrule = rrule?.trim()?.removePrefix("RRULE:")?.takeIf { recurring },
            exdates = if (recurring) parseExdates(exdate, tz, dtStart, allDay).map { it.toString() } else emptyList(),
        )
    }

    private fun deleteLocalWithExceptions(id: Long) {
        provider.delete(eventsUri, "${Events.ORIGINAL_ID} = ?", arrayOf(id.toString()))
        provider.delete(eventUri(id), null, null)
    }

    // ---------- Download ----------

    private fun download(cal: LocalCalendar, remote: List<CalEvent>) {
        val local = queryEvents(
            "${Events.CALENDAR_ID} = ? AND ${Events.ORIGINAL_ID} IS NULL AND ${Events.ORIGINAL_SYNC_ID} IS NULL",
            arrayOf(cal.id.toString()),
        )
        val localByServerId = local.filter { it.serverId != null }.associateBy { it.serverId!! }
        val ops = Batch(provider)

        for (ev in remote) {
            val existing = localByServerId[ev.id]
            when {
                existing == null -> {
                    ops += ContentProviderOperation.newInsert(eventsUri).withValues(eventValues(ev, cal.id)).build()
                    result.stats.numInserts++
                }
                // Changed locally but not uploaded (e.g. rejected by the server): don't overwrite
                existing.dirty || existing.deleted -> Unit
                existing.hash != ev.syncHash() -> {
                    ops += ContentProviderOperation.newUpdate(eventUri(existing.id)).withValues(eventValues(ev, cal.id)).build()
                    result.stats.numUpdates++
                }
            }
        }
        val remoteIds = remote.map { it.id }.toSet()
        for (ev in local) {
            if (ev.serverId != null && ev.serverId !in remoteIds && !ev.dirty) {
                ops += ContentProviderOperation.newDelete(eventsUri)
                    .withSelection("${Events.ORIGINAL_ID} = ?", arrayOf(ev.id.toString())).build()
                ops += ContentProviderOperation.newDelete(eventUri(ev.id)).build()
                result.stats.numDeletes++
            }
        }
        ops.flush()
    }

    private fun eventValues(ev: CalEvent, calendarId: Long): ContentValues {
        val t = ev.toAndroidTimes()
        return ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events._SYNC_ID, ev.id)
            put(Events.SYNC_DATA1, ev.syncHash())
            put(Events.UID_2445, ev.uid)
            put(Events.TITLE, ev.title)
            put(Events.DESCRIPTION, ev.description)
            put(Events.EVENT_LOCATION, ev.location)
            put(Events.ALL_DAY, if (ev.allDay) 1 else 0)
            put(Events.DTSTART, t.dtStart)
            if (t.dtEnd != null) put(Events.DTEND, t.dtEnd) else putNull(Events.DTEND)
            if (t.duration != null) put(Events.DURATION, t.duration) else putNull(Events.DURATION)
            put(Events.EVENT_TIMEZONE, t.timezone)
            if (t.rrule != null) put(Events.RRULE, t.rrule) else putNull(Events.RRULE)
            if (t.exdate != null) put(Events.EXDATE, t.exdate) else putNull(Events.EXDATE)
            put(Events.STATUS, Events.STATUS_CONFIRMED)
            // Without organizer = account owner, some calendar apps treat events as invitations (not editable)
            put(Events.ORGANIZER, account.name)
            put(Events.DIRTY, 0)
        }
    }

    // ---------- Error handling ----------

    /** Skip errors of a single event; network and sign-in errors abort the sync. */
    private suspend fun itemSafe(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            if (e.status == 401 || e.status >= 500) throw e
            Log.w(TAG, "Event skipped: ${e.message}")
            result.stats.numSkippedEntries++
            if (firstItemError == null) firstItemError = e
        }
    }
}

/** Collects operations and writes them in batches (a Binder transaction is limited to ~1 MB). */
internal class Batch(private val provider: ContentProviderClient, private val maxSize: Int = 200) {
    private val ops = ArrayList<ContentProviderOperation>()

    operator fun plusAssign(op: ContentProviderOperation) {
        ops += op
        if (ops.size >= maxSize) flush()
    }

    val size get() = ops.size

    /** Returns the results of the written operations (e.g. URIs of inserted rows). */
    fun flush(): Array<android.content.ContentProviderResult> {
        if (ops.isEmpty()) return emptyArray()
        val res = provider.applyBatch(ArrayList(ops))
        ops.clear()
        return res
    }

    /** Like [plusAssign], without automatic writing – for related operations with back references. */
    fun add(op: ContentProviderOperation) {
        ops += op
    }
}
