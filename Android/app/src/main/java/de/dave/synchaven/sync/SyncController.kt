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
    /** Time of the last successful sync (epoch milliseconds) */
    val lastSync: Long? = null,
    /** Error of the last sync, already translated */
    val error: String? = null,
)

data class SyncState(
    /** `null` = no Android account set up */
    val accountName: String? = null,
    val calendar: KindSyncState = KindSyncState(),
    val contacts: KindSyncState = KindSyncState(),
    val intervalMinutes: Int = SyncAccounts.DEFAULT_INTERVAL_MINUTES,
    /** Permissions already requested once after creation (see [SyncController.finishOnboarding]) */
    val onboarded: Boolean = true,
) {
    operator fun get(kind: SyncKind) = if (kind == SyncKind.CALENDAR) calendar else contacts
}

/**
 * Controls the sync with the Android calendar and Android contacts: create/remove the account,
 * turn areas on and off, interval and triggering after changes in the app.
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

    // ---------- Account ----------

    /**
     * Create the account or update the credentials – after sign-in (with password) or from an existing
     * session (token only, which the sync then renews itself).
     */
    fun setupAccount(
        serverUrl: String, userId: String, email: String, password: String?, token: String, expiresAt: String,
    ) {
        val isNew = !hasAccountFor(serverUrl, userId)
        val account = accounts.createOrUpdate(serverUrl, userId, email, password, token, expiresAt)
        if (isNew) {
            // Only turned on once the permissions have been granted (see SyncOnboarding)
            for (k in SyncKind.entries) ContentResolver.setIsSyncable(account, k.authority, 0)
        }
        refresh()
    }

    fun hasAccountFor(serverUrl: String, userId: String): Boolean =
        accounts.account()?.let { accounts.serverUrl(it).orEmpty() == serverUrl && accounts.userId(it) == userId } ?: false

    /** The one-time permission request after creating the account is done. */
    fun finishOnboarding() {
        accounts.account()?.let(accounts::setOnboarded)
        refresh()
    }

    /** When signing out: remove the account including synced calendars and contacts from the device. */
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

    // ---------- Areas ----------

    /** Turns an area on (the permissions must already be granted) and syncs immediately. */
    fun enable(kind: SyncKind) {
        val account = accounts.account() ?: return
        ContentResolver.setIsSyncable(account, kind.authority, 1)
        ContentResolver.setSyncAutomatically(account, kind.authority, true)
        applyInterval(account, kind)
        accounts.recordResult(account, kind, null)
        requestSync(kind, manual = true)
        refresh()
    }

    /** Turns an area off and removes its data from the device (it is kept on the server). */
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
            Log.w("SyncController", "Could not remove local data", e)
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

    /** Interval in minutes, 0 = no periodic sync (only on changes and manually). */
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
     * Triggers the sync of enabled areas – after changes in the app, so they appear immediately
     * in the Android calendar or contacts, or manually ([manual]).
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
