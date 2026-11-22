package de.dave.synchaven.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Nicht umbenennen: Der Dateiname leitet sich davon ab (auch in den Backup-Regeln). */
private val Context.dataStore by preferencesDataStore(name = "synchaven")

/** Der eine Preferences-DataStore der App; [SessionStore] und [SettingsStore] verwalten jeweils eigene Schlüssel. */
class AppDataStore(context: Context, val scope: CoroutineScope) {
    val store: DataStore<Preferences> = context.applicationContext.dataStore

    /** Rohdaten; `null`, bis DataStore zum ersten Mal geladen hat. */
    val prefs: StateFlow<Preferences?> = store.data.stateIn(scope, SharingStarted.Eagerly, null)
}
