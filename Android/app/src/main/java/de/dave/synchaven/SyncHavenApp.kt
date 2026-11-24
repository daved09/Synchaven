package de.dave.synchaven

import android.app.Application
import de.dave.synchaven.di.AppContainer
import de.dave.synchaven.icon.DynamicLauncherIcon
import de.dave.synchaven.reminders.ReminderNotifier
import kotlinx.coroutines.launch

class SyncHavenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Set the launcher icon to today's day (PackageManager calls are IPC → not on the main thread)
        container.appScope.launch { runCatching { DynamicLauncherIcon.update(this@SyncHavenApp) } }
        // Schedule event reminders and reschedule them when data changes
        ReminderNotifier.start(this, container)
    }
}
