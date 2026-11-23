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

/** Kalender des Kontos im Android-Kalender (eine Zeile in CalendarContract.Calendars). */
private data class LocalCalendar(val id: Long, val serverId: String)

/** Termin oder Ausnahme eines Termins im Android-Kalender. */
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
 * Gleicht die Kalender und Termine des Servers mit dem Android-Kalender ab – in beide Richtungen.
 * Lokale Änderungen (DIRTY/DELETED) werden zuerst hochgeladen, danach der Stand des Servers übernommen.
 *
 * Abbildung: Kalender `_SYNC_ID` = Kalender-ID des Servers; Termin `_SYNC_ID` = Termin-ID, `SYNC_DATA1` =
 * Prüfsumme des zuletzt übernommenen Serverstands. Serien werden mit RRULE/EXDATE übertragen; geänderte
 * oder abgesagte Einzeltermine (Ausnahmen in Android) werden auf dem Server – wie beim iCalendar-Import –
 * zu eigenen Terminen bzw. EXDATEs der Serie.
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

    /** Fehler einzelner Termine, die übersprungen wurden (erste Meldung wird angezeigt) */
    var firstItemError: Throwable? = null
        private set

    suspend fun sync() {
        val remote = apiCall { api.calendars() }
        var local = localCalendars()

        // 1. Lokale Änderungen hochladen (nur für Kalender, die es auf dem Server noch gibt)
        val remoteIds = remote.map { it.id }.toSet()
        for (cal in local) if (cal.serverId in remoteIds) upload(cal)

        // 2. Kalender anlegen, aktualisieren, entfernen
        syncCalendars(remote, local)
        local = localCalendars()

        // 3. Termine herunterladen
        val byServerId = local.associateBy { it.serverId }
        for (cal in remote) {
            val lc = byServerId[cal.id] ?: continue
            download(lc, apiCall { api.calendarEvents(cal.id) })
        }
    }

    // ---------- Kalender ----------

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
                // Sichtbarkeit nur beim Anlegen übernehmen – danach entscheidet die Kalender-App auf dem Gerät
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

    // ---------- Termine lesen ----------

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

    // ---------- Hochladen ----------

    private suspend fun upload(cal: LocalCalendar) {
        val changed = queryEvents(
            "${Events.CALENDAR_ID} = ? AND (${Events.DIRTY} = 1 OR ${Events.DELETED} = 1)", arrayOf(cal.id.toString()),
        )
        // Erst Serien/Einzeltermine, dann Ausnahmen – die brauchen die Server-ID ihrer Serie
        val (exceptions, events) = changed.partition { it.originalId != null || it.originalServerId != null }
        for (ev in events) itemSafe { uploadEvent(cal, ev) }
        // Ausnahmen einer gerade gelöschten Serie sind mit ihr verschwunden
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
                // Auf dem Server inzwischen gelöscht: die Änderung vom Gerät nicht verwerfen, sondern neu anlegen
                if (e.status != 404) throw e
                apiCall { api.createEvent(input) }
            }.also { result.stats.numUpdates++ }
        }
        provider.update(eventUri(ev.id), eventValues(saved, cal.id), null, null)
    }

    /**
     * Ausnahme einer Serie: abgesagt → EXDATE an der Serie; geändert → eigener Termin auf dem Server und
     * EXDATE an der Serie (erst anlegen, dann ausnehmen, damit bei Fehlern eher ein Duplikat als ein Verlust
     * entsteht). Die lokale Ausnahme wird danach entfernt; der Download bringt Serie und neuen Termin.
     */
    private suspend fun uploadException(cal: LocalCalendar, ev: LocalEvent) {
        val masterId = ev.originalServerId ?: ev.originalId?.let(::serverIdOf)
        val instance = ev.originalInstanceTime
        if (ev.deleted || masterId == null || instance == null) {
            // Gelöschte Ausnahme (Vorkommen wiederhergestellt) oder Serie noch nicht auf dem Server
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

    // ---------- Herunterladen ----------

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
                // Lokal geändert, aber nicht hochgeladen (z. B. vom Server abgelehnt): nicht überschreiben
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
            // Ohne Organisator = Kontoinhaber behandeln manche Kalender-Apps Termine als Einladung (nicht bearbeitbar)
            put(Events.ORGANIZER, account.name)
            put(Events.DIRTY, 0)
        }
    }

    // ---------- Fehlerbehandlung ----------

    /** Fehler eines einzelnen Termins überspringen; Netzwerk- und Anmeldefehler brechen die Synchronisierung ab. */
    private suspend fun itemSafe(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            if (e.status == 401 || e.status >= 500) throw e
            Log.w(TAG, "Termin übersprungen: ${e.message}")
            result.stats.numSkippedEntries++
            if (firstItemError == null) firstItemError = e
        }
    }
}

/** Sammelt Operationen und schreibt sie in Blöcken (eine Binder-Transaktion ist auf ~1 MB begrenzt). */
internal class Batch(private val provider: ContentProviderClient, private val maxSize: Int = 200) {
    private val ops = ArrayList<ContentProviderOperation>()

    operator fun plusAssign(op: ContentProviderOperation) {
        ops += op
        if (ops.size >= maxSize) flush()
    }

    val size get() = ops.size

    /** Liefert die Ergebnisse der geschriebenen Operationen (z. B. URIs eingefügter Zeilen). */
    fun flush(): Array<android.content.ContentProviderResult> {
        if (ops.isEmpty()) return emptyArray()
        val res = provider.applyBatch(ArrayList(ops))
        ops.clear()
        return res
    }

    /** Wie [plusAssign], ohne automatisches Schreiben – für zusammengehörige Operationen mit Rückverweisen. */
    fun add(op: ContentProviderOperation) {
        ops += op
    }
}
