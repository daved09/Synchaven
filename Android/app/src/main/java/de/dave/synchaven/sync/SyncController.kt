package de.dave.synchaven.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class KindSyncState(
    val enabled: Boolean = false,
    val syncing: Boolean = false,
    /** Zeitpunkt der letzten erfolgreichen Synchronisierung (Epoch-Millisekunden) */
    val lastSync: Long? = null,
    /** Fehler der letzten Synchronisierung, bereits übersetzt */
    val error: String? = null,
)

data class SyncState(
    /** `null` = kein Android-Konto eingerichtet */
    val accountName: String? = null,
    val calendar: KindSyncState = KindSyncState(),
    val contacts: KindSyncState = KindSyncState(),
    val intervalMinutes: Int = SyncAccounts.DEFAULT_INTERVAL_MINUTES,
    /** Nach dem Anlegen schon einmal nach den Berechtigungen gefragt (siehe [SyncController.finishOnboarding]) */
    val onboarded: Boolean = true,
) {
    operator fun get(kind: SyncKind) = if (kind == SyncKind.CALENDAR) calendar else contacts
}

/**
 * Steuert die Synchronisierung mit dem Android-Kalender und den Android-Kontakten: Konto anlegen/entfernen,
 * Bereiche ein- und ausschalten, Intervall und Anstoßen nach Änderungen in der App.
 */
class SyncController(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    val accounts = SyncAccounts(context)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    init {
        val mask = ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE or ContentResolver.SYNC_OBSERVER_TYPE_PENDING or
            ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS
        ContentResolver.addStatusChangeListener(mask) { refresh() }
    }

    fun refresh() {
        _state.value = read()
    }

    private fun read(): SyncState {
        val account = accounts.account() ?: return SyncState()
        fun kind(k: SyncKind) = KindSyncState(
            enabled = isEnabled(account, k),
            syncing = ContentResolver.isSyncActive(account, k.authority),
            lastSync = accounts.lastSync(account, k),
            error = accounts.lastError(account, k),
        )
        return SyncState(
            accountName = account.name,
            calendar = kind(SyncKind.CALENDAR),
            contacts = kind(SyncKind.CONTACTS),
            intervalMinutes = accounts.intervalMinutes(account),
            onboarded = accounts.onboarded(account),
        )
    }

    private fun isEnabled(account: Account, kind: SyncKind) =
        ContentResolver.getIsSyncable(account, kind.authority) > 0 && ContentResolver.getSyncAutomatically(account, kind.authority)

    // ---------- Konto ----------

    /**
     * Konto anlegen bzw. Zugangsdaten aktualisieren – nach der Anmeldung (mit Passwort) oder aus einer bestehenden
     * Sitzung (nur Token, das die Synchronisierung danach selbst erneuert).
     */
    fun setupAccount(
        serverUrl: String, userId: String, email: String, password: String?, token: String, expiresAt: String,
    ) {
        val isNew = !hasAccountFor(serverUrl, userId)
        val account = accounts.createOrUpdate(serverUrl, userId, email, password, token, expiresAt)
        if (isNew) {
            // Eingeschaltet wird erst, wenn die Berechtigungen erteilt sind (siehe SyncOnboarding)
            for (k in SyncKind.entries) ContentResolver.setIsSyncable(account, k.authority, 0)
        }
        refresh()
    }

    fun hasAccountFor(serverUrl: String, userId: String): Boolean =
        accounts.account()?.let { accounts.serverUrl(it).orEmpty() == serverUrl && accounts.userId(it) == userId } ?: false

    /** Die einmalige Abfrage der Berechtigungen nach dem Anlegen ist erledigt. */
    fun finishOnboarding() {
        accounts.account()?.let(accounts::setOnboarded)
        refresh()
    }

    /** Beim Abmelden: Konto samt synchronisierter Kalender und Kontakte vom Gerät entfernen. */
    fun removeAccount() {
        accounts.remove()
        refresh()
    }

    fun updatePassword(password: String) {
        accounts.account()?.let { accounts.setPassword(it, password) }
    }

    fun updateEmail(email: String) {
        accounts.account()?.let { accounts.setEmail(it, email) }
    }

    // ---------- Bereiche ----------

    /** Schaltet einen Bereich ein (die Berechtigungen müssen bereits erteilt sein) und synchronisiert sofort. */
    fun enable(kind: SyncKind) {
        val account = accounts.account() ?: return
        ContentResolver.setIsSyncable(account, kind.authority, 1)
        ContentResolver.setSyncAutomatically(account, kind.authority, true)
        applyInterval(account, kind)
        accounts.recordResult(account, kind, null)
        requestSync(kind, manual = true)
        refresh()
    }

    /** Schaltet einen Bereich aus und entfernt dessen Daten vom Gerät (auf dem Server bleiben sie erhalten). */
    suspend fun disable(kind: SyncKind) = withContext(Dispatchers.IO) {
        val account = accounts.account() ?: return@withContext
        ContentResolver.cancelSync(account, kind.authority)
        ContentResolver.setSyncAutomatically(account, kind.authority, false)
        ContentResolver.getPeriodicSyncs(account, kind.authority).forEach {
            ContentResolver.removePeriodicSync(account, kind.authority, it.extras)
        }
        ContentResolver.setIsSyncable(account, kind.authority, 0)
        try {
            removeLocalData(account, kind)
        } catch (e: SecurityException) {
            Log.w("SyncController", "Lokale Daten konnten nicht entfernt werden", e)
        }
        refresh()
    }

    private fun removeLocalData(account: Account, kind: SyncKind) {
        when (kind) {
            SyncKind.CALENDAR -> {
                val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
                    .build()
                resolver.delete(
                    uri, "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                    arrayOf(account.name, account.type),
                )
            }
            SyncKind.CONTACTS -> {
                val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                    .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                    .build()
                resolver.delete(
                    uri, "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                    arrayOf(account.name, account.type),
                )
            }
        }
    }

    /** Intervall in Minuten, 0 = keine regelmäßige Synchronisierung (nur bei Änderungen und von Hand). */
    fun setInterval(minutes: Int) {
        val account = accounts.account() ?: return
        accounts.setIntervalMinutes(account, minutes)
        for (k in SyncKind.entries) if (isEnabled(account, k)) applyInterval(account, k)
        refresh()
    }

    private fun applyInterval(account: Account, kind: SyncKind) {
        ContentResolver.getPeriodicSyncs(account, kind.authority).forEach {
            ContentResolver.removePeriodicSync(account, kind.authority, it.extras)
        }
        val minutes = accounts.intervalMinutes(account)
        if (minutes > 0) ContentResolver.addPeriodicSync(account, kind.authority, Bundle.EMPTY, minutes * 60L)
    }

    /**
     * Stößt die Synchronisierung eingeschalteter Bereiche an – nach Änderungen in der App, damit sie sofort
     * im Android-Kalender bzw. in den Kontakten erscheinen, oder von Hand ([manual]).
     */
    fun requestSync(vararg kinds: SyncKind, manual: Boolean = false) {
        val account = accounts.account() ?: return
        for (k in kinds.ifEmpty { SyncKind.entries.toTypedArray() }) {
            if (!isEnabled(account, k)) continue
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                if (manual) putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            }
            ContentResolver.requestSync(account, k.authority, extras)
        }
    }
}
