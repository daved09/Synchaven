package de.dave.synchaven.data.local

import androidx.annotation.StringRes

/**
 * Texts of the local mode (error messages, default names) in the app's language. They match the server's texts
 * for the same language (`backend/locales/<lang>.json`; the `api_` strings), so the app reads the same with and without a server.
 */
fun interface LocalTexts {
    fun get(@StringRes id: Int, vararg args: Any): String
}
