package de.dave.synchaven

import android.app.Application
import de.dave.synchaven.di.AppContainer

class SyncHavenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
