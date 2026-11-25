package de.dave.synchaven.sync

import android.accounts.Account
import de.dave.synchaven.data.api.AppJson
import de.dave.synchaven.data.api.InvalidServerUrlException
import de.dave.synchaven.data.api.LanguageInterceptor
import de.dave.synchaven.data.api.NetworkFactory
import de.dave.synchaven.data.api.NoServerConfiguredException
import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.api.rebaseUrl
import de.dave.synchaven.data.model.AuthResponse
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The sync's token is no longer accepted (expired, password changed, signed out everywhere). */
class SyncAuthException : IOException("authentication failed")

/** The token is renewed if it expires in less than this time. */
private const val REFRESH_BEFORE_MS = 7L * 24 * 3600 * 1000

/**
 * Attaches the Android account's token and renews it in time before it expires. If it is rejected anyway (401 –
 * expired, or revoked on the server), the sync stops with [SyncAuthException] until the user signs in again in the
 * app: no password is stored on the device, so ending the sessions on the server really ends this one as well.
 */
private class AccountInterceptor(
    private val accounts: SyncAccounts,
    private val account: Account,
    private val loginClient: OkHttpClient,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val server = accounts.serverUrl(account) ?: throw NoServerConfiguredException()
        val base = server.toHttpUrlOrNull() ?: throw InvalidServerUrlException(server)
        val original = chain.request()
        val url = rebaseUrl(base, original.url)
        fun request(token: String) = original.newBuilder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .build()

        val response = chain.proceed(request(currentToken()))
        if (response.code != 401) return response
        response.close()
        throw SyncAuthException()
    }

    @Synchronized
    private fun currentToken(): String {
        val server = accounts.serverUrl(account) ?: throw NoServerConfiguredException()
        val token = accounts.token(account) ?: throw SyncAuthException()
        val expires = accounts.tokenExpiresAt(account)
        if (expires == null || expires - System.currentTimeMillis() < REFRESH_BEFORE_MS) {
            // Failure is not a problem here: the old token may still be valid, otherwise the 401 path follows
            runCatching { return refresh(server, token) }
        }
        return token
    }

    private fun refresh(server: String, token: String): String {
        val request = Request.Builder().url("$server/api/auth/refresh")
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody()).build()
        return authCall(request) ?: throw IOException("refresh failed")
    }

    /** Refresh; stores the new token. `null` on 401. */
    private fun authCall(request: Request): String? =
        loginClient.newCall(request).execute().use { res ->
            if (res.code == 401) return null
            if (!res.isSuccessful) throw IOException("auth failed: HTTP ${res.code}")
            val auth = AppJson.decodeFromString<AuthResponse>(res.body.string())
            accounts.setToken(account, auth.token, auth.expiresAt)
            auth.token
        }
}

/** REST client of the SyncAdapters – independent of the app's session, signed in via the Android account. */
fun syncApi(accounts: SyncAccounts, account: Account): SyncHavenApi {
    val loginClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(LanguageInterceptor())
        .build()
    val client = loginClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(AccountInterceptor(accounts, account, loginClient))
        .build()
    return NetworkFactory.api(client)
}
