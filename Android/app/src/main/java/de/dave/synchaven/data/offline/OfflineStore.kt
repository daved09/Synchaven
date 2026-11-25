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
 * Persistent local cache per account (`<server>|<user>`) under `noBackupFilesDir/offline/`: [OfflineData] as a
 * JSON file plus the contact photos as separate files. In local mode without a server ([LOCAL_ACCOUNT]) this is the
 * only data; it is stored under `filesDir/local/`. Every change is written immediately (coalesced).
 * When the token expires, the cache is kept so that changes made offline are transferred after signing in
 * again; [deleteCurrent] removes it when signing out.
 */
class OfflineStore(context: Context, private val scope: CoroutineScope) {
    private val root = File(context.noBackupFilesDir, "offline")
    /** Local mode data is not a copy but the original – that's why it is included in Android backups */
    private val localDir = File(context.filesDir, "local")

    private val _data = MutableStateFlow(OfflineData())
    /** Current local state; empty as long as no account is open */
    val data: StateFlow<OfflineData> = _data.asStateFlow()

    private val mutex = Mutex()
    private var account: String? = null
    @Volatile private var dir: File? = null
    @Volatile private var ready = CompletableDeferred<Unit>()
    /** Increases with every account switch – prevents responses for the old account from ending up in the new one. */
    private var generation = 0

    private val pendingWrite = MutableStateFlow<Pair<File, OfflineData>?>(null)

    init {
        scope.launch(Dispatchers.IO) {
            pendingWrite.drop(1).conflate().collect { job -> job?.let { (d, data) -> write(d, data) } }
        }
    }

    /** Opens an account's cache (or closes it with `null`). */
    suspend fun open(account: String?) {
        mutex.withLock {
            if (account == this.account && ready.isCompleted) return
            generation++
            this.account = account
            // Waiters check again afterwards and wait for the new account
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

    /** Waits until the signed-in account's cache is loaded. */
    suspend fun awaitReady() {
        while (true) {
            val r = ready
            r.await()
            if (r === ready) return
        }
    }

    val isOpen: Boolean get() = account != null

    /** Key of the open account ([LOCAL_ACCOUNT] in local mode) */
    val currentAccount: String? get() = account

    /** Changes the local state atomically and writes it. Returns the new state. */
    suspend fun update(transform: (OfflineData) -> OfflineData): OfflineData = mutex.withLock {
        val d = dir ?: return@withLock _data.value
        val new = transform(_data.value)
        if (new != _data.value) {
            _data.value = new
            pendingWrite.value = d to new
        }
        new
    }

    /** Like [update], but only if no account switch happened since [token]. */
    suspend fun updateIf(token: Int, transform: (OfflineData) -> OfflineData): OfflineData? =
        if (token != generation) null else update(transform)

    /** Identifier of the open account for [updateIf] */
    val token: Int get() = generation

    // ---------- Photos ----------

    @Serializable
    private data class CachedPhoto(val etag: String, val photo: String)

    /** Cached photo of a contact if it matches the [etag] (data: URL) */
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

    /** Removes photos of contacts that no longer exist. */
    suspend fun prunePhotos(keep: Set<String>) = withContext(Dispatchers.IO) {
        val d = dir?.let { File(it, "photos") } ?: return@withContext
        val names = keep.map(::safeName).toSet()
        d.listFiles()?.forEach { f -> if (f.nameWithoutExtension !in names) f.delete() }
    }

    private fun photoFile(contactId: String): AtomicFile? =
        dir?.let { AtomicFile(File(File(it, "photos").apply { mkdirs() }, safeName(contactId) + ".json")) }

    /** Removes the signed-in account's cache (when signing out) including changes not yet transferred. */
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

    // ---------- Files ----------

    private fun read(d: File): OfflineData {
        val f = AtomicFile(File(d, DATA_FILE))
        if (!f.baseFile.exists()) return OfflineData()
        return try {
            AppJson.decodeFromString<OfflineData>(f.readFully().decodeToString())
        } catch (e: Exception) {
            // Unreadable (e.g. after an incompatible update): reload from the server
            Log.w(TAG, "Offline cache unreadable, discarding it", e)
            OfflineData()
        }
    }

    private fun write(d: File, data: OfflineData) {
        // Deleted after signing out – don't create it again
        if (d != dir) return
        runCatching { writeAtomic(AtomicFile(File(d.apply { mkdirs() }, DATA_FILE)), AppJson.encodeToString(OfflineData.serializer(), data)) }
            .onFailure { Log.w(TAG, "Could not write offline cache", it) }
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
