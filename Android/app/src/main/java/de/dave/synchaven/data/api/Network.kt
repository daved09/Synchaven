package de.dave.synchaven.data.api

import de.dave.synchaven.data.model.ApiErrorBody
import de.dave.synchaven.data.store.SessionStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/** Platzhalter, den der [ServerInterceptor] durch die Adresse des Servers ersetzt. */
private const val PLACEHOLDER_BASE = "http://synchaven.invalid/api/"

/** Kein Server angemeldet (sollte nur nach einer Abmeldung während einer Anfrage vorkommen). */
class NoServerConfiguredException : IOException("no server configured")

/** Die gespeicherte oder eingegebene Serveradresse ist keine gültige URL. */
class InvalidServerUrlException(url: String) : IOException("invalid server url: $url")

/**
 * Fehlerantwort des Servers, siehe Fehlerformat in docs/API.md.
 * [serverMessage] ist die anzeigbare Meldung des Servers (leer, wenn er keine geliefert hat).
 */
class ApiException(val status: Int, val code: String, val serverMessage: String) :
    Exception("HTTP $status $code: $serverMessage")

/** Normalisiert Eingaben wie „synchaven.local:8080“ zu „http://synchaven.local:8080“. */
fun normalizeServerUrl(input: String): String {
    var url = input.trim().trimEnd('/')
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "http://$url"
    // Wer die API-Adresse einfügt, meint den Server selbst
    if (url.endsWith("/api", ignoreCase = true)) url = url.dropLast(4)
    return url
}

/**
 * Ersetzt den Platzhalter durch den Server (Header [SyncHavenApi.SERVER_HEADER] oder angemeldete Sitzung)
 * und hängt das Bearer-Token an. Ein 401 bei angemeldeter Sitzung meldet ab (Token abgelaufen).
 */
class ServerInterceptor(
    private val sessionStore: SessionStore,
    private val onUnauthorized: () -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val explicitServer = original.header(SyncHavenApi.SERVER_HEADER)
        val session = sessionStore.current
        val server = explicitServer ?: session?.serverUrl ?: throw NoServerConfiguredException()
        val base = server.toHttpUrlOrNull() ?: throw InvalidServerUrlException(server)

        val url = base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + original.url.encodedPath)
            .encodedQuery(original.url.encodedQuery)
            .build()
        val request = original.newBuilder()
            .url(url)
            .removeHeader(SyncHavenApi.SERVER_HEADER)
            .header("Accept", "application/json")
            .apply { if (explicitServer == null && session != null) header("Authorization", "Bearer ${session.token}") }
            .build()

        val response = chain.proceed(request)
        // PUT /auth/me antwortet auch bei falschem aktuellem Passwort mit 401 – das ist kein abgelaufenes Token
        val passwordCheck = original.method == "PUT" && original.url.encodedPath.endsWith("/auth/me")
        if (response.code == 401 && explicitServer == null && session != null && !passwordCheck) onUnauthorized()
        return response
    }
}

object NetworkFactory {
    fun okHttp(sessionStore: SessionStore, debug: Boolean, onUnauthorized: () -> Unit): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(ServerInterceptor(sessionStore, onUnauthorized))
            .apply {
                if (debug) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                    })
                }
            }
            .build()

    fun api(client: OkHttpClient): SyncHavenApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE)
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SyncHavenApi::class.java)
}

/**
 * Führt einen API-Aufruf aus und wandelt HTTP-Fehler in [ApiException] mit der Meldung des Servers um.
 */
suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
    val parsed = body?.let { runCatching { AppJson.decodeFromString<ApiErrorBody>(it) }.getOrNull() }
    throw ApiException(e.code(), parsed?.error.orEmpty(), parsed?.message.orEmpty())
}
