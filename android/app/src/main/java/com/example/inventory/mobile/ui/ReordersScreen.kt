package com.example.inventory.mobile.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.ForecastingApi
import com.example.inventory.api.models.ReorderRecommendation
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
class ReordersViewModel @Inject constructor(
    private val forecasting: ForecastingApi,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Failed(val error: ApiError) : UiState
        data class Loaded(
            val items: List<ReorderRecommendation>,
            val dismissing: UUID? = null,
            val actionError: ApiError? = null,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val response = forecasting.reorderRecommendationsGet(limit = 50, status = null)
                if (response.isSuccessful) {
                    UiState.Loaded(response.body()?.items.orEmpty())
                } else {
                    UiState.Failed(response.toApiError())
                }
            } catch (e: Exception) {
                UiState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }

    fun dismiss(recommendationId: UUID) {
        val current = _state.value as? UiState.Loaded ?: return
        if (current.dismissing != null) {
            return
        }
        _state.value = current.copy(dismissing = recommendationId, actionError = null)

        viewModelScope.launch {
            try {
                val response = forecasting.reorderRecommendationsRecommendationIdDismissPost(
                    recommendationId,
                    com.example.inventory.api.models.SalesSaleIdVoidPostRequest(reason = null),
                )
                val latest = _state.value as? UiState.Loaded ?: return@launch
                _state.value = if (response.isSuccessful) {
                    latest.copy(
                        items = latest.items.filterNot { it.id == recommendationId },
                        dismissing = null,
                    )
                } else {
                    latest.copy(dismissing = null, actionError = response.toApiError())
                }
            } catch (e: Exception) {
                val latest = _state.value as? UiState.Loaded ?: return@launch
                _state.value = latest.copy(
                    dismissing = null,
                    actionError = ApiError.fromNetworkFailure(e),
                )
            }
        }
    }
}

@Composable
fun ReordersScreen(
    onBack: (() -> Unit)? = null,
    onCreateOrder: ((ReorderRecommendation) -> Unit)? = null,
    loadKey: Int = 0,
    viewModel: ReordersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(loadKey) { viewModel.load() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        onBack?.let { TextButton(onClick = it) { Text("← Back") } }
        Text("Reorders", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        when (val current = state) {
            is ReordersViewModel.UiState.Loading ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading…")
                }

            is ReordersViewModel.UiState.Failed ->
                Column {
                    Text(
                        "Unable to load — check your connection.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current.error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.load() }) { Text("Try again") }
                }

            is ReordersViewModel.UiState.Loaded -> {
                current.actionError?.let { error ->
                    Text(
                        error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (current.items.isEmpty()) {
                    Text(
                        "No products currently need reordering.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(current.items, key = { it.id }) { recommendation ->
                            RecommendationCard(
                                recommendation = recommendation,
                                dismissing = current.dismissing == recommendation.id,
                                onDismiss = { viewModel.dismiss(recommendation.id) },
                                onCreateOrder = onCreateOrder?.let { { it(recommendation) } },
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    recommendation: ReorderRecommendation,
    dismissing: Boolean,
    onDismiss: () -> Unit,
    onCreateOrder: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                recommendation.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(recommendation.sku, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))

            Text(
                "${plain(recommendation.quantityOnHand)} in stock · " +
                    "order ${plain(recommendation.recommendedQty)} · " +
                    recommendation.urgency.value,
                style = MaterialTheme.typography.bodyMedium,
            )
            recommendation.supplierName?.let {
                Text("Supplier: $it", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))

            Text(recommendation.rationale, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onCreateOrder?.let {
                    OutlinedButton(onClick = it, enabled = !dismissing) { Text("Create order") }
                }
                OutlinedButton(onClick = onDismiss, enabled = !dismissing) {
                    Text(if (dismissing) "Dismissing…" else "Dismiss")
                }
            }
        }
    }
}
