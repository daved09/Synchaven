package de.dave.synchaven.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import de.dave.synchaven.R
import de.dave.synchaven.data.model.PostalAddress
import de.dave.synchaven.data.model.TypedValue
import de.dave.synchaven.toUiText
import de.dave.synchaven.ui.common.ErrorBox
import de.dave.synchaven.ui.common.LoadingBox
import de.dave.synchaven.ui.common.appViewModel
import de.dave.synchaven.util.OTHER_TYPES
import de.dave.synchaven.util.PHONE_TYPES
import de.dave.synchaven.util.TypeOption
import de.dave.synchaven.util.sameCategory
import de.dave.synchaven.util.typeLabel
import de.dave.synchaven.ui.viewmodels.ContactEditViewModel
import de.dave.synchaven.ui.viewmodels.TypedField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditScreen(onClose: () -> Unit, onCreated: (String) -> Unit) {
    val vm = appViewModel { ContactEditViewModel(it.contactRepository, createSavedStateHandle()) }
    val s by vm.state.collectAsStateWithLifecycle()
    val c = s.contact

    LaunchedEffect(s.savedId) {
        val id = s.savedId ?: return@LaunchedEffect
        if (s.isNew) onCreated(id) else onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel)) } },
                title = { Text(stringResource(if (s.isNew) R.string.contacts_new else R.string.contact_edit)) },
                actions = {
                    Button(onClick = vm::save, enabled = !s.loading && !s.saving, modifier = Modifier.padding(end = 8.dp)) {
                        if (s.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        when {
            s.loading -> LoadingBox(Modifier.padding(padding))
            s.addressBooks.isEmpty() && s.error != null -> ErrorBox(s.error!!, null, Modifier.padding(padding))
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .wrapContentWidth()
                    .widthIn(max = 720.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                s.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }

                // ---------- Name ----------
                if (s.showAllNameFields) {
                    NameField(stringResource(R.string.field_prefix), c.prefix) { v -> vm.update { it.copy(prefix = v) } }
                }
                NameField(stringResource(R.string.field_given_name), c.givenName) { v -> vm.update { it.copy(givenName = v) } }
                if (s.showAllNameFields) {
                    NameField(stringResource(R.string.field_additional_names), c.additionalNames) { v -> vm.update { it.copy(additionalNames = v) } }
                }
                NameField(stringResource(R.string.field_family_name), c.familyName) { v -> vm.update { it.copy(familyName = v) } }
                if (s.showAllNameFields) {
                    NameField(stringResource(R.string.field_suffix), c.suffix) { v -> vm.update { it.copy(suffix = v) } }
                    NameField(stringResource(R.string.field_nickname), c.nickname) { v -> vm.update { it.copy(nickname = v) } }
                } else {
                    TextButton(onClick = vm::showAllNameFields) { Text(stringResource(R.string.field_more_names)) }
                }
                NameField(stringResource(R.string.field_organization), c.organization) { v -> vm.update { it.copy(organization = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NameField(stringResource(R.string.field_department), c.department, Modifier.weight(1f)) { v -> vm.update { it.copy(department = v) } }
                    NameField(stringResource(R.string.field_job_title), c.title, Modifier.weight(1f)) { v -> vm.update { it.copy(title = v) } }
                }

                // ---------- Kontaktwege ----------
                Section(stringResource(R.string.section_phone))
                TypedList(c.phones, PHONE_TYPES, phone = true, KeyboardType.Phone, stringResource(R.string.field_phone),
                    onChange = { i, v -> vm.updateTyped(TypedField.PHONES, i, v) },
                    onRemove = { vm.removeTyped(TypedField.PHONES, it) },
                    onAdd = { vm.addTyped(TypedField.PHONES) })

                Section(stringResource(R.string.contact_email))
                TypedList(c.emails, OTHER_TYPES, phone = false, KeyboardType.Email, stringResource(R.string.field_email),
                    onChange = { i, v -> vm.updateTyped(TypedField.EMAILS, i, v) },
                    onRemove = { vm.removeTyped(TypedField.EMAILS, it) },
                    onAdd = { vm.addTyped(TypedField.EMAILS) })

                Section(stringResource(R.string.section_addresses))
                c.addresses.forEachIndexed { i, a ->
                    AddressEditor(a, onChange = { vm.updateAddress(i, it) }, onRemove = { vm.removeAddress(i) })
                }
                TextButton(onClick = vm::addAddress) {
                    Icon(Icons.Outlined.Add, null)
                    Text(stringResource(R.string.add_address), Modifier.padding(start = 8.dp))
                }

                Section(stringResource(R.string.section_websites))
                TypedList(c.urls, OTHER_TYPES, phone = false, KeyboardType.Uri, stringResource(R.string.field_website),
                    onChange = { i, v -> vm.updateTyped(TypedField.URLS, i, v) },
                    onRemove = { vm.removeTyped(TypedField.URLS, it) },
                    onAdd = { vm.addTyped(TypedField.URLS) })

                // ---------- Sonstiges ----------
                Section(stringResource(R.string.section_more))
                OutlinedTextField(
                    value = s.birthdayInput,
                    onValueChange = vm::onBirthdayChange,
                    label = { Text(stringResource(R.string.contact_birthday)) },
                    placeholder = { Text(stringResource(R.string.field_birthday_placeholder)) },
                    isError = !s.birthdayValid,
                    supportingText = { Text(stringResource(if (s.birthdayValid) R.string.field_birthday_hint else R.string.field_birthday_invalid)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                CategoryEditor(
                    categories = c.categories,
                    input = s.categoryInput,
                    suggestions = s.allCategories.filter { a -> c.categories.none { sameCategory(it, a) } },
                    onInput = vm::onCategoryInput,
                    onAdd = vm::addCategory,
                    onRemove = vm::removeCategory,
                )
                OutlinedTextField(
                    value = c.note,
                    onValueChange = { v -> vm.update { it.copy(note = v) } },
                    label = { Text(stringResource(R.string.contact_note)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OptionDropdown(
                    label = stringResource(R.string.contact_address_book),
                    value = s.addressBooks.firstOrNull { it.id == c.addressBookId }?.name.orEmpty(),
                    options = s.addressBooks.map { TypeOption(it.name.toUiText(), it.id) },
                    onSelect = { id -> vm.update { it.copy(addressBookId = id) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Zugewiesene Kategorien als Chips, Eingabe für neue und Vorschläge aus anderen Kontakten */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(
    categories: List<String>,
    input: String,
    suggestions: List<String>,
    onInput: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Text(stringResource(R.string.contact_categories), style = MaterialTheme.typography.labelLarge)
    if (categories.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (cat in categories) {
                InputChip(
                    selected = true,
                    onClick = { onRemove(cat) },
                    label = { Text(cat) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.size(InputChipDefaults.IconSize)) },
                    trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.action_remove), Modifier.size(InputChipDefaults.IconSize)) },
                )
            }
        }
    }
    OutlinedTextField(
        value = input,
        onValueChange = onInput,
        label = { Text(stringResource(R.string.field_new_category)) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { onAdd(input) }, enabled = input.isNotBlank()) {
                Icon(Icons.Outlined.Add, stringResource(R.string.action_add_category))
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) onAdd(input) }),
        modifier = Modifier.fillMaxWidth(),
    )
    val shown = suggestions.filter { input.isBlank() || it.contains(input.trim(), ignoreCase = true) }.take(12)
    if (shown.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (cat in shown) {
                SuggestionChip(onClick = { onAdd(cat) }, label = { Text(cat) })
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NameField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = modifier,
    )
}

/** Liste typisierter Werte (Telefon, E-Mail, Website) mit Typauswahl und eigenen Bezeichnungen */
@Composable
private fun TypedList(
    items: List<TypedValue>,
    types: List<TypeOption>,
    phone: Boolean,
    keyboard: KeyboardType,
    placeholder: String,
    onChange: (Int, TypedValue) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    items.forEachIndexed { i, item ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = item.value,
                onValueChange = { onChange(i, item.copy(value = it)) },
                label = { Text(placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                modifier = Modifier.weight(1f),
            )
            OptionDropdown(
                label = stringResource(R.string.label_type),
                value = typeLabel(item.type, phone).asString(),
                // Eigene Bezeichnungen aus anderen Programmen bleiben wählbar
                options = types + listOfNotNull(
                    TypeOption(item.type.toUiText(), item.type).takeIf { item.type.isNotBlank() && types.none { it.value == item.type } }
                ),
                onSelect = { onChange(i, item.copy(type = it)) },
                modifier = Modifier.width(170.dp),
            )
            IconButton(onClick = { onRemove(i) }) { Icon(Icons.Outlined.RemoveCircleOutline, stringResource(R.string.action_remove)) }
        }
    }
    TextButton(onClick = onAdd) {
        Icon(Icons.Outlined.Add, null)
        Text(stringResource(R.string.add_item, placeholder), Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun AddressEditor(a: PostalAddress, onChange: (PostalAddress) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OptionDropdown(
                label = stringResource(R.string.label_type),
                value = typeLabel(a.type).asString(),
                options = OTHER_TYPES + listOfNotNull(
                    TypeOption(a.type.toUiText(), a.type).takeIf { a.type.isNotBlank() && OTHER_TYPES.none { it.value == a.type } }
                ),
                onSelect = { onChange(a.copy(type = it)) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.RemoveCircleOutline, stringResource(R.string.remove_address)) }
        }
        NameField(stringResource(R.string.field_street), a.street) { onChange(a.copy(street = it)) }
        NameField(stringResource(R.string.field_extended), a.extended) { onChange(a.copy(extended = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = a.postalCode, onValueChange = { onChange(a.copy(postalCode = it)) },
                label = { Text(stringResource(R.string.field_postal_code)) }, singleLine = true, modifier = Modifier.weight(0.4f),
            )
            NameField(stringResource(R.string.field_locality), a.locality, Modifier.weight(0.6f)) { onChange(a.copy(locality = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NameField(stringResource(R.string.field_region), a.region, Modifier.weight(1f)) { onChange(a.copy(region = it)) }
            NameField(stringResource(R.string.field_country), a.country, Modifier.weight(1f)) { onChange(a.copy(country = it)) }
        }
        OutlinedTextField(
            value = a.poBox, onValueChange = { onChange(a.copy(poBox = it)) },
            label = { Text(stringResource(R.string.field_po_box)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionDropdown(
    label: String,
    value: String,
    options: List<TypeOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (o in options) {
                DropdownMenuItem(text = { Text(o.title.asString()) }, onClick = { expanded = false; onSelect(o.value) })
            }
        }
    }
}
