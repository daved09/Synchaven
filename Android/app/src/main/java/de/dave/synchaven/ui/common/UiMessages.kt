package de.dave.synchaven.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import de.dave.synchaven.UiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** One-time messages from a ViewModel to the UI, shown as a snackbar. */
class UiMessages {
    private val channel = Channel<UiText>(Channel.BUFFERED)
    val flow: Flow<UiText> = channel.receiveAsFlow()

    fun send(message: UiText) {
        channel.trySend(message)
    }
}

@Composable
fun CollectMessages(messages: Flow<UiText>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(messages) { messages.collect { snackbar.showSnackbar(it.resolve(resources)) } }
}
