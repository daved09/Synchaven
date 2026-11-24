package de.dave.synchaven.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Don't rename: the file name is derived from it (also in the backup rules). */
private val Context.dataStore by preferencesDataStore(name = "synchaven")

/** The app's single Preferences DataStore; [SessionStore] and [SettingsStore] each manage their own keys. */
class AppDataStore(context: Context, val scope: CoroutineScope) {
    val store: DataStore<Preferences> = context.applicationContext.dataStore

    /** Raw data; `null` until DataStore has loaded for the first time. */
    val prefs: StateFlow<Preferences?> = store.data.stateIn(scope, SharingStarted.Eagerly, null)
}
