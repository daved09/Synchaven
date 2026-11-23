package de.dave.synchaven.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import de.dave.synchaven.UiText
import de.dave.synchaven.data.api.ApiException
import de.dave.synchaven.data.api.InvalidServerUrlException
import de.dave.synchaven.data.api.NoServerConfiguredException
import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.api.apiCall
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.CompletedRequest
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.model.ExdateRequest
import de.dave.synchaven.data.model.UpdateMeRequest
import de.dave.synchaven.data.store.SessionStore
import de.dave.synchaven.data.userMessage
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Zustand des Offline-Betriebs für die Oberfläche */
data class OfflineStatus(
    /** Der Server war zuletzt nicht erreichbar – angezeigt werden die lokal gespeicherten Daten */
    val offline: Boolean = false,
    /** Grund der letzten fehlgeschlagenen Verbindung */
    val reason: UiText? = null,
    /** Noch nicht übertragene Änderungen */
    val pending: Int = 0,
    val syncing: Boolean = false,
    /** Letzter vollständiger Abgleich */
    val lastSync: Instant? = null,
    /** Vom Server abgelehnte und daher verworfene Änderung (zuletzt) */
    val rejected: UiText? = null,
)

/** Server nicht erreichbar (Netzwerkfehler oder Gateway-Fehler eines vorgeschalteten Proxys) */
fun Throwable.isOfflineError(): Boolean = when (this) {
    is NoServerConfiguredException, is InvalidServerUrlException -> false
    is IOException -> true
    is ApiException -> status in 502..504
    else -> false
}

/**
 * Hält den [OfflineStore] mit dem Server abgeglichen: überträgt offline gemachte Änderungen in Reihenfolge, lädt
 * danach alle Kalender, Termine, Adressbücher und Kontakte neu und merkt sich, ob der Server erreichbar ist.
 * Abgeglichen wird beim Öffnen des Kontos, sobald das Gerät wieder ein Netz hat, beim Zurückkehren in die App und
 * auf Wunsch.
 */
