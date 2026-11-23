package de.dave.synchaven.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import de.dave.synchaven.MainActivity
import de.dave.synchaven.R
import de.dave.synchaven.data.api.ApiException
import de.dave.synchaven.data.userMessage
import java.io.IOException
import kotlinx.coroutines.runBlocking

private const val TAG = "SyncAdapter"

/**
 * SyncAdapter für Kalender bzw. Kontakte. Android ruft ihn periodisch, nach Änderungen in der Kalender- oder
 * Kontakte-App (Upload) und auf Anforderung der App auf. Ergebnis und Fehler landen am Konto (siehe [SyncAccounts]).
 */
private class SyncAdapter(context: Context, private val kind: SyncKind) :
    AbstractThreadedSyncAdapter(context, true, false) {

    override fun onPerformSync(
        account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult,
    ) {
        if (account.type != ACCOUNT_TYPE) return
        // Android fragt so nach, ob das Konto synchronisiert werden soll – das entscheidet die App selbst
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE)) return
        val accounts = SyncAccounts(context)
        val res = context.resources
        val error: String? = try {
            val api = syncApi(accounts, account)
            val itemError = runBlocking {
                when (kind) {
                    SyncKind.CALENDAR -> CalendarSyncer(account, provider, api, syncResult, res.getString(R.string.sync_untitled))
                        .also { it.sync() }.firstItemError
                    SyncKind.CONTACTS -> ContactsSyncer(account, provider, api, syncResult, res)
                        .also { it.sync() }.firstItemError
                }
            }
            itemError?.let {
                res.getQuantityString(
                    R.plurals.sync_items_skipped, syncResult.stats.numSkippedEntries.toInt(),
                    syncResult.stats.numSkippedEntries.toInt(), it.userMessage().resolve(res),
                )
            }
        } catch (e: Exception) {
            if (e.isCancellation()) {
                // Android hat die Synchronisierung abgebrochen (z. B. weil sie gleichzeitig neu angestoßen wurde) –
                // kein Fehler, der SyncManager holt sie nach
                Log.i(TAG, "${kind.name}: abgebrochen")
                return
            }
            errorMessage(e, syncResult)
        }
        Log.i(TAG, "${kind.name}: $syncResult${error?.let { " – $it" }.orEmpty()}")
        // Das Konto kann während der Synchronisierung entfernt worden sein
        if (accounts.account() == account) accounts.recordResult(account, kind, error)
    }

    private fun errorMessage(e: Exception, syncResult: SyncResult): String {
        val res = context.resources
        return when (e) {
            is SyncAuthException -> {
                syncResult.stats.numAuthExceptions++
                res.getString(R.string.sync_error_auth)
            }
            is SecurityException -> {
                Log.w(TAG, "Berechtigung fehlt", e)
                syncResult.stats.numAuthExceptions++
                res.getString(R.string.sync_error_permission)
            }
            is IOException -> {
                syncResult.stats.numIoExceptions++
                e.userMessage().resolve(res)
            }
            is ApiException -> {
                if (e.status >= 500) syncResult.stats.numIoExceptions++ else syncResult.stats.numParseExceptions++
                e.userMessage().resolve(res)
            }
            else -> {
                Log.e(TAG, "Synchronisierung fehlgeschlagen", e)
                syncResult.databaseError = true
                e.userMessage().resolve(res)
            }
        }
    }
}

/** Abbruch durch den SyncManager: der Sync-Thread wurde unterbrochen. */
private fun Throwable.isCancellation(): Boolean =
    Thread.currentThread().isInterrupted || generateSequence(this) { it.cause }.any {
        it is InterruptedException || it is java.io.InterruptedIOException && it !is java.net.SocketTimeoutException ||
            it is kotlinx.coroutines.CancellationException
    }

class CalendarSyncService : Service() {
    private val adapter by lazy { SyncAdapter(applicationContext, SyncKind.CALENDAR) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

class ContactsSyncService : Service() {
    private val adapter by lazy { SyncAdapter(applicationContext, SyncKind.CONTACTS) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

/**
 * Minimaler Authenticator: Konten entstehen bei der Anmeldung in der App. „Konto hinzufügen“ in den
 * Android-Einstellungen öffnet deshalb die App.
 */
private class Authenticator(private val context: Context) : AbstractAccountAuthenticator(context) {
    override fun addAccount(
        response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
        requiredFeatures: Array<out String>?, options: Bundle?,
    ): Bundle = Bundle().apply {
        putParcelable(
            AccountManager.KEY_INTENT,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETUP_SYNC, true),
        )
    }

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?,
    ): Bundle = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, "unsupported")
    }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null
    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null
    override fun getAuthTokenLabel(authTokenType: String?): String? = null
    override fun updateCredentials(
        response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?,
    ): Bundle? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?,
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}

class AuthenticatorService : Service() {
    private val authenticator by lazy { Authenticator(this) }
    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}
