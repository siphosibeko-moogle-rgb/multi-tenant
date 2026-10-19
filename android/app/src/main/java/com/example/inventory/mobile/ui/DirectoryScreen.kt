package com.example.inventory.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.DirectoryApi
import com.example.inventory.api.models.BusinessType
import com.example.inventory.api.models.SupplierContact
import com.example.inventory.api.models.SupplierListing
import com.example.inventory.api.models.SupplierListingOwn
import com.example.inventory.api.models.SupplierListingWrite
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------
// View models
// ---------------------------------------------------------------------------------------

@HiltViewModel
class DirectoryViewModel @Inject constructor(
    private val api: DirectoryApi,
) : PagedListViewModel<SupplierListing>() {

    private var query: String? = null
    private var type: BusinessType? = null

    fun apply(q: String, t: BusinessType?) {
        query = q.trim().ifEmpty { null }
        type = t
        reload()
    }

    override suspend fun fetchPage(cursor: String?): PageResult<SupplierListing> =
        api.searchSupplierDirectory(
            q = query,
            businessType = type,
            cursor = cursor,
            limit = PAGE_SIZE,
        ).toPage({ it.items }, { it.nextCursor })

    private companion object {
        const val PAGE_SIZE = 20
    }
}

@HiltViewModel
class DirectoryDetailViewModel @Inject constructor(
    private val api: DirectoryApi,
) : ViewModel() {

    sealed interface ListingState {
        data object Loading : ListingState
        data class Failed(val error: ApiError) : ListingState
        data class Loaded(val listing: SupplierListing) : ListingState
    }

    sealed interface ContactState {
        data object Idle : ContactState
        data object Loading : ContactState
        data class Revealed(val contact: SupplierContact) : ContactState
        data class Failed(val error: ApiError) : ContactState
    }

    private val _listing = MutableStateFlow<ListingState>(ListingState.Loading)
    val listing: StateFlow<ListingState> = _listing.asStateFlow()

    private val _contact = MutableStateFlow<ContactState>(ContactState.Idle)
    val contact: StateFlow<ContactState> = _contact.asStateFlow()

    fun load(id: UUID) {
        _listing.value = ListingState.Loading
        _contact.value = ContactState.Idle
        viewModelScope.launch {
            _listing.value = try {
                val response = api.getSupplierListing(id)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    ListingState.Loaded(body)
                } else {
                    ListingState.Failed(response.toApiError())
                }
            } catch (e: Exception) {
                ListingState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }

    fun requestContact(id: UUID) {
        if (_contact.value is ContactState.Loading) return
        _contact.value = ContactState.Loading
        viewModelScope.launch {
            _contact.value = try {
                val response = api.requestSupplierContact(id)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    ContactState.Revealed(body)
                } else {
                    ContactState.Failed(response.toApiError())
                }
            } catch (e: Exception) {
                ContactState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }
}

@HiltViewModel
class MyListingViewModel @Inject constructor(
    private val api: DirectoryApi,
) : ViewModel() {

    sealed interface MineState {
        data object Loading : MineState
        data class Failed(val error: ApiError) : MineState
        data object NotListed : MineState
        data class Listed(val listing: SupplierListingOwn) : MineState
    }

    sealed interface SaveState {
        data object Idle : SaveState
        data object Working : SaveState
        data class Failed(val error: ApiError) : SaveState
    }

    private val _mine = MutableStateFlow<MineState>(MineState.Loading)
    val mine: StateFlow<MineState> = _mine.asStateFlow()

    private val _save = MutableStateFlow<SaveState>(SaveState.Idle)
    val save: StateFlow<SaveState> = _save.asStateFlow()

    fun load() {
        _mine.value = MineState.Loading
        viewModelScope.launch {
            _mine.value = try {
                val response = api.getMySupplierListing()
                val body = response.body()
                when {
                    response.isSuccessful && body != null -> MineState.Listed(body)
                    response.code() == 404 -> MineState.NotListed
                    else -> MineState.Failed(response.toApiError())
                }
            } catch (e: Exception) {
                MineState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }

    fun submit(write: SupplierListingWrite, onDone: () -> Unit) {
        if (_save.value is SaveState.Working) return
        _save.value = SaveState.Working
        viewModelScope.launch {
            try {
                val response = api.upsertMySupplierListing(write)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    _mine.value = MineState.Listed(body)
                    _save.value = SaveState.Idle
                    onDone()
                } else {
                    _save.value = SaveState.Failed(response.toApiError())
                }
            } catch (e: Exception) {
                _save.value = SaveState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }

    fun withdraw(onDone: () -> Unit) {
        if (_save.value is SaveState.Working) return
        _save.value = SaveState.Working
        viewModelScope.launch {
            try {
                val response = api.withdrawMySupplierListing()
                if (response.isSuccessful) {
                    _mine.value = MineState.NotListed
                    _save.value = SaveState.Idle
                    onDone()
                } else {
                    _save.value = SaveState.Failed(response.toApiError())
                }
            } catch (e: Exception) {
                _save.value = SaveState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }

    fun clearError() {
        if (_save.value is SaveState.Failed) _save.value = SaveState.Idle
    }
}

// ---------------------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------------------

private enum class DirectoryPage { LIST, DETAIL, MINE, FORM }

internal fun businessTypeLabel(type: BusinessType): String = humanise(type.value.lowercase())

@Composable
fun SupplierDirectoryScreen(
    loadKey: Int,
    role: String?,
    tenantName: String,
    onBack: () -> Unit,
    viewModel: DirectoryViewModel = hiltViewModel(),
) {
    var page by rememberSaveable { mutableStateOf(DirectoryPage.LIST) }
    var openListing by rememberSaveable { mutableStateOf<String?>(null) }
    val canList = canEditCatalog(role) // owner or manager

    when (page) {
        DirectoryPage.DETAIL -> {
            val id = openListing
            if (id != null) {
                DirectoryDetailScreen(
                    listingId = UUID.fromString(id),
                    canRequestContact = canList,
                    onBack = { page = DirectoryPage.LIST },
                )
                return
            }
            page = DirectoryPage.LIST
        }
        DirectoryPage.MINE, DirectoryPage.FORM -> {
            MyListingScreen(
                tenantName = tenantName,
                startInForm = page == DirectoryPage.FORM,
                onBack = { page = DirectoryPage.LIST },
            )
            return
        }
        DirectoryPage.LIST -> Unit
    }

    var query by rememberSaveable { mutableStateOf("") }
    var typeName by rememberSaveable { mutableStateOf<String?>(null) }
    val type = typeName?.let { name -> BusinessType.entries.firstOrNull { it.value == name } }

    LaunchedEffect(query, typeName, loadKey) {
        if (query.isNotEmpty()) delay(300) // debounce typing
        viewModel.apply(query, type)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            SubScreenHeader(
                "Supplier directory",
                onBack,
                subtitle = "Businesses that chose to be listed. Contact details are shown only after you request them.",
            )
            if (canList) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { page = DirectoryPage.MINE }) { Text("My listing") }
                    OutlinedButton(onClick = { page = DirectoryPage.FORM }) { Text("Become a supplier") }
                }
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by name or what they supply") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf<Pair<String?, String>>(null to "All") +
                    BusinessType.entries.map { it.value to businessTypeLabel(it) },
                selected = typeName,
                onSelect = { typeName = it },
            )
            Spacer(Modifier.height(12.dp))
        }

        when (val current = state) {
            is PagedListViewModel.UiState.Loading -> item { LoadingState() }
            is PagedListViewModel.UiState.Failed -> item { ErrorState(current.error) { viewModel.reload() } }
            is PagedListViewModel.UiState.Loaded -> {
                if (current.items.isEmpty()) {
                    item {
                        EmptyState(
                            if (query.isBlank() && type == null) {
                                "No suppliers are listed yet."
                            } else {
                                "No suppliers match your search."
                            },
                        )
                    }
                }
                items(current.items, key = { it.id }) { listing ->
                    ListingCard(listing) {
                        openListing = listing.id.toString()
                        page = DirectoryPage.DETAIL
                    }
                    HorizontalDivider()
                }
                item {
                    LoadMoreFooter(
                        nextCursor = current.nextCursor,
                        loadingMore = current.loadingMore,
                        error = current.moreError,
                        onLoadMore = { viewModel.loadMore() },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ListingCard(listing: SupplierListing, onOpen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listing.businessName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusTag(businessTypeLabel(listing.businessType))
        }
        Text(listing.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        Text(listing.category, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun DirectoryDetailScreen(
    listingId: UUID,
    canRequestContact: Boolean,
    onBack: () -> Unit,
    viewModel: DirectoryDetailViewModel = hiltViewModel(),
) {
    val listingState by viewModel.listing.collectAsStateWithLifecycle()
    val contactState by viewModel.contact.collectAsStateWithLifecycle()
    LaunchedEffect(listingId) { viewModel.load(listingId) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        SubScreenHeader(
            (listingState as? DirectoryDetailViewModel.ListingState.Loaded)?.listing?.businessName ?: "Supplier",
            onBack,
        )
        when (val current = listingState) {
            is DirectoryDetailViewModel.ListingState.Loading -> LoadingState()
            is DirectoryDetailViewModel.ListingState.Failed -> ErrorState(current.error) { viewModel.load(listingId) }
            is DirectoryDetailViewModel.ListingState.Loaded -> {
                val listing = current.listing
                DetailRow("Business type", businessTypeLabel(listing.businessType))
                DetailRow("Contact person", listing.contactName)
                DetailRow("Category", listing.category)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("What they supply", style = MaterialTheme.typography.titleMedium)
                Text(listing.description, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                when (val contact = contactState) {
                    is DirectoryDetailViewModel.ContactState.Revealed ->
                        ContactCard(contact.contact)
                    else -> {
                        if (!canRequestContact) {
                            Text(
                                "Only owners and managers can request contact details.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                "Phone and email are hidden. Requesting them lets this supplier's business " +
                                    "see that your business asked.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            if (contact is DirectoryDetailViewModel.ContactState.Failed) {
                                InlineError(contact.error)
                            }
                            Button(
                                onClick = { viewModel.requestContact(listingId) },
                                enabled = contact !is DirectoryDetailViewModel.ContactState.Loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (contact is DirectoryDetailViewModel.ContactState.Loading) {
                                        "Requesting…"
                                    } else {
                                        "Request contact"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ContactCard(contact: SupplierContact) {
    val context = LocalContext.current

    fun launch(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // no dialer / mail app installed; nothing sensible to do
        }
    }

    Text("Contact details", style = MaterialTheme.typography.titleMedium)
    DetailRow("Phone", contact.phone)
    DetailRow("Email", contact.email)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phone}"))) }) {
            Text("Call")
        }
        OutlinedButton(onClick = { launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${contact.email}"))) }) {
            Text("Email")
        }
    }
}

@Composable
fun MyListingScreen(
    tenantName: String,
    startInForm: Boolean,
    onBack: () -> Unit,
    viewModel: MyListingViewModel = hiltViewModel(),
) {
    val mine by viewModel.mine.collectAsStateWithLifecycle()
    val save by viewModel.save.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(startInForm) }
    var confirmWithdraw by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.load() }

    val existing = (mine as? MyListingViewModel.MineState.Listed)?.listing

    if (editing && mine !is MyListingViewModel.MineState.Loading) {
        ListingForm(
            existing = existing,
            defaultBusinessName = tenantName,
            save = save,
            onSubmit = { viewModel.submit(it) { editing = false } },
            onCancel = {
                viewModel.clearError()
                if (existing == null && startInForm) onBack() else editing = false
            },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        SubScreenHeader("My listing", onBack)
        when (val current = mine) {
            is MyListingViewModel.MineState.Loading -> LoadingState()
            is MyListingViewModel.MineState.Failed -> ErrorState(current.error) { viewModel.load() }
            is MyListingViewModel.MineState.NotListed -> {
                Text(
                    "Your business is not in the supplier directory. List it so other businesses can find you.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { editing = true }) { Text("Become a supplier") }
            }
            is MyListingViewModel.MineState.Listed -> {
                val l = current.listing
                if (l.isPublished) StatusTag("Listed") else StatusTag("Not published")
                Spacer(Modifier.height(8.dp))
                DetailRow("Business", l.businessName)
                DetailRow("Business type", businessTypeLabel(l.businessType))
                DetailRow("Contact person", l.contactName)
                DetailRow("Phone", l.phone)
                DetailRow("Email", l.email)
                DetailRow("Category", l.category)
                Text(l.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                if (save is MyListingViewModel.SaveState.Failed) {
                    InlineError((save as MyListingViewModel.SaveState.Failed).error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = true }) { Text("Edit") }
                    OutlinedButton(
                        onClick = { confirmWithdraw = true },
                        enabled = save !is MyListingViewModel.SaveState.Working,
                    ) { Text("Withdraw listing") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmWithdraw) {
        AlertDialog(
            onDismissRequest = { confirmWithdraw = false },
            title = { Text("Withdraw listing?") },
            text = {
                Text(
                    "Your listing and contact details will be removed from the directory. " +
                        "You can list again at any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWithdraw = false
                    viewModel.withdraw {}
                }) { Text("Withdraw") }
            },
            dismissButton = { TextButton(onClick = { confirmWithdraw = false }) { Text("Cancel") } },
        )
    }
}

private val PHONE_REGEX = Regex("^\\+?[0-9 ()-]{7,20}$")

@Composable
private fun ListingForm(
    existing: SupplierListingOwn?,
    defaultBusinessName: String,
    save: MyListingViewModel.SaveState,
    onSubmit: (SupplierListingWrite) -> Unit,
    onCancel: () -> Unit,
) {
    var businessName by rememberSaveable { mutableStateOf(existing?.businessName ?: defaultBusinessName) }
    var typeValue by rememberSaveable {
        mutableStateOf(existing?.businessType?.value ?: BusinessType.entries.first().value)
    }
    var contactName by rememberSaveable { mutableStateOf(existing?.contactName ?: "") }
    var phone by rememberSaveable { mutableStateOf(existing?.phone ?: "") }
    var email by rememberSaveable { mutableStateOf(existing?.email ?: "") }
    var description by rememberSaveable { mutableStateOf(existing?.description ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    val phoneOk = PHONE_REGEX.matches(phone.trim())
    val emailOk = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val valid = businessName.isNotBlank() && contactName.isNotBlank() && phoneOk && emailOk &&
        description.isNotBlank() && category.isNotBlank() && consent
    val working = save is MyListingViewModel.SaveState.Working

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        SubScreenHeader(if (existing == null) "Become a supplier" else "Edit listing", onCancel)

        OutlinedTextField(
            value = businessName, onValueChange = { businessName = it },
            label = { Text("Business name") }, singleLine = true,
            isError = showErrors && businessName.isBlank(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("Business type", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(
            options = BusinessType.entries.map { it.value to businessTypeLabel(it) },
            selected = typeValue,
            onSelect = { typeValue = it },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = contactName, onValueChange = { contactName = it },
            label = { Text("Contact name") }, singleLine = true,
            isError = showErrors && contactName.isBlank(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("Phone") }, singleLine = true,
            isError = showErrors && !phoneOk,
            supportingText = { if (showErrors && !phoneOk) Text("Enter a valid phone number") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, singleLine = true,
            isError = showErrors && !emailOk,
            supportingText = { if (showErrors && !emailOk) Text("Enter a valid email address") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = category, onValueChange = { category = it.take(60) },
            label = { Text("Category (for example Cleaning)") }, singleLine = true,
            isError = showErrors && category.isBlank(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = description, onValueChange = { description = it.take(500) },
            label = { Text("What do you supply?") },
            isError = showErrors && description.isBlank(),
            supportingText = { Text("${description.length}/500") },
            minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = consent, onCheckedChange = { consent = it })
            Text(
                "Other businesses can request these details (your phone number and email). " +
                    "You can withdraw your listing at any time.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (showErrors && !consent) {
            Text(
                "Please tick the box to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (save is MyListingViewModel.SaveState.Failed) InlineError(save.error)

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                showErrors = true
                if (valid) {
                    onSubmit(
                        SupplierListingWrite(
                            businessName = businessName.trim(),
                            businessType = BusinessType.entries.first { it.value == typeValue },
                            contactName = contactName.trim(),
                            phone = phone.trim(),
                            email = email.trim(),
                            description = description.trim(),
                            category = category.trim(),
                            consentGiven = true,
                        ),
                    )
                }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (working) "Saving…" else if (existing == null) "Publish listing" else "Save changes") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        Spacer(Modifier.height(16.dp))
    }
}
