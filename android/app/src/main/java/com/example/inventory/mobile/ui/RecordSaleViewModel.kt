package com.example.inventory.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.models.Product
import com.example.inventory.api.models.SaleWriteRequest
import com.example.inventory.api.models.SaleWriteRequestLinesInner
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.offline.Outbox
import com.example.inventory.mobile.offline.OutboxCoordinator
import com.example.inventory.mobile.offline.OutboxEntry
import com.example.inventory.mobile.offline.OutboxOperation
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class RecordSaleViewModel @Inject constructor(
    private val sales: SalesApi,
    private val outbox: Outbox,
    private val coordinator: OutboxCoordinator,
) : ViewModel() {

    data class UiState(
        val product: Product? = null,
        val quantity: String = "1",
        val submitting: Boolean = false,
        val error: ApiError? = null,
        val recordedSaleNumber: String? = null,
        val pendingRequestId: UUID? = null,
        val soldAt: OffsetDateTime? = null,
        val queuedOffline: Boolean = false,
    ) {
        val quantityOrNull: BigDecimal?
            get() = quantity.trim().toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }

        val canSubmit: Boolean get() = product != null && quantityOrNull != null && !submitting
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun startSale(product: Product) {
        _state.value = UiState(product = product)
    }

    fun setQuantity(quantity: String) {
        _state.value = _state.value.copy(quantity = quantity, error = null)
    }

    fun dismiss() {
        _state.value = UiState()
    }

    fun save() {
        val current = _state.value
        if (!current.canSubmit) {
            return
        }
        _state.value = current.copy(
            pendingRequestId = current.pendingRequestId ?: UUID.randomUUID(),
            soldAt = current.soldAt ?: OffsetDateTime.now(),
        )
        submit()
    }

    fun retry() {
        if (_state.value.pendingRequestId == null) {
            save()
        } else {
            submit()
        }
    }

    private fun submit() {
        val current = _state.value
        val product = current.product ?: return
        val quantity = current.quantityOrNull ?: return
        val requestId = current.pendingRequestId ?: return
        val soldAt = current.soldAt ?: return

        _state.value = current.copy(submitting = true, error = null)

        viewModelScope.launch {
            try {
                val response = sales.salesPost(
                    SaleWriteRequest(
                        lines = listOf(
                            SaleWriteRequestLinesInner(
                                productId = product.id,
                                quantity = quantity,
                            )
                        ),
                        clientRequestId = requestId,
                        soldAt = soldAt,
                    )
                )

                if (!response.isSuccessful) {
                    _state.value = _state.value.copy(
                        submitting = false,
                        error = response.toApiError(),
                    )
                    return@launch
                }

                outbox.remove(requestId)

                _state.value = _state.value.copy(
                    submitting = false,
                    error = null,
                    queuedOffline = false,
                    recordedSaleNumber = response.body()?.saleNumber ?: "recorded",
                    pendingRequestId = null,
                )

                coordinator.syncNow()
            } catch (e: Exception) {
                outbox.enqueue(
                    OutboxEntry(
                        clientRequestId = requestId,
                        operation = OutboxOperation.SALE,
                        productId = product.id,
                        quantity = quantity,
                        capturedAt = soldAt,
                    )
                )
                coordinator.refreshPendingCount()
                _state.value = _state.value.copy(
                    submitting = false,
                    error = null,
                    queuedOffline = true,
                )
            }
        }
    }
}
