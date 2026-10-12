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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.models.Sale
import com.example.inventory.api.models.SaleDetail
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SalesPeriod(val label: String, val days: Long?) {
    ALL("All time", null),
    TODAY("Today", 0),
    WEEK("Last 7 days", 6),
    MONTH("Last 30 days", 29),
}

@HiltViewModel
class SalesHistoryViewModel @Inject constructor(
    private val sales: SalesApi,
) : PagedListViewModel<Sale>() {

    var status by mutableStateOf<SalesApi.StatusSalesGet?>(null)
        private set
    var period by mutableStateOf(SalesPeriod.ALL)
        private set

    internal var clock: Clock = Clock.systemUTC()

    var businessZone by mutableStateOf<BusinessZone>(BusinessZone.NotLoaded)
        private set

    fun setFilters(status: SalesApi.StatusSalesGet?, period: SalesPeriod) {
        if (status == this.status && period == this.period) return
        if (period != SalesPeriod.ALL && businessZone !is BusinessZone.Known) return
        this.status = status
        this.period = period
        reload()
    }

    fun updateBusinessZone(zone: BusinessZone) {
        if (zone == businessZone) return
        businessZone = zone
        if (period != SalesPeriod.ALL && zone !is BusinessZone.Known) {
            period = SalesPeriod.ALL
            reload()
        } else if (period != SalesPeriod.ALL) {
            reload()
        }
    }

    override suspend fun fetchPage(cursor: String?): PageResult<Sale> {
        val range = when (val zone = businessZone) {
            is BusinessZone.Known -> period.range(businessToday(clock, zone.zone))
            else -> if (period == SalesPeriod.ALL) {
                null
            } else {
                return PageResult.Err(ApiError("The business time zone is not available, so date filters cannot be applied."))
            }
        }
        return sales.salesGet(cursor = cursor, limit = 50, from = range?.first, to = range?.second, status = status)
            .toPage({ it.items }, { it.nextCursor })
    }
}

@HiltViewModel
class SaleDetailViewModel @Inject constructor(
    private val sales: SalesApi,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Failed(val error: ApiError) : UiState
        data class Loaded(val sale: SaleDetail) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(saleId: UUID) {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val response = sales.salesSaleIdGet(saleId)
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

@Composable
fun SalesHistoryScreen(
    loadKey: Int,
    onBack: () -> Unit,
    timezone: String?,
    viewModel: SalesHistoryViewModel = hiltViewModel(),
) {
    var openSale by rememberSaveable { mutableStateOf<String?>(null) }
    val zone = businessZoneOf(timezone)
    val displayZone = (zone as? BusinessZone.Known)?.zone

    openSale?.let { id ->
        SaleDetailScreen(saleId = UUID.fromString(id), displayZone = displayZone, onBack = { openSale = null })
        return
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(zone) { viewModel.updateBusinessZone(zone) }
    LaunchedEffect(loadKey) { viewModel.loadFor(loadKey) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            SubScreenHeader("Sales History", onBack)
            ChoiceRow(
                options = SalesPeriod.entries.map { it to it.label },
                selected = viewModel.period,
                onSelect = { viewModel.setFilters(viewModel.status, it) },
                enabled = { it == SalesPeriod.ALL || zone is BusinessZone.Known },
            )
            when (zone) {
                is BusinessZone.Known -> Unit
                is BusinessZone.NotLoaded -> Text(
                    "Date filters are available once the business time zone has loaded.",
                    style = MaterialTheme.typography.bodySmall,
                )
                is BusinessZone.Invalid -> Text(
                    "Date filters are off: the business time zone \"${zone.raw}\" is not recognised. " +
                        "It needs to be set to a zone such as Africa/Johannesburg.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ChoiceRow(
                options = listOf<Pair<SalesApi.StatusSalesGet?, String>>(null to "Any status") +
                    SalesApi.StatusSalesGet.entries.map { it to humanise(it.value) },
                selected = viewModel.status,
                onSelect = { viewModel.setFilters(it, viewModel.period) },
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
                            if (viewModel.status == null && viewModel.period == SalesPeriod.ALL) {
                                "No sales have been recorded yet."
                            } else {
                                "No sales match these filters."
                            },
                        )
                    }
                }
                items(current.items, key = { it.id }) { sale ->
                    SaleRow(sale, displayZone) { openSale = sale.id.toString() }
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
private fun SaleRow(sale: Sale, displayZone: ZoneId?, onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(sale.saleNumber, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(plain(sale.totalAmount), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusTag(humanise(sale.status.value))
            Text(
                "${formatDateTime(sale.soldAt, displayZone)} · ${sale.itemCount} item${if (sale.itemCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        sale.customerName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun SaleDetailScreen(
    saleId: UUID,
    displayZone: ZoneId?,
    onBack: () -> Unit,
    viewModel: SaleDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(saleId) { viewModel.load(saleId) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            SubScreenHeader((state as? SaleDetailViewModel.UiState.Loaded)?.sale?.saleNumber ?: "Sale", onBack)
        }
        when (val current = state) {
            is SaleDetailViewModel.UiState.Loading -> item { LoadingState() }
            is SaleDetailViewModel.UiState.Failed -> item { ErrorState(current.error) { viewModel.load(saleId) } }
            is SaleDetailViewModel.UiState.Loaded -> {
                val sale = current.sale
                item {
                    StatusTag(humanise(sale.status.value))
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Sold", formatDateTime(sale.soldAt, displayZone))
                    sale.createdByName?.let { DetailRow("Recorded by", it) }
                    sale.customerName?.let { DetailRow("Customer", it) }
                    sale.paymentMethod?.let { DetailRow("Payment", humanise(it)) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Items", style = MaterialTheme.typography.titleMedium)
                }
                items(sale.lines) { line ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(line.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(plain(line.lineTotal), style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(
                            "${line.sku} · ${plain(line.quantity)} × ${plain(line.unitPrice)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (line.returnedQuantity.signum() > 0) {
                            Text(
                                "Returned ${plain(line.returnedQuantity)} · ${plain(line.outstandingQuantity)} still sold",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Subtotal", plain(sale.subtotalAmount))
                    if (sale.discountAmount.signum() != 0) DetailRow("Discount", plain(sale.discountAmount))
                    DetailRow("Tax", plain(sale.taxAmount))
                    DetailRow("Total", plain(sale.totalAmount))
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
