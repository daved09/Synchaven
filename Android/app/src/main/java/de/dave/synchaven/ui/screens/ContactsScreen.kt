package de.dave.synchaven.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ImportContacts
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dave.synchaven.R
import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.ui.common.CollectMessages
import de.dave.synchaven.ui.common.ErrorBox
import de.dave.synchaven.ui.common.LetterFastScroller
import de.dave.synchaven.ui.common.LoadingBox
import de.dave.synchaven.ui.common.MainBottomBar
import de.dave.synchaven.ui.common.MainTab
import de.dave.synchaven.ui.common.appViewModel
import de.dave.synchaven.util.displayName
import de.dave.synchaven.util.displayNameText
import de.dave.synchaven.util.subtitle
import de.dave.synchaven.ui.contacts.AddressBooksDialog
import de.dave.synchaven.ui.contacts.Avatar
import de.dave.synchaven.ui.viewmodels.CategoryCount
import de.dave.synchaven.ui.viewmodels.ContactsViewModel
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Settings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    onSelectTab: (MainTab) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenContact: (String) -> Unit,
    /** Neuer Kontakt, vorbelegt mit dem gewählten Adressbuch und der gewählten Kategorie */
    onNewContact: (bookId: String?, category: String?) -> Unit,
) {
    val vm = appViewModel { ContactsViewModel(it.contactRepository, it.fileTransfer) }
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages.flow, snackbar)

    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var manageBooks by rememberSaveable { mutableStateOf(false) }

    // Import/Export: das Adressbuch, für das die Dateiauswahl geöffnet wurde
    var fileBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val fileBook = s.addressBooks.firstOrNull { it.id == fileBookId }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vcard")) { uri ->
        if (uri != null && fileBook != null) vm.exportAddressBook(fileBook, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && fileBook != null) vm.importAddressBook(fileBook, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_contacts)) },
                actions = {
                    IconButton(onClick = {
                        if (searching) vm.onQueryChange("")
                        searching = !searching
                    }) { Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, stringResource(R.string.action_search)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.contacts_manage_books)) },
                                leadingIcon = { Icon(Icons.Outlined.ImportContacts, null) },
                                onClick = { menu = false; manageBooks = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.contacts_sync)) },
                                leadingIcon = { Icon(Icons.Outlined.Sync, null) },
                                onClick = { menu = false; onOpenSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_refresh)) },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = { menu = false; vm.refresh() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tab_settings)) },
                                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                onClick = { menu = false; onOpenSettings() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { MainBottomBar(MainTab.CONTACTS, onSelectTab) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNewContact(s.selectedBookId, s.selectedCategory) }) { Icon(Icons.Outlined.PersonAdd, stringResource(R.string.contacts_new)) }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searching) {
                OutlinedTextField(
                    value = s.query,
                    onValueChange = vm::onQueryChange,
                    placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (s.addressBooks.size > 1 || s.categories.isNotEmpty()) {
                ContactFilter(
                    books = s.addressBooks.takeIf { it.size > 1 }.orEmpty(),
                    selectedBook = s.selectedBookId,
                    categories = s.categories,
                    selectedCategory = s.selectedCategory,
                    onSelectBook = vm::selectBook,
                    onSelectCategory = vm::selectCategory,
                )
            }
            when {
                s.loading -> LoadingBox()
                s.error != null && s.groups.isEmpty() -> ErrorBox(s.error!!, onRetry = vm::refresh)
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                    if (s.groups.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(
                                    if (s.query.isBlank() && s.selectedCategory == null) R.string.contacts_empty else R.string.contacts_no_results,
                                ),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        val listState = rememberLazyListState()
                        val scope = rememberCoroutineScope()
                        // Index der Kopfzeile jeder Gruppe in der LazyColumn
                        val headerIndices = remember(s.groups) {
                            s.groups.runningFold(0) { acc, g -> acc + 1 + g.contacts.size }.dropLast(1)
                        }
                        val currentGroup by remember(headerIndices) {
                            derivedStateOf {
                                val first = listState.firstVisibleItemIndex
                                headerIndices.indexOfLast { it <= first }.coerceAtLeast(0)
                            }
                        }
                        val showScroller = s.groups.size > 1
                        Box(Modifier.fillMaxSize()) {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                state = listState,
                                contentPadding = PaddingValues(bottom = 88.dp, end = if (showScroller) 12.dp else 0.dp),
                            ) {
                                for (group in s.groups) {
                                    stickyHeader(key = "h" + group.letter) {
                                        Text(
                                            group.letter,
                                            Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(horizontal = 24.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    items(group.contacts, key = { it.id }) { c ->
                                        ContactRow(c) { onOpenContact(c.id) }
                                    }
                                }
                            }
                            if (showScroller) {
                                LetterFastScroller(
                                    letters = s.groups.map { it.letter },
                                    current = currentGroup,
                                    onSelect = { i -> scope.launch { listState.scrollToItem(headerIndices[i]) } },
                                    // Platz für den FAB unten rechts lassen
                                    modifier = Modifier.align(Alignment.CenterEnd).padding(top = 8.dp, bottom = 88.dp, end = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (manageBooks) {
        AddressBooksDialog(
            books = s.addressBooks,
            onSave = vm::saveAddressBook,
            onDelete = vm::deleteAddressBook,
            onExport = { book ->
                fileBookId = book.id
                exportLauncher.launch("${book.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")}.vcf")
            },
            onImport = { book ->
                fileBookId = book.id
                importLauncher.launch(arrayOf("text/vcard", "text/x-vcard", "text/directory", "application/octet-stream", "text/plain"))
            },
            onDismiss = { manageBooks = false },
        )
    }
}

/** Filter nach Adressbuch (nur bei mehreren) und Kategorie */
@Composable
private fun ContactFilter(
    books: List<AddressBook>,
    selectedBook: String?,
    categories: List<CategoryCount>,
    selectedCategory: String?,
    onSelectBook: (String?) -> Unit,
    onSelectCategory: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedBook == null && selectedCategory == null,
                onClick = { onSelectBook(null); onSelectCategory(null) },
                label = { Text(stringResource(R.string.contacts_filter_all)) },
            )
        }
        items(books, key = { "b" + it.id }) { b ->
            FilterChip(
                selected = selectedBook == b.id,
                onClick = { onSelectBook(if (selectedBook == b.id) null else b.id) },
                label = { Text(stringResource(R.string.contacts_book_chip, b.name, b.contactCount)) },
            )
        }
        items(categories, key = { "c" + it.name.lowercase() }) { cat ->
            val selected = selectedCategory == cat.name
            FilterChip(
                selected = selected,
                onClick = { onSelectCategory(if (selected) null else cat.name) },
                label = { Text(stringResource(R.string.contacts_book_chip, cat.name, cat.count)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
    }
}

@Composable
private fun ContactRow(c: Contact, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(c.displayNameText().asString(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = c.subtitle().takeIf { it.isNotBlank() }?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = { Avatar(c, 40) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