class OfflineSync(
    context: Context,
    private val api: SyncHavenApi,
    private val store: OfflineStore,
    private val sessionStore: SessionStore,
    private val scope: CoroutineScope,
    /** Termine auf dem Server geändert (beim Übertragen) */
    private val onEventsChanged: () -> Unit,
    /** Kontakte auf dem Server geändert (beim Übertragen) */
    private val onContactsChanged: () -> Unit,
) {
    /** `null` = unbekannt (z. B. nach einem Netzwechsel) */
    private val reachable = MutableStateFlow<Boolean?>(null)

    /** Lokaler Modus: Es gibt keinen Server – die „API“ schreibt direkt in den [store], abzugleichen ist nichts. */
    private val local: Boolean get() = sessionStore.current?.local == true
    private val reason = MutableStateFlow<UiText?>(null)
    private val syncing = MutableStateFlow(false)
    private val rejected = MutableStateFlow<UiText?>(null)

    /** Schützt die Warteschlange: direkte Schreibzugriffe und das Übertragen laufen nacheinander. */
    private val writeMutex = Mutex()
    /** Der erste Eintrag der Warteschlange wird gerade übertragen und darf nicht mehr zusammengefasst werden. */
    private var inFlight = false
    private val requests = Channel<Unit>(Channel.CONFLATED)

    val status: StateFlow<OfflineStatus> = combine(
        store.data, reachable, reason, syncing, rejected,
    ) { data, isReachable, why, isSyncing, rej ->
        OfflineStatus(
            offline = isReachable == false,
            reason = why.takeIf { isReachable == false },
            pending = data.outbox.size,
            syncing = isSyncing,
            lastSync = data.lastSync?.let { runCatching { Instant.parse(it) }.getOrNull() },
            rejected = rej,
        )
    }.stateIn(scope, SharingStarted.Eagerly, OfflineStatus())

    init {
        scope.launch {
            for (r in requests) runCatching { syncOnce() }.onFailure { Log.w(TAG, "Abgleich fehlgeschlagen", it) }
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Neues Netz: Server erneut versuchen und ausstehende Änderungen übertragen
                    reachable.value = null
                    requestSync()
                }

                override fun onLost(network: Network) {
                    if (!local) markOffline(null)
                }
            })
        }
    }

    /** Stößt einen Abgleich an (mehrere Anfragen werden zusammengefasst). */
    fun requestSync() {
        requests.trySend(Unit)
    }

    /** Abgleich, wenn der letzte länger als [maxAge] her ist, Änderungen ausstehen oder der Server nicht erreichbar war. */
    fun syncIfNeeded(maxAge: Duration = Duration.ofMinutes(5)) {
        val s = status.value
        val stale = s.lastSync == null || s.lastSync.plus(maxAge) < Instant.now()
        if (stale || s.pending > 0 || s.offline) requestSync()
    }

    fun dismissRejected() {
        rejected.value = null
    }

    /** Anderes Konto oder lokaler Modus: Erreichbarkeit und Meldungen des vorigen gelten nicht mehr. */
    fun reset() {
        reachable.value = null
        reason.value = null
        rejected.value = null
    }

    // ---------- Zugriffe der Repositories ----------

    /**
     * Schreibt beim Server ([online]) oder – wenn er nicht erreichbar ist oder noch Änderungen ausstehen – lokal:
     * [op] wird angewendet, in die Warteschlange gestellt und [offline] liefert das Ergebnis aus dem lokalen Stand.
     */
    suspend fun <T> write(op: PendingOp, online: suspend () -> T, offline: (OfflineData) -> T): T {
        store.awaitReady()
        val result = writeMutex.withLock {
            if (local || (store.data.value.outbox.isEmpty() && reachable.value != false)) {
                try {
                    return@withLock online().also { markReachable() }
                } catch (e: Throwable) {
                    if (!e.isOfflineError()) throw e
                    markOffline(e)
                }
            }
            val new = store.update { d ->
                val resolved = op.resolve(d.idMap)
                d.apply(resolved).copy(outbox = d.outbox.enqueue(resolved, frozen = if (inFlight) 1 else 0)).withPending()
            }
            offline(new)
        }
        if (store.data.value.outbox.isNotEmpty()) requestSync()
        return result
    }

    /** Nur beim Server möglich (z. B. Import); merkt sich, ob er erreichbar war. */
    suspend fun <T> online(block: suspend () -> T): T = try {
        block().also { markReachable() }
    } catch (e: Throwable) {
        if (e.isOfflineError()) markOffline(e)
        throw e
    }

    /**
     * Lesen mit Rückfall auf den Cache: vom Server ([fetch]), bei fehlender Verbindung aus [cached]. Ist der Server
     * bekanntermaßen nicht erreichbar oder [preferCache] gesetzt, wird gar nicht erst gefragt.
     */
    suspend fun <T> read(preferCache: Boolean = false, cached: (OfflineData) -> T?, fetch: suspend () -> T): T {
        store.awaitReady()
        if (!local && (preferCache || reachable.value == false)) cached(store.data.value)?.let { return it }
        return try {
            fetch().also { markReachable() }
        } catch (e: Throwable) {
            if (!e.isOfflineError()) throw e
            markOffline(e)
            cached(store.data.value) ?: throw e
        }
    }

    /** Hat [id] noch nicht übertragene Änderungen? Dann ist der lokale Stand neuer als der des Servers. */
    fun hasPending(id: String): Boolean = store.data.value.let { d -> d.outbox.any { it.targetId == d.resolve(id) } }

    val hasAnyPending: Boolean get() = store.data.value.outbox.isNotEmpty()

    private fun markReachable() {
        if (reachable.value != true) {
            reachable.value = true
            reason.value = null
            // Wieder erreichbar: ausstehende Änderungen übertragen
            if (!syncing.value && store.data.value.outbox.isNotEmpty()) requestSync()
        }
    }

    private fun markOffline(e: Throwable?) {
        reachable.value = false
        reason.value = e?.userMessage()
    }

    // ---------- Abgleich ----------

    private suspend fun syncOnce() {
        store.awaitReady()
        if (!store.isOpen || sessionStore.current == null || local) return
        syncing.value = true
        try {
            val sent = flush()
            if (sent.complete) {
                try {
                    refreshAll()
                    markReachable()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    if (e.isOfflineError()) markOffline(e) else Log.w(TAG, "Neu laden fehlgeschlagen", e)
                }
            }
            if (sent.events) onEventsChanged()
            if (sent.contacts) onContactsChanged()
        } finally {
            syncing.value = false
        }
    }

    private class FlushResult(var complete: Boolean = true, var events: Boolean = false, var contacts: Boolean = false)

    /** Überträgt die Warteschlange in Reihenfolge. Bricht ab, sobald der Server nicht erreichbar ist. */
    private suspend fun flush(): FlushResult {
        val result = FlushResult()
        while (true) {
            val token = store.token
            val op = writeMutex.withLock {
                store.data.value.outbox.firstOrNull()?.also { inFlight = true }
            } ?: return result
            val outcome: (OfflineData) -> OfflineData = try {
                send(op).also { markReachable() }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    writeMutex.withLock { inFlight = false }
                    throw e
                }
                val retryLater = e.isOfflineError() ||
                    (e is ApiException && (e.status == 401 || e.status == 408 || e.status == 429 || e.status >= 500))
                if (retryLater) {
                    if (e.isOfflineError()) markOffline(e)
                    writeMutex.withLock { inFlight = false }
                    result.complete = false
                    return result
                }
                // Vom Server abgelehnt (z. B. inzwischen gelöscht oder ungültig): verwerfen, sonst blockiert sie alles
                Log.w(TAG, "Änderung verworfen: $op", e)
                rejected.value = e.userMessage()
                val keepAsIs: (OfflineData) -> OfflineData = { it }
                keepAsIs
            }
            writeMutex.withLock {
                inFlight = false
                store.updateIf(token) { d ->
                    val rest = d.outbox.drop(1)
                    outcome(d.copy(outbox = rest)).withPending()
                }
            }
            when (op) {
                is PendingOp.CreateEvent, is PendingOp.UpdateEvent, is PendingOp.DeleteEvent, is PendingOp.AddExdate ->
                    result.events = true
                is PendingOp.CreateContact, is PendingOp.UpdateContact, is PendingOp.DeleteContact -> {
                    // Geburtstage hängen an den Kontakten
                    result.contacts = true
                    result.events = true
                }
                is PendingOp.UpdateCalendar -> result.events = true
                // Aufgaben zeigen die Ansichten direkt aus dem Store – nichts weiter anzustoßen
                is PendingOp.CreateTask, is PendingOp.UpdateTask, is PendingOp.SetTaskCompleted,
                is PendingOp.DeleteTask, is PendingOp.UpdateTaskList, is PendingOp.SetDefaultView -> Unit
            }
            if (token != store.token) return result.also { it.complete = false }
        }
    }

    /** Sendet eine Änderung und liefert, wie der lokale Stand danach anzupassen ist. */
    private suspend fun send(op: PendingOp): (OfflineData) -> OfflineData = when (op) {
        is PendingOp.CreateEvent -> {
            val ev = apiCall { api.createEvent(op.input) }
            val f: (OfflineData) -> OfflineData = { d ->
                d.copy(
                    events = d.events.orEmpty().filterNot { it.id == op.localId } + ev,
                    outbox = d.outbox.remap(op.localId, ev.id),
                    idMap = d.idMap + (op.localId to ev.id),
                )
            }
            f
        }
        is PendingOp.UpdateEvent -> {
            val ev = apiCall { api.updateEvent(op.id, op.input) }
            replaceEvent(ev)
        }
        is PendingOp.AddExdate -> {
            val ev = apiCall { api.addExdate(op.id, ExdateRequest(op.occurrenceStart)) }
            replaceEvent(ev)
        }
        is PendingOp.DeleteEvent -> {
            ignoreNotFound { apiCall { api.deleteEvent(op.id) } }
            val f: (OfflineData) -> OfflineData = { d -> d.copy(events = d.events?.filterNot { it.id == op.id }) }
            f
        }
        is PendingOp.CreateContact -> {
            val c = apiCall { api.createContact(op.input) }
            store.savePhoto(c.id, c.etag, c.photo ?: op.input.photo)
            val f: (OfflineData) -> OfflineData = { d ->
                d.copy(
                    contacts = d.contacts.orEmpty().filterNot { it.id == op.localId } + c.copy(photo = null),
                    outbox = d.outbox.remap(op.localId, c.id),
                    idMap = d.idMap + (op.localId to c.id),
                )
            }
            f
        }
        is PendingOp.UpdateContact -> {
            val c = apiCall { api.updateContact(op.id, op.input) }
            if (c.photo != null || op.input.photo != null) store.savePhoto(c.id, c.etag, c.photo ?: op.input.photo)
            val f: (OfflineData) -> OfflineData = { d ->
                d.copy(contacts = d.contacts?.map { if (it.id == c.id) c.copy(photo = null) else it })
            }
            f
        }
        is PendingOp.DeleteContact -> {
            ignoreNotFound { apiCall { api.deleteContact(op.id) } }
            store.savePhoto(op.id, "", null)
            val f: (OfflineData) -> OfflineData = { d -> d.copy(contacts = d.contacts?.filterNot { it.id == op.id }) }
            f
        }
        is PendingOp.UpdateCalendar -> {
            val cal = apiCall { api.updateCalendar(op.id, op.input) }
            val f: (OfflineData) -> OfflineData = { d -> d.copy(calendars = d.calendars?.map { if (it.id == cal.id) cal else it }) }
            f
        }
        is PendingOp.CreateTask -> {
            val t = apiCall { api.createTask(op.input) }
            val f: (OfflineData) -> OfflineData = { d ->
                d.copy(
                    tasks = d.tasks.orEmpty().filterNot { it.id == op.localId } + t,
                    outbox = d.outbox.remap(op.localId, t.id),
                    idMap = d.idMap + (op.localId to t.id),
                )
            }
            f
        }
        is PendingOp.UpdateTask -> replaceTask(apiCall { api.updateTask(op.id, op.input) })
        is PendingOp.SetTaskCompleted -> replaceTask(apiCall { api.setTaskCompleted(op.id, CompletedRequest(op.completed)) })
        is PendingOp.DeleteTask -> {
            ignoreNotFound { apiCall { api.deleteTask(op.id) } }
            val f: (OfflineData) -> OfflineData = { d -> d.copy(tasks = d.tasks?.filterNot { it.id == op.id }) }
            f
        }
        is PendingOp.UpdateTaskList -> {
            val list = apiCall { api.updateTaskList(op.id, op.input) }
            val f: (OfflineData) -> OfflineData = { d -> d.copy(taskLists = d.taskLists?.map { if (it.id == list.id) list else it }) }
            f
        }
        is PendingOp.SetDefaultView -> {
            val user = apiCall { api.updateMe(UpdateMeRequest(defaultView = op.view)) }
            sessionStore.updateUser(user)
            val f: (OfflineData) -> OfflineData = { it }
            f
        }
    }

    private fun replaceEvent(ev: CalEvent): (OfflineData) -> OfflineData = { d ->
        d.copy(events = d.events?.map { if (it.id == ev.id) ev else it })
    }

    private fun replaceTask(t: Task): (OfflineData) -> OfflineData = { d ->
        d.copy(tasks = d.tasks?.map { if (it.id == t.id) t else it })
    }

    private suspend fun ignoreNotFound(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            if (e.status != 404) throw e
        }
    }

    /** Lädt alle Daten des Kontos neu und rechnet ausstehende Änderungen wieder ein. */
    suspend fun refreshAll() {
        val token = store.token
        coroutineScope {
            val calendars = apiCall { api.calendars() }
            val eventsJob = async {
                try {
                    calendars.map { c -> async { apiCall { api.calendarEvents(c.id) } } }.awaitAll().flatten()
                } catch (e: ApiException) {
                    // Älterer Server ohne Serien-Endpunkt: Termine nur online
                    if (e.status == 404) null else throw e
                }
            }
            val booksJob = async { apiCall { api.addressBooks() } }
            val contactsJob = async { apiCall { api.contacts() } }
            val tasksJob = async { fetchTasks(api) }
            val events = eventsJob.await()
            val books = booksJob.await()
            val contacts = contactsJob.await()
            val tasks = tasksJob.await()
            store.updateIf(token) { d ->
                d.copy(
                    calendars = calendars,
                    events = events ?: d.events,
                    addressBooks = books,
                    contacts = contacts,
                    taskLists = tasks?.first ?: d.taskLists,
                    tasks = tasks?.second ?: d.tasks,
                    lastSync = Instant.now().toString(),
                ).withPending()
            }
            store.prunePhotos(contacts.map { it.id }.toSet() + store.data.value.contacts.orEmpty().map { it.id })
        }
    }

    /** Ersetzt die Daten einer Liste durch den Stand des Servers (ausstehende Änderungen bleiben eingerechnet). */
    suspend fun mergeServer(transform: (OfflineData) -> OfflineData) {
        store.awaitReady()
        store.update { transform(it).withPending() }
    }

    private companion object {
        const val TAG = "OfflineSync"
    }
}

/**
 * Aufgabenlisten und alle Aufgaben vom Server; `null` bei einem älteren Server ohne Aufgaben (404).
 * Die Listen zuerst: `GET /task-lists` legt bei Bedarf „Meine Aufgaben“ an.
 */
suspend fun fetchTasks(api: SyncHavenApi): Pair<List<TaskList>, List<Task>>? = try {
    val lists = apiCall { api.taskLists() }
    lists to apiCall { api.tasks() }
} catch (e: ApiException) {
    if (e.status == 404) null else throw e
}

/** Ersetzt bereits übertragene vorläufige IDs durch die des Servers. */
private fun PendingOp.resolve(idMap: Map<String, String>): PendingOp {
    val server = idMap[targetId] ?: return this
    return listOf(this).remap(targetId, server).first()
}
