package de.dave.synchaven

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dave.synchaven.ui.AppRoot
import de.dave.synchaven.ui.theme.SyncHavenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SyncHavenApp).container
        setContent {
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
            SyncHavenTheme(settings) {
                AppRoot(container.sessionStore)
            }
        }
    }
}
