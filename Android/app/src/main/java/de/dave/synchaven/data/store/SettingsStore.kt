package de.dave.synchaven.data.store

import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.dave.synchaven.R
import java.time.DayOfWeek
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ViewMode(@StringRes val labelRes: Int) {
    MONTH(R.string.view_month), WEEK(R.string.view_week), DAY(R.string.view_day), LIST(R.string.view_list);

    /** Value in the API (`default_view` on the user) */
    val apiValue: String get() = name.lowercase()

    companion object {
        fun fromApi(value: String?): ViewMode = entries.firstOrNull { it.apiValue == value } ?: MONTH
    }
}

/**
 * Display and calendar settings of this device, matching the settings of the web UI.
 * The default view belongs to the user account (see [Session.defaultView]).
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Colors from the wallpaper (Android 12+) instead of from [seedColor] */
    val dynamicColor: Boolean = false,
    val seedColor: String = "#6750A4",
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val showWeekNumbers: Boolean = true,
    /** Show event reminders as notifications */
    val reminders: Boolean = true,
    /** The notification permission (Android 13+) has already been requested once */
    val notificationPermissionAsked: Boolean = false,
)

class SettingsStore(private val dataStore: AppDataStore) {
    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val seedColor = stringPreferencesKey("seed_color")
        val weekStart = stringPreferencesKey("week_start")
        val showWeekNumbers = booleanPreferencesKey("show_week_numbers")
        val reminders = booleanPreferencesKey("reminders")
        val notificationPermissionAsked = booleanPreferencesKey("notification_permission_asked")
    }

    val settings: StateFlow<AppSettings> = dataStore.prefs
        .map { it?.toSettings() ?: AppSettings() }
        .stateIn(dataStore.scope, SharingStarted.Eagerly, AppSettings())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.store.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[Keys.themeMode] = s.themeMode.name
            prefs[Keys.dynamicColor] = s.dynamicColor
            prefs[Keys.seedColor] = s.seedColor
            prefs[Keys.weekStart] = s.weekStart.name
            prefs[Keys.showWeekNumbers] = s.showWeekNumbers
            prefs[Keys.reminders] = s.reminders
            prefs[Keys.notificationPermissionAsked] = s.notificationPermissionAsked
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = enumOr(this[Keys.themeMode], d.themeMode),
            dynamicColor = this[Keys.dynamicColor] ?: d.dynamicColor,
            seedColor = this[Keys.seedColor] ?: d.seedColor,
            weekStart = enumOr(this[Keys.weekStart], d.weekStart),
            showWeekNumbers = this[Keys.showWeekNumbers] ?: d.showWeekNumbers,
            reminders = this[Keys.reminders] ?: d.reminders,
            notificationPermissionAsked = this[Keys.notificationPermissionAsked] ?: d.notificationPermissionAsked,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
