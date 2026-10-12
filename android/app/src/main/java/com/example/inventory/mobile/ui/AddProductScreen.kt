package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.CatalogApi
import com.example.inventory.api.models.ProductWriteRequest
import com.example.inventory.api.models.ProductWriteRequestOpeningStock
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProductForm(
    val name: String = "",
    val sku: String = "",
    val barcode: String = "",
    val unitOfMeasure: String = "",
    val costPrice: String = "",
    val sellingPrice: String = "",
    val reorderPoint: String = "",
    val openingStock: String = "",
)

data class ValidProduct(
    val name: String,
    val sku: String,
    val barcode: String?,
    val unitOfMeasure: String?,
    val costPrice: BigDecimal?,
    val sellingPrice: BigDecimal?,
    val reorderPoint: BigDecimal?,
    val openingStock: BigDecimal?,
)

fun validateProductForm(form: ProductForm): Pair<ValidProduct?, Map<String, String>> {
    val errors = mutableMapOf<String, String>()

    val name = form.name.trim()
    val sku = form.sku.trim()
    if (name.isEmpty()) errors["name"] = "Name is required"
    if (name.length > 300) errors["name"] = "Name must be 300 characters or fewer"
    if (sku.isEmpty()) errors["sku"] = "SKU is required"
    if (sku.length > 64) errors["sku"] = "SKU must be 64 characters or fewer"

    fun number(field: String, text: String): BigDecimal? {
        if (text.isBlank()) return null
        val value = text.trim().toBigDecimalOrNull()
        when {
            value == null -> errors[field] = "Enter a number"
            value < BigDecimal.ZERO -> errors[field] = "Cannot be negative"
        }
        return value
    }

    val cost = number("costPrice", form.costPrice)
    val price = number("sellingPrice", form.sellingPrice)
    val reorder = number("reorderPoint", form.reorderPoint)
    val opening = number("openingStock", form.openingStock)

    if (errors.isNotEmpty()) return null to errors

    return ValidProduct(
        name = name,
        sku = sku,
        barcode = form.barcode.trim().ifEmpty { null },
        unitOfMeasure = form.unitOfMeasure.trim().ifEmpty { null },
        costPrice = cost,
        sellingPrice = price,
        reorderPoint = reorder,
        openingStock = opening?.takeIf { it > BigDecimal.ZERO },
    ) to emptyMap()
}

@HiltViewModel
class AddProductViewModel @Inject constructor(
    private val catalog: CatalogApi,
) : ViewModel() {

    data class UiState(
        val form: ProductForm = ProductForm(),
        val fieldErrors: Map<String, String> = emptyMap(),
        val saving: Boolean = false,
        val error: ApiError? = null,
        val saved: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun update(change: (ProductForm) -> ProductForm) {
        _state.value = _state.value.copy(form = change(_state.value.form), error = null)
    }

    fun save() {
        if (_state.value.saving) return
        val (valid, errors) = validateProductForm(_state.value.form)
        if (valid == null) {
            _state.value = _state.value.copy(fieldErrors = errors)
            return
        }
        _state.value = _state.value.copy(fieldErrors = emptyMap(), saving = true, error = null)

        viewModelScope.launch {
            try {
                val opening = valid.openingStock?.let { qty ->
                    val locations = catalog.locationsGet()
                    val defaultLocation = locations.body()?.firstOrNull { it.isDefault == true }
                        ?: locations.body()?.firstOrNull()
                    if (defaultLocation == null) {
                        _state.value = _state.value.copy(
                            saving = false,
                            error = ApiError("This business has no stock location yet, so opening stock cannot be recorded."),
                        )
                        return@launch
                    }
                    ProductWriteRequestOpeningStock(
                        locationId = defaultLocation.id,
                        quantity = qty,
                        unitCost = valid.costPrice,
                    )
                }

                val response = catalog.productsPost(
                    ProductWriteRequest(
                        sku = valid.sku,
                        name = valid.name,
                        barcode = valid.barcode,
                        unitOfMeasure = valid.unitOfMeasure,
                        costPrice = valid.costPrice,
                        sellingPrice = valid.sellingPrice,
                        reorderPoint = valid.reorderPoint,
                        openingStock = opening,
                    ),
                )

                _state.value = if (response.isSuccessful) {
                    _state.value.copy(saving = false, saved = true)
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
fun AddProductScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: AddProductViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        TextButton(onClick = onBack, enabled = !state.saving) { Text("← Back") }
        Text("Add product", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        val f = state.form
        val e = state.fieldErrors
        Field("Name *", f.name, e["name"]) { v -> viewModel.update { it.copy(name = v) } }
        Field("SKU *", f.sku, e["sku"]) { v -> viewModel.update { it.copy(sku = v) } }
        Field("Barcode", f.barcode, null) { v -> viewModel.update { it.copy(barcode = v) } }
        Field("Unit (e.g. each, kg)", f.unitOfMeasure, null) { v -> viewModel.update { it.copy(unitOfMeasure = v) } }
        Field("Cost price", f.costPrice, e["costPrice"], numeric = true) { v -> viewModel.update { it.copy(costPrice = v) } }
        Field("Selling price", f.sellingPrice, e["sellingPrice"], numeric = true) { v -> viewModel.update { it.copy(sellingPrice = v) } }
        Field("Reorder when stock reaches", f.reorderPoint, e["reorderPoint"], numeric = true) { v -> viewModel.update { it.copy(reorderPoint = v) } }
        Field("Opening stock", f.openingStock, e["openingStock"], numeric = true) { v -> viewModel.update { it.copy(openingStock = v) } }

        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            error.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = viewModel::save,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.saving) CircularProgressIndicator(modifier = Modifier.height(20.dp)) else Text("Save product")
        }
    }
}

@Composable
internal fun Field(
    label: String,
    value: String,
    error: String?,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
