package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val forecasting: ForecastingApi,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Failed(val error: ApiError) : UiState
        data class Loaded(
            val needsAttention: Int,
            val top: List<ReorderRecommendation>,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val response = forecasting.reorderRecommendationsGet(limit = 50, status = null)
                if (!response.isSuccessful) {
                    UiState.Failed(response.toApiError())
                } else {
                    val items = response.body()?.items.orEmpty()
                    UiState.Loaded(
                        needsAttention = items.size,
                        top = items.sortedBy { urgencyRank(it.urgency) }.take(3),
                    )
                }
            } catch (e: Exception) {
                UiState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }

    private fun urgencyRank(urgency: ReorderRecommendation.Urgency): Int = when (urgency) {
        ReorderRecommendation.Urgency.CRITICAL -> 0
        ReorderRecommendation.Urgency.HIGH -> 1
        ReorderRecommendation.Urgency.NORMAL -> 2
    }
}

@Composable
fun HomeScreen(
    onRecordSale: () -> Unit,
    onSeeAllReorders: (() -> Unit)? = null,
    loadKey: Int = 0,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(loadKey) { viewModel.load() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Home", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onRecordSale,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Record a sale")
        }
        Spacer(Modifier.height(20.dp))

        when (val current = state) {
            is HomeViewModel.UiState.Loading ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading…")
                }

            is HomeViewModel.UiState.Failed ->
                Column(modifier = Modifier.fillMaxWidth()) {
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

            is HomeViewModel.UiState.Loaded -> {
                Text(
                    "${current.needsAttention} " +
                        if (current.needsAttention == 1) "product needs attention"
                        else "products need attention",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))

                if (current.top.isEmpty()) {
                    Text(
                        "No products currently need reordering.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(current.top) { recommendation ->
                            HomeRecommendationCard(recommendation)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    onSeeAllReorders?.let { seeAll ->
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = seeAll, modifier = Modifier.fillMaxWidth()) {
                            Text("See all reorders")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRecommendationCard(recommendation: ReorderRecommendation) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(recommendation.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${recommendation.urgency.value} · ${plain(recommendation.quantityOnHand)} left " +
                    "· order ${plain(recommendation.recommendedQty)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal fun plain(value: java.math.BigDecimal): String =
    value.stripTrailingZeros().toPlainString()
