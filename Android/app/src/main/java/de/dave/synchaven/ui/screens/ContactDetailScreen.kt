package de.dave.synchaven.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ImportContacts
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.toUiText
import de.dave.synchaven.ui.common.CollectMessages
import de.dave.synchaven.ui.common.ConfirmDialog
import de.dave.synchaven.ui.common.ErrorBox
import de.dave.synchaven.ui.common.LoadingBox
import de.dave.synchaven.ui.common.appViewModel
import de.dave.synchaven.uiText
import de.dave.synchaven.util.displayName
import de.dave.synchaven.util.displayNameText
import de.dave.synchaven.util.formatBirthday
import de.dave.synchaven.util.lines
import de.dave.synchaven.util.mapQuery
import de.dave.synchaven.util.typeLabel
import de.dave.synchaven.util.websiteUrl
import de.dave.synchaven.ui.contacts.Avatar
import de.dave.synchaven.ui.viewmodels.ContactDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(onBack: () -> Unit, onEdit: (String) -> Unit) {
    val vm = appViewModel { ContactDetailViewModel(it.contactRepository, createSavedStateHandle()) }
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages.flow, snackbar)
    var askDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
                title = {},
                actions = {
                    s.contact?.let { c ->
                        IconButton(onClick = { onEdit(c.id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit)) }
                        IconButton(onClick = { askDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val c = s.contact
        when {
            s.loading -> LoadingBox(Modifier.padding(padding))
            c == null -> ErrorBox(s.error ?: uiText(R.string.contact_not_found), onRetry = { vm.load() }, modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val photo = s.photo
                        if (photo != null) {
                            Image(
                                photo.asImageBitmap(), null,
                                Modifier.size(112.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Avatar(c, 112)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(c.displayNameText().asString(), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        if (c.nickname.isNotBlank()) {
                            Text(stringResource(R.string.contact_nickname, c.nickname), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val org = UiText.Join(
                            listOf(c.title, c.department, c.organization).filter { it.isNotBlank() }.map { it.toUiText() }
                        ).asString()
                        if (org.isNotBlank()) {
                            Text(org, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            val phone = c.phones.firstOrNull()?.value
                            val email = c.emails.firstOrNull()?.value
                            QuickAction(Icons.Outlined.Call, stringResource(R.string.contact_call), phone != null) {
                                open(Intent(Intent.ACTION_DIAL, "tel:${Uri.encode(phone)}".toUri()))
                            }
                            QuickAction(Icons.AutoMirrored.Outlined.Message, stringResource(R.string.contact_sms), phone != null) {
                                open(Intent(Intent.ACTION_SENDTO, "smsto:${Uri.encode(phone)}".toUri()))
                            }
                            QuickAction(Icons.Outlined.Mail, stringResource(R.string.contact_email), email != null) {
                                open(Intent(Intent.ACTION_SENDTO, "mailto:${Uri.encode(email)}".toUri()))
                            }
                        }
                    }
                    HorizontalDivider()
                }

                c.phones.forEachIndexed { i, p ->
                    item(key = "p$i") {
                        DetailItem(Icons.Outlined.Call.takeIf { i == 0 }, p.value, typeLabel(p.type, phone = true).asString()) {
                            open(Intent(Intent.ACTION_DIAL, "tel:${Uri.encode(p.value)}".toUri()))
                        }
                    }
                }
                c.emails.forEachIndexed { i, e ->
                    item(key = "e$i") {
                        DetailItem(Icons.Outlined.Mail.takeIf { i == 0 }, e.value, typeLabel(e.type).asString()) {
                            open(Intent(Intent.ACTION_SENDTO, "mailto:${Uri.encode(e.value)}".toUri()))
                        }
                    }
                }
                c.addresses.forEachIndexed { i, a ->
                    item(key = "a$i") {
                        DetailItem(Icons.Outlined.Place.takeIf { i == 0 }, a.lines().map { it.asString() }.joinToString("\n"), typeLabel(a.type).asString()) {
                            open(Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(a.mapQuery())}".toUri()))
                        }
                    }
                }
                c.urls.forEachIndexed { i, u ->
                    item(key = "u$i") {
                        DetailItem(Icons.Outlined.Language.takeIf { i == 0 }, u.value, typeLabel(u.type).asString()) {
                            open(Intent(Intent.ACTION_VIEW, u.websiteUrl().toUri()))
                        }
                    }
                }
                if (c.birthday.isNotBlank()) {
                    item(key = "bday") { DetailItem(Icons.Outlined.Cake, formatBirthday(c.birthday).asString(), stringResource(R.string.contact_birthday)) }
                }
                if (c.note.isNotBlank()) {
                    item(key = "note") { DetailItem(Icons.AutoMirrored.Outlined.Notes, c.note, stringResource(R.string.contact_note)) }
                }
                if (s.addressBookName.isNotBlank()) {
                    item(key = "book") { DetailItem(Icons.Outlined.ImportContacts, s.addressBookName, stringResource(R.string.contact_address_book)) }
                }
            }
        }
    }

    if (askDelete) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_contact_title),
            text = stringResource(
                R.string.dialog_delete_contact_text,
                s.contact?.displayNameText()?.asString().orEmpty(),
            ),
            onConfirm = vm::delete,
            onDismiss = { askDelete = false },
        )
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Zeile mit Symbol (nur beim ersten Eintrag einer Gruppe), Wert und Typ */
@Composable
private fun DetailItem(icon: ImageVector?, value: String, label: String, onClick: (() -> Unit)? = null) {
    ListItem(
        leadingContent = {
            if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Spacer(Modifier.size(24.dp))
        },
        headlineContent = { Text(value) },
        supportingContent = { Text(label) },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
