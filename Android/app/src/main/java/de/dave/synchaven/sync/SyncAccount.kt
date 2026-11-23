package de.dave.synchaven.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.provider.CalendarContract
import android.provider.ContactsContract

/** Kontotyp, unter dem Android die synchronisierten Kalender und Kontakte führt (siehe res/xml/authenticator.xml). */
const val ACCOUNT_TYPE = "de.dave.synchaven"

/** Typ des Bearer-Tokens im AccountManager */
const val TOKEN_TYPE = "synchaven"

/** Synchronisierte Bereiche – je ein SyncAdapter. */
enum class SyncKind(val authority: String) {
    CALENDAR(CalendarContract.AUTHORITY),
    CONTACTS(ContactsContract.AUTHORITY);

    companion object {
        fun of(authority: String): SyncKind? = entries.firstOrNull { it.authority == authority }
    }
}

/** Laufzeitberechtigungen je Bereich */
val SyncKind.permissions: Array<String>
    get() = when (this) {
        SyncKind.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        SyncKind.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }

fun Context.hasPermissions(kind: SyncKind) =
    kind.permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Zugriff auf das Android-Konto der App. Das Token liegt im AccountManager und wird vor Ablauf erneuert
 * (`/auth/refresh`), damit die Synchronisierung ohne geöffnete App weiterläuft. Wurde bei der Anmeldung ein
 * Passwort eingegeben, liegt es ebenfalls dort – als Rückfall, falls das Token doch abgelaufen ist.
 */
class SyncAccounts(context: Context) {
    private val am = AccountManager.get(context)

    private object Keys {
        const val SERVER = "server_url"
        const val USER_ID = "user_id"
        const val EMAIL = "email"
        const val INTERVAL = "sync_interval_minutes"
        const val TOKEN_EXPIRES = "token_expires_at"
        const val ONBOARDED = "onboarding_done"
        fun lastSync(kind: SyncKind) = "last_sync_${kind.name.lowercase()}"
        fun lastError(kind: SyncKind) = "last_error_${kind.name.lowercase()}"
    }

    fun account(): Account? = am.getAccountsByType(ACCOUNT_TYPE).firstOrNull()

    fun serverUrl(account: Account): String? = am.getUserData(account, Keys.SERVER)
    fun userId(account: Account): String? = am.getUserData(account, Keys.USER_ID)

    /** E-Mail-Adresse für die Anmeldung (der Kontoname bleibt bei einer Änderung der Adresse gleich). */
    fun email(account: Account): String = am.getUserData(account, Keys.EMAIL) ?: account.name

    fun password(account: Account): String? = am.getPassword(account)
    fun token(account: Account): String? = am.peekAuthToken(account, TOKEN_TYPE)

    fun setToken(account: Account, token: String?) = am.setAuthToken(account, TOKEN_TYPE, token)

    /** Ablauf des Tokens (Epoch-Millisekunden), `null` wenn unbekannt */
    fun tokenExpiresAt(account: Account): Long? = am.getUserData(account, Keys.TOKEN_EXPIRES)?.toLongOrNull()

    /** Speichert Token und Ablauf aus der Antwort von Login/Refresh (`expires_at` im RFC-3339-Format). */
    fun setToken(account: Account, token: String, expiresAt: String) {
        setToken(account, token)
        val ms = runCatching { java.time.Instant.parse(expiresAt).toEpochMilli() }.getOrNull()
        am.setUserData(account, Keys.TOKEN_EXPIRES, ms?.toString())
    }

    /** Wurde nach dem Anlegen des Kontos schon einmal nach den Berechtigungen gefragt? */
    fun onboarded(account: Account): Boolean = am.getUserData(account, Keys.ONBOARDED) == "1"
    fun setOnboarded(account: Account) = am.setUserData(account, Keys.ONBOARDED, "1")
    fun setPassword(account: Account, password: String) = am.setPassword(account, password)
    fun setEmail(account: Account, email: String) = am.setUserData(account, Keys.EMAIL, email)

    /** Intervall der automatischen Synchronisierung in Minuten, 0 = nur bei Änderungen und von Hand */
    fun intervalMinutes(account: Account): Int =
        am.getUserData(account, Keys.INTERVAL)?.toIntOrNull() ?: DEFAULT_INTERVAL_MINUTES

    fun setIntervalMinutes(account: Account, minutes: Int) = am.setUserData(account, Keys.INTERVAL, minutes.toString())

    fun lastSync(account: Account, kind: SyncKind): Long? = am.getUserData(account, Keys.lastSync(kind))?.toLongOrNull()
    fun lastError(account: Account, kind: SyncKind): String? =
        am.getUserData(account, Keys.lastError(kind))?.takeIf { it.isNotBlank() }

    fun recordResult(account: Account, kind: SyncKind, error: String?) {
        if (error == null) am.setUserData(account, Keys.lastSync(kind), System.currentTimeMillis().toString())
        am.setUserData(account, Keys.lastError(kind), error)
    }

    /**
     * Legt das Konto an oder aktualisiert es. Ein Konto eines anderen Benutzers oder Servers wird vorher
     * entfernt – Android löscht dabei dessen Kalender und Kontakte vom Gerät.
     */
    fun createOrUpdate(
        serverUrl: String, userId: String, email: String, password: String?, token: String, expiresAt: String,
    ): Account {
        account()?.let { existing ->
            if (serverUrl(existing) == serverUrl && userId(existing) == userId) {
                if (password != null) setPassword(existing, password)
                setToken(existing, token, expiresAt)
                setEmail(existing, email)
                return existing
            }
            am.removeAccountExplicitly(existing)
        }
        val account = Account(email, ACCOUNT_TYPE)
        val data = android.os.Bundle().apply {
            putString(Keys.SERVER, serverUrl)
            putString(Keys.USER_ID, userId)
            putString(Keys.EMAIL, email)
            putString(Keys.INTERVAL, DEFAULT_INTERVAL_MINUTES.toString())
        }
        check(am.addAccountExplicitly(account, password, data)) { "Konto konnte nicht angelegt werden" }
        setToken(account, token, expiresAt)
        return account
    }

    /** Entfernt das Konto samt aller synchronisierten Kalender und Kontakte auf diesem Gerät. */
    fun remove() {
        am.getAccountsByType(ACCOUNT_TYPE).forEach { am.removeAccountExplicitly(it) }
    }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 60
    }
}
