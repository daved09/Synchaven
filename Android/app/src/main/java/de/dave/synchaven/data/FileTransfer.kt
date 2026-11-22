package de.dave.synchaven.data

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Eine vom Benutzer gewählte Datei ließ sich nicht öffnen. */
class FileAccessException(val write: Boolean) : IOException(if (write) "cannot write file" else "cannot read file")

/** Liest und schreibt vom Benutzer gewählte Dateien (Storage Access Framework) für Import und Export. */
class FileTransfer(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw FileAccessException(write = false)
    }

    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw FileAccessException(write = true)
    }
}
