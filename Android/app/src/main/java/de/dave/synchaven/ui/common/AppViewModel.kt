package de.dave.synchaven.ui.common

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.dave.synchaven.SyncHavenApp
import de.dave.synchaven.di.AppContainer

/** Creates a ViewModel with access to the [AppContainer] and the [CreationExtras] (for SavedStateHandle). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: CreationExtras.(AppContainer) -> VM,
): VM = viewModel(
    factory = viewModelFactory {
        initializer { create((this[APPLICATION_KEY] as SyncHavenApp).container) }
    }
)
