package com.example.inventory.mobile.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.PurchasingApi
import com.example.inventory.api.apis.SuppliersApi
import com.example.inventory.api.models.PurchaseOrder
import com.example.inventory.api.models.SupplierDetail
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SuppliersViewModel @Inject constructor(
    private val purchasing: PurchasingApi,
    private val suppliers: SuppliersApi,
) : ViewModel() {

    data class Entry(
        val id: UUID,
        val name: String,
        val orderCount: Int,
        val detail: SupplierDetail?,
    )

    sealed interface UiState {
        data object Loading : UiState
        data class Failed(val error: ApiError) : UiState
        data class Loaded(
            val entries: List<Entry>,
            val truncated: Boolean,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadedKey: Int? = null

    fun loadFor(key: Int) {
        if (key == loadedKey && _state.value !is UiState.Failed) return
        loadedKey = key
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                scan()
            } catch (e: Exception) {
                UiState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }

    private suspend fun scan(): UiState {
        val orders = mutableListOf<PurchaseOrder>()
        var cursor: String? = null
        var pages = 0
        do {
            val response = purchasing.purchaseOrdersGet(cursor = cursor, limit = PAGE_SIZE)
            val body = response.body()
            if (!response.isSuccessful || body == null) return UiState.Failed(response.toApiError())
            orders += body.items
            cursor = body.nextCursor
            pages++
        } while (cursor != null && pages < MAX_PAGES)

        val bySupplier = orders.groupBy { it.supplierId }
        val details = coroutineScope {
            bySupplier.keys.map { id ->
                async {
                    id to runCatching { suppliers.suppliersSupplierIdGet(id).takeIf { it.isSuccessful }?.body() }
                        .getOrNull()
                }
            }.awaitAll().toMap()
        }

        val entries = bySupplier.map { (id, list) ->
            Entry(
                id = id,
                name = details[id]?.name ?: list.first().supplierName,
                orderCount = list.size,
                detail = details[id],
            )
        }.sortedBy { it.name.lowercase() }

        return UiState.Loaded(entries, truncated = cursor != null)
    }

    companion object {
        private const val PAGE_SIZE = 200
        private const val MAX_PAGES = 5
    }
}

@HiltViewModel
class SupplierDetailViewModel @Inject constructor(
    private val suppliers: SuppliersApi,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Failed(val error: ApiError) : UiState
        data class Loaded(val supplier: SupplierDetail) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(supplierId: UUID) {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val response = suppliers.suppliersSupplierIdGet(supplierId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    UiState.Loaded(body)
                } else {
                    UiState.Failed(response.toApiError())
                }
            } catch (e: Exception) {
                UiState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }
}

internal fun SuppliersViewModel.Entry.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return listOfNotNull(name, detail?.contactName, detail?.email, detail?.phone)
        .any { it.contains(q, ignoreCase = true) }
}

@Composable
fun SuppliersScreen(
    loadKey: Int,
    onBack: () -> Unit,
    viewModel: SuppliersViewModel = hiltViewModel(),
) {
    var openSupplier by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    openSupplier?.let { id ->
        SupplierDetailScreen(supplierId = UUID.fromString(id), onBack = { openSupplier = null })
        return
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(loadKey) { viewModel.loadFor(loadKey) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            SubScreenHeader("Suppliers", onBack)
            Text(
                "Showing suppliers on your purchase orders. The server cannot list all suppliers " +
                    "or add new ones yet, so a supplier you have never ordered from will not appear here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
        }

        when (val current = state) {
            is SuppliersViewModel.UiState.Loading -> item { LoadingState() }
            is SuppliersViewModel.UiState.Failed -> item { ErrorState(current.error) { viewModel.load() } }
            is SuppliersViewModel.UiState.Loaded -> {
                if (current.entries.isEmpty()) {
                    item { EmptyState("No suppliers found. Suppliers appear here once a purchase order uses them.") }
                } else {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search suppliers") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (current.truncated) {
                            Text(
                                "Only your most recent purchase orders were checked.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    val shown = current.entries.filter { it.matches(query) }
                    if (shown.isEmpty()) {
                        item { EmptyState("No suppliers match \"${query.trim()}\".") }
                    }
                    items(shown, key = { it.id }) { entry ->
                        SupplierRow(entry) { openSupplier = entry.id.toString() }
                        HorizontalDivider()
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SupplierRow(entry: SuppliersViewModel.Entry, onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (entry.detail?.isActive == false) StatusTag("Inactive")
        }
        val detail = entry.detail
        if (detail == null) {
            Text("Contact details could not be loaded", style = MaterialTheme.typography.bodySmall)
        } else {
            val contact = listOfNotNull(detail.contactName, detail.phone, detail.email).joinToString(" · ")
            Text(contact.ifEmpty { "No contact details on file" }, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "${entry.orderCount} purchase order${if (entry.orderCount == 1) "" else "s"}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun SupplierDetailScreen(
    supplierId: UUID,
    onBack: () -> Unit,
    viewModel: SupplierDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(supplierId) { viewModel.load(supplierId) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            SubScreenHeader((state as? SupplierDetailViewModel.UiState.Loaded)?.supplier?.name ?: "Supplier", onBack)
            when (val current = state) {
                is SupplierDetailViewModel.UiState.Loading -> LoadingState()
                is SupplierDetailViewModel.UiState.Failed -> ErrorState(current.error) { viewModel.load(supplierId) }
                is SupplierDetailViewModel.UiState.Loaded -> SupplierDetailBody(current.supplier)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SupplierDetailBody(supplier: SupplierDetail) {
    if (!supplier.isActive) {
        StatusTag("Inactive")
        Spacer(Modifier.height(8.dp))
    }
    Text("Contact", style = MaterialTheme.typography.titleMedium)
    DetailRow("Name", supplier.contactName ?: "—")
    DetailRow("Phone", supplier.phone ?: "—")
    DetailRow("Email", supplier.email ?: "—")
    supplier.address?.takeIf { it.isNotBlank() }?.let { DetailRow("Address", it) }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Ordering", style = MaterialTheme.typography.titleMedium)
    DetailRow("Quoted lead time", "${supplier.leadTimeDays} days")
    val observed = supplier.observedLeadTime
    DetailRow(
        "Measured lead time",
        observed.averageDays?.let {
            "${it.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()} days " +
                "(${observed.sampleSize} deliver${if (observed.sampleSize == 1) "y" else "ies"})"
        } ?: "No deliveries yet",
    )
    supplier.minOrderValue?.let { DetailRow("Minimum order", plain(it)) }
    DetailRow("Products supplied", supplier.productCount.toString())

    supplier.notes?.takeIf { it.isNotBlank() }?.let {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Notes", style = MaterialTheme.typography.titleMedium)
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
}
