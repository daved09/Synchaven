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
        // Launcher-Icon auf den heutigen Tag stellen (PackageManager-Aufrufe sind IPC → nicht im Main-Thread)
        container.appScope.launch { runCatching { DynamicLauncherIcon.update(this@SyncHavenApp) } }
        // Erinnerungen an Termine planen und bei Datenänderungen neu planen
        ReminderNotifier.start(this, container)
    }
}
