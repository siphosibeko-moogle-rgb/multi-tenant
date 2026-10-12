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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.PurchasingApi
import com.example.inventory.api.models.PurchaseOrder
import com.example.inventory.api.models.PurchaseOrderDetail
import com.example.inventory.api.models.PurchaseOrderDetailAllOfLines
import com.example.inventory.api.models.PurchaseOrdersPoIdReceiptsPostRequest
import com.example.inventory.api.models.PurchaseOrdersPoIdReceiptsPostRequestLinesInner
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PurchaseOrdersViewModel @Inject constructor(
    private val purchasing: PurchasingApi,
) : PagedListViewModel<PurchaseOrder>() {

    var status by mutableStateOf<PurchasingApi.StatusPurchaseOrdersGet?>(null)
        private set

    fun selectStatus(status: PurchasingApi.StatusPurchaseOrdersGet?) {
        if (status == this.status) return
        this.status = status
        reload()
    }

    override suspend fun fetchPage(cursor: String?): PageResult<PurchaseOrder> =
        purchasing.purchaseOrdersGet(cursor = cursor, limit = 50, status = status)
            .toPage({ it.items }, { it.nextCursor })
}

@HiltViewModel
class PurchaseOrderDetailViewModel @Inject constructor(
    private val purchasing: PurchasingApi,
) : ViewModel() {

    enum class Pending { MARKING_ORDERED, RECEIVING }

    sealed interface UiState {
        data object Loading : UiState
        data class Failed(val error: ApiError) : UiState
        data class Loaded(
            val order: PurchaseOrderDetail,
            val pending: Pending? = null,
            val actionError: ApiError? = null,
            val receiptMessage: String? = null,
            val notice: String? = null,
            val receipts: Int = 0,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var receiptKey: Pair<List<PurchaseOrdersPoIdReceiptsPostRequestLinesInner>, UUID>? = null

    fun load(poId: UUID) {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val response = purchasing.purchaseOrdersPoIdGet(poId)
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

    fun markOrdered() {
        val current = _state.value as? UiState.Loaded ?: return
        if (current.pending != null) return
        _state.value = current.copy(pending = Pending.MARKING_ORDERED, actionError = null, notice = null)
        viewModelScope.launch {
            try {
                val response = purchasing.purchaseOrdersPoIdSubmitPost(current.order.id)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    _state.value = UiState.Loaded(body, notice = "${body.poNumber} is now marked as ordered.")
                } else {
                    failed(current.order.id, response.toApiError())
                }
            } catch (e: Exception) {
                failed(current.order.id, ApiError.fromNetworkFailure(e), unknownOutcome = true)
            }
        }
    }

    fun receive(inputs: Map<UUID, String>) {
        val current = _state.value as? UiState.Loaded ?: return
        if (current.pending != null) return
        val lines = when (val check = checkReceipt(current.order.lines, inputs)) {
            is ReceiptCheck.Invalid -> {
                _state.value = current.copy(receiptMessage = check.message)
                return
            }
            is ReceiptCheck.Ok -> check.lines
        }
        val key = receiptKey?.takeIf { it.first == lines }?.second ?: UUID.randomUUID()
        receiptKey = lines to key

        _state.value = current.copy(pending = Pending.RECEIVING, actionError = null, receiptMessage = null, notice = null)
        viewModelScope.launch {
            try {
                val response = purchasing.purchaseOrdersPoIdReceiptsPost(
                    current.order.id,
                    PurchaseOrdersPoIdReceiptsPostRequest(lines = lines),
                    idempotencyKey = key,
                )
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    receiptKey = null
                    _state.value = UiState.Loaded(
                        body,
                        notice = "Stock received. ${body.poNumber} is now ${humanise(body.status.value).lowercase()}.",
                        receipts = current.receipts + 1,
                    )
                } else {
                    failed(current.order.id, response.toApiError(), forReceipt = true)
                }
            } catch (e: Exception) {
                failed(current.order.id, ApiError.fromNetworkFailure(e), forReceipt = true, unknownOutcome = true)
            }
        }
    }

    private suspend fun failed(
        poId: UUID,
        error: ApiError,
        forReceipt: Boolean = false,
        unknownOutcome: Boolean = false,
    ) {
        val explained = if (unknownOutcome) {
            ApiError(
                "The connection dropped, so it is not known whether that was saved. " +
                    "The order has been re-read — check it before trying again.",
                error.message,
            )
        } else {
            error
        }
        val latest = try {
            purchasing.purchaseOrdersPoIdGet(poId).takeIf { it.isSuccessful }?.body()
        } catch (e: Exception) {
            null
        }
        val previous = _state.value as? UiState.Loaded ?: return
        _state.value = previous.copy(
            order = latest ?: previous.order,
            pending = null,
            actionError = if (forReceipt) null else explained,
            receiptMessage = if (forReceipt) listOfNotNull(explained.message, explained.detail).joinToString(" ") else null,
        )
    }

    fun clearNotice() {
        val current = _state.value as? UiState.Loaded ?: return
        _state.value = current.copy(notice = null, actionError = null, receiptMessage = null)
    }
}

@Composable
fun PurchaseOrdersScreen(
    loadKey: Int,
    onBack: () -> Unit,
    role: String?,
    timezone: String?,
    onStockChanged: () -> Unit,
    viewModel: PurchaseOrdersViewModel = hiltViewModel(),
) {
    var openOrder by rememberSaveable { mutableStateOf<String?>(null) }
    val displayZone = (businessZoneOf(timezone) as? BusinessZone.Known)?.zone

    openOrder?.let { id ->
        PurchaseOrderDetailScreen(
            poId = UUID.fromString(id),
            role = role,
            displayZone = displayZone,
            onStockChanged = onStockChanged,
            onBack = { openOrder = null },
        )
        return
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(loadKey) { viewModel.loadFor(loadKey) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            SubScreenHeader("Purchase Orders", onBack)
            ChoiceRow(
                options = listOf<Pair<PurchasingApi.StatusPurchaseOrdersGet?, String>>(null to "All") +
                    PurchasingApi.StatusPurchaseOrdersGet.entries.map { it to humanise(it.value) },
                selected = viewModel.status,
                onSelect = viewModel::selectStatus,
            )
            Spacer(Modifier.height(8.dp))
        }

        when (val current = state) {
            is PagedListViewModel.UiState.Loading -> item { LoadingState() }
            is PagedListViewModel.UiState.Failed -> item { ErrorState(current.error) { viewModel.reload() } }
            is PagedListViewModel.UiState.Loaded -> {
                if (current.items.isEmpty()) {
                    item {
                        EmptyState(
                            if (viewModel.status == null) {
                                "No purchase orders yet. Create one from a recommendation on the Reorders tab."
                            } else {
                                "No purchase orders with this status."
                            },
                        )
                    }
                }
                items(current.items, key = { it.id }) { order ->
                    PurchaseOrderRow(order, displayZone) { openOrder = order.id.toString() }
                    HorizontalDivider()
                }
                item {
                    LoadMoreFooter(current.nextCursor, current.loadingMore, current.moreError) {
                        viewModel.loadMore()
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PurchaseOrderRow(order: PurchaseOrder, displayZone: java.time.ZoneId?, onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(order.poNumber, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(plain(order.totalAmount), style = MaterialTheme.typography.titleMedium)
        }
        Text(order.supplierName, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusTag(humanise(order.status.value))
            Text(
                orderDateLine(order.orderedAt, order.expectedAt, order.lineCount, displayZone),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun orderDateLine(
    orderedAt: java.time.OffsetDateTime?,
    expectedAt: java.time.LocalDate?,
    lineCount: Int,
    displayZone: java.time.ZoneId?,
): String = listOfNotNull(
    orderedAt?.let { "Ordered ${formatDateTime(it, displayZone)}" } ?: "Not yet placed",
    expectedAt?.let { "due ${formatDate(it)}" },
    "$lineCount line${if (lineCount == 1) "" else "s"}",
).joinToString(" · ")

@Composable
fun PurchaseOrderDetailScreen(
    poId: UUID,
    role: String?,
    displayZone: java.time.ZoneId?,
    onStockChanged: () -> Unit,
    onBack: () -> Unit,
    viewModel: PurchaseOrderDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var receiving by rememberSaveable(poId) { mutableStateOf(false) }
    var confirmOrdered by rememberSaveable(poId) { mutableStateOf(false) }
    LaunchedEffect(poId) { viewModel.load(poId) }

    val receipts = (state as? PurchaseOrderDetailViewModel.UiState.Loaded)?.receipts ?: 0
    LaunchedEffect(receipts) {
        if (receipts > 0) {
            receiving = false
            onStockChanged()
        }
    }

    val loaded = state as? PurchaseOrderDetailViewModel.UiState.Loaded
    if (receiving && loaded != null) {
        ReceiveStockScreen(
            order = loaded.order,
            saving = loaded.pending == PurchaseOrderDetailViewModel.Pending.RECEIVING,
            message = loaded.receiptMessage,
            onSubmit = viewModel::receive,
            onBack = {
                viewModel.clearNotice()
                receiving = false
            },
        )
        return
    }

    if (confirmOrdered && loaded != null) {
        AlertDialog(
            onDismissRequest = { confirmOrdered = false },
            title = { Text("Mark ${loaded.order.poNumber} as ordered?") },
            text = {
                Text(
                    "Records that the order has been placed with ${loaded.order.supplierName}, dated now. " +
                        "That date starts the supplier's measured lead time. It cannot be undone in the app.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOrdered = false
                    viewModel.markOrdered()
                }) { Text("Mark as ordered") }
            },
            dismissButton = { TextButton(onClick = { confirmOrdered = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            SubScreenHeader(
                (state as? PurchaseOrderDetailViewModel.UiState.Loaded)?.order?.poNumber ?: "Purchase order",
                onBack,
            )
        }
        when (val current = state) {
            is PurchaseOrderDetailViewModel.UiState.Loading -> item { LoadingState() }
            is PurchaseOrderDetailViewModel.UiState.Failed -> item { ErrorState(current.error) { viewModel.load(poId) } }
            is PurchaseOrderDetailViewModel.UiState.Loaded -> {
                val order = current.order
                val actions = purchaseOrderActions(order, role)
                val busy = current.pending != null
                item {
                    StatusTag(humanise(order.status.value))
                    Spacer(Modifier.height(8.dp))
                    current.notice?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    current.actionError?.let { InlineError(it) }
                    if (actions.markOrdered) {
                        Button(
                            onClick = { confirmOrdered = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (current.pending == PurchaseOrderDetailViewModel.Pending.MARKING_ORDERED) {
                                    "Saving…"
                                } else {
                                    "Mark as ordered"
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (actions.receive) {
                        Button(
                            onClick = {
                                viewModel.clearNotice()
                                receiving = true
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Receive stock") }
                        Spacer(Modifier.height(8.dp))
                    }
                    DetailRow("Supplier", order.supplierName)
                    DetailRow("Placed", order.orderedAt?.let { formatDateTime(it, displayZone) } ?: "Not yet — still a draft")
                    order.expectedAt?.let { DetailRow("Expected", formatDate(it)) }
                    order.receivedAt?.let { DetailRow("Received", formatDateTime(it, displayZone)) }
                    DetailRow("Total", plain(order.totalAmount))
                    order.notes?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Lines", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    QuantityHeader()
                    HorizontalDivider()
                }
                items(order.lines) { line ->
                    PurchaseOrderLineRow(line)
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun QuantityHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Product", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        QuantityCell("Ordered", header = true)
        QuantityCell("Received", header = true)
        QuantityCell("To come", header = true)
    }
}

@Composable
private fun PurchaseOrderLineRow(line: PurchaseOrderDetailAllOfLines) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(line.name, style = MaterialTheme.typography.bodyLarge)
            Text("${line.sku} · ${plain(line.unitCost)} each", style = MaterialTheme.typography.bodySmall)
        }
        QuantityCell(plain(line.quantityOrdered))
        QuantityCell(plain(line.quantityReceived))
        QuantityCell(plain(line.quantityOutstanding))
    }
}

@Composable
private fun QuantityCell(text: String, header: Boolean = false) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.End,
        modifier = Modifier.width(68.dp),
    )
}
