package de.dave.synchaven.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dave.synchaven.BuildConfig
import de.dave.synchaven.R
import de.dave.synchaven.data.store.ThemeMode
import de.dave.synchaven.data.store.ViewMode
import de.dave.synchaven.ui.common.CollectMessages
import de.dave.synchaven.ui.common.ColorSwatches
import de.dave.synchaven.ui.common.ConfirmDialog
import de.dave.synchaven.ui.common.appViewModel
import de.dave.synchaven.ui.theme.SEED_COLORS
import de.dave.synchaven.ui.theme.supportsDynamicColor
import de.dave.synchaven.util.LOCALE
import de.dave.synchaven.ui.settings.ChoiceItem
import de.dave.synchaven.ui.settings.NotificationSection
import de.dave.synchaven.ui.settings.OfflineSection
import de.dave.synchaven.ui.settings.SectionTitle
import de.dave.synchaven.ui.settings.SyncSection
import de.dave.synchaven.ui.settings.SyncSetupDialog
import de.dave.synchaven.ui.viewmodels.SettingsViewModel
import java.time.DayOfWeek
import java.time.format.TextStyle
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it.settingsStore, it.sessionStore, it.authRepository, it.syncController, it.offlineSync) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val serverInfo by vm.serverInfo.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val syncSetup by vm.syncSetup.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    // Konto kann in den Android-Einstellungen entfernt, Berechtigungen können entzogen worden sein
    LifecycleResumeEffect(Unit) {
        vm.refreshSync()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages.flow, snackbar)
    var askLogout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                },
                title = { Text(stringResource(R.string.tab_settings)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            // ---------- Konto ----------
            SectionTitle(stringResource(R.string.settings_account))
            if (session?.local == true) {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.PhoneAndroid, null) },
                    headlineContent = { Text(stringResource(R.string.settings_local_mode)) },
                    supportingContent = { Text(stringResource(R.string.settings_local_mode_hint)) },
                )
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Dns, null) },
                    headlineContent = { Text(stringResource(R.string.settings_connect_server)) },
                    modifier = Modifier.clickable { askLogout = true },
                )
            } else session?.let { s ->
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.AccountCircle, null) },
                    headlineContent = { Text(s.name.ifBlank { s.email }) },
                    supportingContent = { Text(s.email) },
                    modifier = Modifier.clickable(onClick = vm::openProfile),
                )
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Dns, null) },
                    headlineContent = { Text(stringResource(R.string.login_server)) },
                    supportingContent = {
                        val info = serverInfo
                        Text(
                            if (info == null) s.serverUrl
                            else stringResource(R.string.settings_server_value, s.serverUrl, info.name, info.version)
                        )
                    },
                )
                ListItem(
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { askLogout = true },
                )
            }
            HorizontalDivider()

            // ---------- Offline-Daten (nur mit Server) ----------
            if (session != null && session?.local != true) {
                OfflineSection(offline, onSyncNow = vm::syncOfflineNow)
                HorizontalDivider()
            }

            // ---------- Synchronisierung mit Android ----------
            if (session != null) {
                SyncSection(
                    state = sync,
                    onSetup = vm::openSyncSetup,
                    onEnable = vm::enableSync,
                    onDisable = vm::disableSync,
                    onPermissionDenied = vm::syncPermissionDenied,
                    onInterval = vm::setSyncInterval,
                    onSyncNow = vm::syncNow,
                )
                HorizontalDivider()
            }

            // ---------- Benachrichtigungen ----------
            if (session != null) {
                NotificationSection(settings.reminders, onToggle = { on -> vm.update { it.copy(reminders = on) } })
                HorizontalDivider()
            }

            // ---------- Darstellung ----------
            SectionTitle(stringResource(R.string.settings_appearance))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { vm.update { it.copy(themeMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            if (supportsDynamicColor()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
                    supportingContent = { Text(stringResource(R.string.settings_dynamic_color_hint)) },
                    trailingContent = {
                        Switch(settings.dynamicColor, onCheckedChange = { v -> vm.update { it.copy(dynamicColor = v) } })
                    },
                )
            }
            if (!settings.dynamicColor || !supportsDynamicColor()) {
                Text(
                    stringResource(R.string.settings_seed_color), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ColorSwatches(
                    SEED_COLORS, settings.seedColor,
                    onSelect = { c -> vm.update { it.copy(seedColor = c) } },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))

            // ---------- Kalender ----------
            SectionTitle(stringResource(R.string.tab_calendar))
            ChoiceItem(
                title = stringResource(R.string.settings_default_view),
                value = stringResource((session?.defaultView ?: ViewMode.MONTH).labelRes),
                options = ViewMode.entries.map { it to stringResource(it.labelRes) },
                onSelect = vm::setDefaultView,
            )
            ChoiceItem(
                title = stringResource(R.string.settings_week_start),
                value = settings.weekStart.getDisplayName(TextStyle.FULL, LOCALE),
                options = listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)
                    .map { it to it.getDisplayName(TextStyle.FULL, LOCALE) },
                onSelect = { v -> vm.update { it.copy(weekStart = v) } },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_week_numbers)) },
                trailingContent = {
                    Switch(settings.showWeekNumbers, onCheckedChange = { v -> vm.update { it.copy(showWeekNumbers = v) } })
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_day_start, settings.dayStartHour)) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.settings_day_start_hint))
                        Slider(
                            value = settings.dayStartHour.toFloat(),
                            onValueChange = { v -> vm.update { it.copy(dayStartHour = v.toInt()) } },
                            valueRange = 0f..12f,
                            steps = 11,
                        )
                    }
                },
            )
            HorizontalDivider()

            ListItem(
                leadingContent = { Icon(Icons.Outlined.Info, null) },
                headlineContent = { Text(stringResource(R.string.settings_about)) },
                supportingContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
            )
        }
    }

    profile?.let { form ->
        AlertDialog(
            onDismissRequest = vm::closeProfile,
            title = { Text(stringResource(R.string.profile_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(form.name, { v -> vm.editProfile { it.copy(name = v) } }, label = { Text(stringResource(R.string.label_name)) }, singleLine = true)
                    OutlinedTextField(
                        form.email, { v -> vm.editProfile { it.copy(email = v) } }, label = { Text(stringResource(R.string.field_email)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    OutlinedTextField(
                        form.newPassword, { v -> vm.editProfile { it.copy(newPassword = v) } },
                        label = { Text(stringResource(R.string.profile_new_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    OutlinedTextField(
                        form.currentPassword, { v -> vm.editProfile { it.copy(currentPassword = v) } },
                        label = { Text(stringResource(R.string.profile_current_password)) },
                        supportingText = { Text(stringResource(R.string.profile_current_password_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    form.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = vm::saveProfile, enabled = !form.saving) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = vm::closeProfile) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (askLogout && session?.local == true) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_connect_server_title),
            text = stringResource(R.string.dialog_connect_server_text),
            confirmLabel = stringResource(R.string.settings_connect_server),
            destructive = false,
            onConfirm = vm::logout,
            onDismiss = { askLogout = false },
        )
    } else if (askLogout) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_logout_title),
            text = stringResource(R.string.dialog_logout_text) +
                if (offline.pending > 0) "\n\n" + pluralStringResource(R.plurals.dialog_logout_pending, offline.pending, offline.pending) else "",
            confirmLabel = stringResource(R.string.settings_logout),
            onConfirm = vm::logout,
            onDismiss = { askLogout = false },
        )
    }
    syncSetup?.let { form ->
        SyncSetupDialog(
            form = form,
            email = session?.email.orEmpty(),
            onPasswordChange = vm::editSyncPassword,
            onSubmit = vm::submitSyncSetup,
            onDismiss = vm::closeSyncSetup,
        )
    }
}
