package de.dave.synchaven

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dave.synchaven.ui.AppRoot
import de.dave.synchaven.ui.theme.SyncHavenTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SyncHavenApp).container
        handleIntent(intent)
        setContent {
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
            SyncHavenTheme(settings) {
                AppRoot(container.sessionStore, container.syncController)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** „Konto hinzufügen → Synchaven“ in den Android-Einstellungen: Konto für die Sitzung anlegen (sonst Anmeldung). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SETUP_SYNC, false) != true) return
        intent.removeExtra(EXTRA_SETUP_SYNC)
        val container = (application as SyncHavenApp).container
        container.appScope.launch { runCatching { container.authRepository.ensureSyncAccount(force = true) } }
    }

    companion object {
        const val EXTRA_SETUP_SYNC = "de.dave.synchaven.SETUP_SYNC"
    }
}
