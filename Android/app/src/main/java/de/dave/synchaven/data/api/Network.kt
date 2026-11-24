package de.dave.synchaven.data.api

import de.dave.synchaven.data.model.ApiErrorBody
import de.dave.synchaven.data.store.SessionStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
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

/** Placeholder that the [ServerInterceptor] replaces with the server's address. */
private const val PLACEHOLDER_BASE = "http://synchaven.invalid/api/"

/** No server signed in (should only happen after signing out during a request). */
class NoServerConfiguredException : IOException("no server configured")

/** The stored or entered server address is not a valid URL. */
class InvalidServerUrlException(url: String) : IOException("invalid server url: $url")

/**
 * Error response from the server, see the error format in docs/API.md.
 * [serverMessage] is the server's displayable message (empty if it didn't provide one).
 */
class ApiException(val status: Int, val code: String, val serverMessage: String) :
    Exception("HTTP $status $code: $serverMessage")

/** Normalizes input such as "synchaven.local:8080" to "http://synchaven.local:8080". */
fun normalizeServerUrl(input: String): String {
    var url = input.trim().trimEnd('/')
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "http://$url"
    // Anyone pasting the API address means the server itself
    if (url.endsWith("/api", ignoreCase = true)) url = url.dropLast(4)
    return url
}

/**
 * Replaces the placeholder with the server (header [SyncHavenApi.SERVER_HEADER] or signed-in session)
 * and attaches the bearer token. A 401 with a signed-in session signs out (token expired).
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

        val url = rebaseUrl(base, original.url)
        val request = original.newBuilder()
            .url(url)
            .removeHeader(SyncHavenApi.SERVER_HEADER)
            .header("Accept", "application/json")
            .apply { if (explicitServer == null && session != null) header("Authorization", "Bearer ${session.token}") }
            .build()

        val response = chain.proceed(request)
        // PUT /auth/me also responds with 401 for a wrong current password – that is not an expired token
        val passwordCheck = original.method == "PUT" && original.url.encodedPath.endsWith("/auth/me")
        if (response.code == 401 && explicitServer == null && session != null && !passwordCheck) onUnauthorized()
        return response
    }
}

/** Places the path of a Retrofit URL (relative to the placeholder) under the server's address. */
fun rebaseUrl(server: HttpUrl, requestUrl: HttpUrl): HttpUrl = server.newBuilder()
    .encodedPath(server.encodedPath.trimEnd('/') + requestUrl.encodedPath)
    .encodedQuery(requestUrl.encodedQuery)
    .build()

object NetworkFactory {
    fun okHttp(sessionStore: SessionStore, debug: Boolean, onUnauthorized: () -> Unit): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS) // short, so the offline cache kicks in quickly without a server
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
 * Executes an API call and converts HTTP errors into [ApiException] with the server's message.
 */
suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
    val parsed = body?.let { runCatching { AppJson.decodeFromString<ApiErrorBody>(it) }.getOrNull() }
    throw ApiException(e.code(), parsed?.error.orEmpty(), parsed?.message.orEmpty())
}
