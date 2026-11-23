package de.dave.synchaven.data.offline

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import de.dave.synchaven.data.api.AppJson
import de.dave.synchaven.data.local.LOCAL_ACCOUNT
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Persistenter lokaler Cache je Konto (`<server>|<benutzer>`) unter `noBackupFilesDir/offline/`: [OfflineData] als
 * JSON-Datei plus die Kontaktfotos als eigene Dateien. Im lokalen Modus ohne Server ([LOCAL_ACCOUNT]) sind das die
 * einzigen Daten; sie liegen unter `filesDir/local/`. Jede Änderung wird sofort (zusammengefasst) geschrieben.
 * Beim Ablauf des Tokens bleibt der Cache erhalten, damit offline gemachte Änderungen nach der erneuten
 * Anmeldung übertragen werden; [deleteCurrent] beim Abmelden entfernt ihn.
 */
class OfflineStore(context: Context, private val scope: CoroutineScope) {
    private val root = File(context.noBackupFilesDir, "offline")
    /** Daten des lokalen Modus sind keine Kopie, sondern das Original – sie liegen deshalb in den Android-Sicherungen */
    private val localDir = File(context.filesDir, "local")

    private val _data = MutableStateFlow(OfflineData())
    /** Aktueller lokaler Stand; leer, solange kein Konto geöffnet ist */
    val data: StateFlow<OfflineData> = _data.asStateFlow()

    private val mutex = Mutex()
    private var account: String? = null
    @Volatile private var dir: File? = null
    @Volatile private var ready = CompletableDeferred<Unit>()
    /** Erhöht sich bei jedem Kontowechsel – verhindert, dass Antworten für das alte Konto im neuen landen. */
    private var generation = 0

    private val pendingWrite = MutableStateFlow<Pair<File, OfflineData>?>(null)

    init {
        scope.launch(Dispatchers.IO) {
            pendingWrite.drop(1).conflate().collect { job -> job?.let { (d, data) -> write(d, data) } }
        }
    }

    /** Öffnet den Cache eines Kontos (bzw. schließt ihn mit `null`). */
    suspend fun open(account: String?) {
        mutex.withLock {
            if (account == this.account && ready.isCompleted) return
            generation++
            this.account = account
            // Wartende prüfen danach erneut und warten auf das neue Konto
            val old = ready
            ready = CompletableDeferred()
            old.complete(Unit)
            _data.value = OfflineData()
            val d = account?.let { if (it == LOCAL_ACCOUNT) localDir else File(root, hash(it)) }
            dir = d
            if (d != null) _data.value = withContext(Dispatchers.IO) { read(d) }
            ready.complete(Unit)
        }
    }

    /** Wartet, bis der Cache des angemeldeten Kontos geladen ist. */
    suspend fun awaitReady() {
        while (true) {
            val r = ready
            r.await()
            if (r === ready) return
        }
    }

    val isOpen: Boolean get() = account != null

    /** Schlüssel des geöffneten Kontos ([LOCAL_ACCOUNT] im lokalen Modus) */
    val currentAccount: String? get() = account

    /** Ändert den lokalen Stand atomar und schreibt ihn. Liefert den neuen Stand. */
    suspend fun update(transform: (OfflineData) -> OfflineData): OfflineData = mutex.withLock {
        val d = dir ?: return@withLock _data.value
        val new = transform(_data.value)
        if (new != _data.value) {
            _data.value = new
            pendingWrite.value = d to new
        }
        new
    }

    /** Wie [update], aber nur, wenn seit [token] kein Kontowechsel stattfand. */
    suspend fun updateIf(token: Int, transform: (OfflineData) -> OfflineData): OfflineData? =
        if (token != generation) null else update(transform)

    /** Kennung des geöffneten Kontos für [updateIf] */
    val token: Int get() = generation

    // ---------- Fotos ----------

    @Serializable
    private data class CachedPhoto(val etag: String, val photo: String)

    /** Zwischengespeichertes Foto eines Kontakts, wenn es zum [etag] passt (data:-URL) */
    suspend fun photo(contactId: String, etag: String): String? = withContext(Dispatchers.IO) {
        val f = photoFile(contactId) ?: return@withContext null
        if (!f.baseFile.exists()) return@withContext null
        runCatching { AppJson.decodeFromString<CachedPhoto>(f.readFully().decodeToString()) }.getOrNull()
            ?.takeIf { it.etag == etag }?.photo
    }

    suspend fun savePhoto(contactId: String, etag: String, photo: String?) = withContext(Dispatchers.IO) {
        val f = photoFile(contactId) ?: return@withContext
        if (photo.isNullOrEmpty()) {
            f.delete()
            return@withContext
        }
        writeAtomic(f, AppJson.encodeToString(CachedPhoto.serializer(), CachedPhoto(etag, photo)))
    }

    /** Entfernt Fotos von Kontakten, die es nicht mehr gibt. */
    suspend fun prunePhotos(keep: Set<String>) = withContext(Dispatchers.IO) {
        val d = dir?.let { File(it, "photos") } ?: return@withContext
        val names = keep.map(::safeName).toSet()
        d.listFiles()?.forEach { f -> if (f.nameWithoutExtension !in names) f.delete() }
    }

    private fun photoFile(contactId: String): AtomicFile? =
        dir?.let { AtomicFile(File(File(it, "photos").apply { mkdirs() }, safeName(contactId) + ".json")) }

    /** Entfernt den Cache des angemeldeten Kontos (beim Abmelden) samt nicht übertragener Änderungen. */
    suspend fun deleteCurrent() {
        mutex.withLock {
            val d = dir ?: return
            generation++
            dir = null
            account = null
            pendingWrite.value = null
            _data.value = OfflineData()
            withContext(Dispatchers.IO) { d.deleteRecursively() }
        }
    }

    // ---------- Dateien ----------

    private fun read(d: File): OfflineData {
        val f = AtomicFile(File(d, DATA_FILE))
        if (!f.baseFile.exists()) return OfflineData()
        return try {
            AppJson.decodeFromString<OfflineData>(f.readFully().decodeToString())
        } catch (e: Exception) {
            // Unlesbar (z. B. nach einem inkompatiblen Update): neu vom Server laden
            Log.w(TAG, "Offline-Cache unlesbar, wird verworfen", e)
            OfflineData()
        }
    }

    private fun write(d: File, data: OfflineData) {
        // Nach dem Abmelden gelöscht – nicht wieder anlegen
        if (d != dir) return
        runCatching { writeAtomic(AtomicFile(File(d.apply { mkdirs() }, DATA_FILE)), AppJson.encodeToString(OfflineData.serializer(), data)) }
            .onFailure { Log.w(TAG, "Offline-Cache konnte nicht geschrieben werden", it) }
    }

    private fun writeAtomic(f: AtomicFile, text: String) {
        val out = f.startWrite()
        try {
            out.write(text.encodeToByteArray())
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val TAG = "OfflineStore"
        const val DATA_FILE = "data.json"

        fun hash(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

        fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
