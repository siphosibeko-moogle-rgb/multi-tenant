package com.example.inventory.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.CatalogApi
import com.example.inventory.api.models.Product
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@HiltViewModel
class ProductListViewModel @Inject constructor(
    private val catalog: CatalogApi,
    private val inventory: com.example.inventory.api.apis.InventoryApi,
) : ViewModel() {

    data class UiState(
        val products: List<Product> = emptyList(),
        val loadingFirstPage: Boolean = true,
        val loadingNextPage: Boolean = false,
        val error: ApiError? = null,
        val nextCursor: String? = null,
        val daysOfCover: Map<java.util.UUID, java.math.BigDecimal> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = products.isEmpty() && !loadingFirstPage && error == null

        val canLoadMore: Boolean get() = nextCursor != null && !loadingNextPage && error == null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load(reset = true)
    }

    fun reload() = load(reset = true)

    fun retry() = load(reset = true)

    fun loadNextPage() {
        if (!_state.value.canLoadMore) {
            return
        }
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        val current = _state.value
        if (reset) {
            _state.value = current.copy(
                loadingFirstPage = true,
                error = null,
                products = emptyList(),
                nextCursor = null,
            )
        } else {
            _state.value = current.copy(loadingNextPage = true, error = null)
        }

        viewModelScope.launch {
            try {
                val cursor = if (reset) null else _state.value.nextCursor
                val response = catalog.productsGet(cursor = cursor, limit = PAGE_SIZE)

                if (!response.isSuccessful) {
                    fail(response.toApiError(), reset)
                    return@launch
                }

                val page = response.body()
                val newItems = page?.items.orEmpty()

                _state.value = _state.value.copy(
                    products = if (reset) newItems else _state.value.products + newItems,
                    loadingFirstPage = false,
                    loadingNextPage = false,
                    error = null,
                    nextCursor = page?.nextCursor,
                )

                loadDaysOfCover()
            } catch (e: Exception) {
                fail(ApiError.fromNetworkFailure(e), reset)
            }
        }
    }

    private suspend fun loadDaysOfCover() {
        try {
            val response = inventory.inventoryGet(limit = PAGE_SIZE)
            if (!response.isSuccessful) {
                return
            }
            val byProduct = response.body()?.items.orEmpty()
                .mapNotNull { status -> status.daysOfCover?.let { status.productId to it } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, covers) -> covers.max() }

            _state.value = _state.value.copy(daysOfCover = byProduct)
        } catch (e: Exception) {
        }
    }

    private fun fail(error: ApiError, reset: Boolean) {
        _state.value = _state.value.copy(
            loadingFirstPage = false,
            loadingNextPage = false,
            error = error,
            products = if (reset) emptyList() else _state.value.products,
        )
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}

@Composable
fun ProductListScreen(
    tenantName: String,
    onSignOut: () -> Unit,
    onOpenDetail: ((java.util.UUID, String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    loadKey: Int = 0,
    viewModel: ProductListViewModel = hiltViewModel(),
    saleViewModel: RecordSaleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saleState by saleViewModel.state.collectAsStateWithLifecycle()

    var firstPass by remember { mutableStateOf(true) }
    LaunchedEffect(loadKey) {
        if (firstPass) firstPass = false else viewModel.reload()
    }

    RecordSaleDialog(
        state = saleState,
        onQuantityChange = saleViewModel::setQuantity,
        onSave = saleViewModel::save,
        onRetry = saleViewModel::retry,
        onDismiss = {
            val recorded = saleState.recordedSaleNumber != null
            saleViewModel.dismiss()
            if (recorded) viewModel.retry()
        },
    )
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .filter { index -> index != null && index >= state.products.size - 3 }
            .collect { viewModel.loadNextPage() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                onBack?.let { back ->
                    TextButton(onClick = back) { Text("← Home") }
                }
                Text("Stock", style = MaterialTheme.typography.headlineSmall)
                if (tenantName.isNotBlank()) {
                    Text(tenantName, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
        HorizontalDivider()

        when {
            state.loadingFirstPage -> CentredMessage { CircularProgressIndicator() }

            state.error != null && state.products.isEmpty() -> CentredMessage {
                ErrorBlock(state.error!!, onRetry = viewModel::retry)
            }

            state.isEmpty -> CentredMessage {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No products yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Products you add will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.products, key = { it.id.toString() }) { product ->
                    ProductRow(
                        product = product,
                        daysOfCover = state.daysOfCover[product.id],
                        onClick = { saleViewModel.startSale(product) },
                        onOpenDetail = onOpenDetail?.let { open ->
                            { open(product.id, product.name) }
                        },
                    )
                    HorizontalDivider()
                }

                if (state.loadingNextPage) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                }

                state.error?.let { error ->
                    item {
                        Box(modifier = Modifier.padding(16.dp)) {
                            ErrorBlock(error, onRetry = viewModel::loadNextPage)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductRow(
    product: Product,
    daysOfCover: java.math.BigDecimal?,
    onClick: () -> Unit,
    onOpenDetail: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(product.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                product.sku,
                style = MaterialTheme.typography.bodySmall,
            )
            daysOfCover?.let { cover ->
                Text(
                    "about ${cover.setScale(0, java.math.RoundingMode.HALF_UP)} days of cover",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Text(
                product.quantityOnHand.stripTrailingZeros().toPlainString(),
                style = MaterialTheme.typography.bodyLarge,
            )
            StockStateLabel(product)
        }
        onOpenDetail?.let { detail ->
            TextButton(onClick = detail) { Text("Details") }
        }
        TextButton(onClick = onClick) { Text("Sell") }
    }
}

@Composable
private fun StockStateLabel(product: Product) {
    if (product.stockState == Product.StockState.UNKNOWN) {
        return
    }
    Text(
        product.stockState.value.replace('_', ' '),
        style = MaterialTheme.typography.bodySmall,
        color = if (product.stockState == Product.StockState.OUT_OF_STOCK) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun ErrorBlock(error: ApiError, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            error.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        error.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun CentredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
