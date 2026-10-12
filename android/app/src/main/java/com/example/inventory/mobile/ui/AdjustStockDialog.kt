package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.models.AdjustmentRequest
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AdjustDirection { ADD, REMOVE }

fun buildAdjustmentDelta(
    direction: AdjustDirection,
    quantityText: String,
    reason: String,
): Pair<BigDecimal?, String?> {
    val qty = quantityText.trim().toBigDecimalOrNull()
        ?: return null to "Enter a quantity"
    if (qty <= BigDecimal.ZERO) return null to "Quantity must be more than zero"
    if (reason.isBlank()) return null to "Give a reason, e.g. delivery or damaged"
    return (if (direction == AdjustDirection.ADD) qty else qty.negate()) to null
}

@HiltViewModel
class AdjustStockViewModel @Inject constructor(
    private val inventory: InventoryApi,
) : ViewModel() {

    data class UiState(
        val direction: AdjustDirection = AdjustDirection.ADD,
        val quantity: String = "",
        val reason: String = "",
        val validation: String? = null,
        val saving: Boolean = false,
        val error: ApiError? = null,
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var idempotencyKey: UUID = UUID.randomUUID()

    fun reset() {
        _state.value = UiState()
        idempotencyKey = UUID.randomUUID()
    }

    fun setDirection(d: AdjustDirection) { _state.value = _state.value.copy(direction = d, validation = null) }
    fun setQuantity(q: String) { _state.value = _state.value.copy(quantity = q, validation = null, error = null) }
    fun setReason(r: String) { _state.value = _state.value.copy(reason = r, validation = null, error = null) }

    fun save(productId: UUID) {
        val s = _state.value
        if (s.saving) return
        val (delta, problem) = buildAdjustmentDelta(s.direction, s.quantity, s.reason)
        if (delta == null) {
            _state.value = s.copy(validation = problem)
            return
        }
        _state.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val response = inventory.inventoryAdjustmentsPost(
                    AdjustmentRequest(productId = productId, quantityDelta = delta, reason = s.reason.trim()),
                    idempotencyKey = idempotencyKey,
                )
                _state.value = if (response.isSuccessful) {
                    _state.value.copy(saving = false, done = true)
                } else {
                    _state.value.copy(saving = false, error = response.toApiError())
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(saving = false, error = ApiError.fromNetworkFailure(e))
            }
        }
    }
}

@Composable
fun AdjustStockDialog(
    productId: UUID,
    productName: String,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: AdjustStockViewModel = hiltViewModel(key = "adjust-$productId"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.done) {
        viewModel.reset()
        onDone()
        return
    }

    AlertDialog(
        onDismissRequest = { if (!state.saving) { viewModel.reset(); onDismiss() } },
        title = { Text("Adjust stock — $productName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.direction == AdjustDirection.ADD,
                        onClick = { viewModel.setDirection(AdjustDirection.ADD) },
                        label = { Text("Add stock") },
                    )
                    FilterChip(
                        selected = state.direction == AdjustDirection.REMOVE,
                        onClick = { viewModel.setDirection(AdjustDirection.REMOVE) },
                        label = { Text("Remove stock") },
                    )
                }
                Field("Quantity", state.quantity, null, numeric = true, onChange = viewModel::setQuantity)
                Field("Reason", state.reason, null, onChange = viewModel::setReason)
                (state.validation ?: state.error?.message)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                state.error?.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.save(productId) }, enabled = !state.saving) {
                Text(if (state.saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.reset(); onDismiss() }, enabled = !state.saving) { Text("Cancel") }
        },
    )
}
