package de.dave.synchaven.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.dave.synchaven.R
import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.AddressBookInput
import de.dave.synchaven.ui.common.ConfirmDialog

/** Create, rename, delete, import and export address books */
@Composable
fun AddressBooksDialog(
    books: List<AddressBook>,
    onSave: (String?, AddressBookInput) -> Unit,
    onDelete: (AddressBook) -> Unit,
    onExport: (AddressBook) -> Unit,
    onImport: (AddressBook) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<AddressBook?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AddressBook?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.books_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (book in books) {
                    var menu by remember(book.id) { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(book.name) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.book_contact_count, book.contactCount, book.contactCount))
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_menu)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.book_rename)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                        onClick = { menu = false; editing = book },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.book_import)) }, leadingIcon = { Icon(Icons.Outlined.Upload, null) },
                                        onClick = { menu = false; onImport(book) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.book_export)) }, leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                        onClick = { menu = false; onExport(book) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_delete)) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                        onClick = { menu = false; deleting = book },
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Outlined.Add, null)
                    Text(stringResource(R.string.book_new), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )

    if (creating) AddressBookEditDialog(null, { onSave(null, it) }) { creating = false }
    editing?.let { book -> AddressBookEditDialog(book, { onSave(book.id, it) }) { editing = null } }
    deleting?.let { book ->
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_book_title),
            text = pluralStringResource(R.plurals.dialog_delete_book_text, book.contactCount, book.contactCount, book.name),
            onConfirm = { onDelete(book) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun AddressBookEditDialog(book: AddressBook?, onSave: (AddressBookInput) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(book?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(book?.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (book == null) R.string.book_new else R.string.book_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.label_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.label_description)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(AddressBookInput(name.trim(), description.trim()))
                onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
