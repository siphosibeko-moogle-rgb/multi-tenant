package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.inventory.mobile.net.ApiError

@Composable
fun RecordSaleDialog(
    state: RecordSaleViewModel.UiState,
    onQuantityChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val product = state.product ?: return

    AlertDialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        title = {
            Text(
                if (state.recordedSaleNumber != null) "Sale recorded" else "Record a sale",
            )
        },
        text = {
            Column {
                Text(product.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "In stock: " + product.quantityOnHand.stripTrailingZeros().toPlainString(),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))

                if (state.recordedSaleNumber != null) {
                    Text(
                        "Receipt ${state.recordedSaleNumber}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    OutlinedTextField(
                        value = state.quantity,
                        onValueChange = onQuantityChange,
                        label = { Text("Quantity") },
                        singleLine = true,
                        enabled = !state.submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                state.error?.let { error ->
                    Spacer(Modifier.height(12.dp))
                    SaleError(error)
                }

                if (state.submitting) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            when {
                state.recordedSaleNumber != null ->
                    TextButton(onClick = onDismiss) { Text("Done") }

                state.error != null ->
                    TextButton(onClick = onRetry, enabled = !state.submitting) { Text("Retry") }

                else ->
                    TextButton(onClick = onSave, enabled = state.canSubmit) { Text("Save") }
            }
        },
        dismissButton = {
            if (state.recordedSaleNumber == null) {
                TextButton(onClick = onDismiss, enabled = !state.submitting) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SaleError(error: ApiError) {
    Column {
        Text(
            error.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        error.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
