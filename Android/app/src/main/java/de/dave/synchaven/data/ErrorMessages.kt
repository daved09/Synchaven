package de.dave.synchaven.data

import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.api.ApiException
import de.dave.synchaven.data.api.InvalidServerUrlException
import de.dave.synchaven.data.api.NoServerConfiguredException
import de.dave.synchaven.toUiText
import de.dave.synchaven.uiText
import kotlinx.serialization.SerializationException

/** Displayable error message for exceptions from the network and data layer. */
fun Throwable.userMessage(): UiText = when {
    this is ApiException -> when {
        status >= 500 -> uiText(R.string.error_server, status)
        // Messages from the server are already displayable, in the language from Accept-Language (see docs/API.md)
        serverMessage.isNotBlank() -> serverMessage.toUiText()
        else -> uiText(R.string.error_http, status)
    }
    isLocalNetworkDenied() -> uiText(R.string.error_local_network)
    this is java.net.UnknownHostException -> uiText(R.string.error_unknown_host)
    this is java.net.ConnectException -> uiText(R.string.error_connect)
    this is java.net.SocketTimeoutException -> uiText(R.string.error_timeout)
    this is javax.net.ssl.SSLException -> uiText(R.string.error_ssl)
    this is SerializationException -> uiText(R.string.error_unexpected_response)
    this is NoServerConfiguredException -> uiText(R.string.error_not_logged_in)
    this is InvalidServerUrlException -> uiText(R.string.error_invalid_server_url)
    this is FileAccessException -> uiText(if (write) R.string.error_file_write else R.string.error_file_read)
    else -> message?.toUiText() ?: uiText(R.string.error_unknown)
}

/** Android 17 blocks sockets to the local network with EPERM if ACCESS_LOCAL_NETWORK is missing. */
private fun Throwable.isLocalNetworkDenied(): Boolean =
    generateSequence(this) { it.cause }.any { e ->
        val msg = e.message.orEmpty()
        msg.contains("EPERM") || msg.contains("Operation not permitted")
    }
