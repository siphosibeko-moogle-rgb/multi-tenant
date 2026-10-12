package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.inventory.api.models.PurchaseOrderDetail
import com.example.inventory.api.models.PurchaseOrderDetailAllOfLines
import com.example.inventory.api.models.PurchaseOrdersPoIdReceiptsPostRequestLinesInner
import java.util.UUID

fun canMarkOrdered(role: String?): Boolean = role?.lowercase() in setOf("owner", "manager")

fun canReceiveStock(role: String?): Boolean = role?.lowercase() in setOf("owner", "manager", "clerk")

data class PurchaseOrderActions(val markOrdered: Boolean, val receive: Boolean)

fun purchaseOrderActions(order: PurchaseOrderDetail, role: String?): PurchaseOrderActions =
    PurchaseOrderActions(
        markOrdered = order.status == PurchaseOrderDetail.Status.DRAFT && canMarkOrdered(role),
        receive = order.status in setOf(PurchaseOrderDetail.Status.ORDERED, PurchaseOrderDetail.Status.PARTIAL) &&
            canReceiveStock(role) &&
            order.lines.any { it.quantityOutstanding.signum() > 0 },
    )

sealed interface ReceiptCheck {
    data class Ok(val lines: List<PurchaseOrdersPoIdReceiptsPostRequestLinesInner>) : ReceiptCheck
    data class Invalid(val message: String) : ReceiptCheck
}

fun checkReceipt(lines: List<PurchaseOrderDetailAllOfLines>, inputs: Map<UUID, String>): ReceiptCheck {
    val out = mutableListOf<PurchaseOrdersPoIdReceiptsPostRequestLinesInner>()
    for (line in lines) {
        val raw = inputs[line.productId]?.trim().orEmpty()
        if (raw.isEmpty()) continue
        val quantity = raw.toBigDecimalOrNull()
            ?: return ReceiptCheck.Invalid("Enter the quantity of ${line.name} as a number.")
        if (quantity.signum() <= 0) {
            return ReceiptCheck.Invalid("The quantity of ${line.name} must be more than zero, or left blank.")
        }
        if (quantity > line.quantityOutstanding) {
            return ReceiptCheck.Invalid(
                "Only ${plain(line.quantityOutstanding)} of ${line.name} are still to come on this order.",
            )
        }
        out += PurchaseOrdersPoIdReceiptsPostRequestLinesInner(productId = line.productId, quantityReceived = quantity)
    }
    return if (out.isEmpty()) ReceiptCheck.Invalid("Enter the quantity received for at least one product.") else ReceiptCheck.Ok(out)
}

@Composable
fun ReceiveStockScreen(
    order: PurchaseOrderDetail,
    saving: Boolean,
    message: String?,
    onSubmit: (Map<UUID, String>) -> Unit,
    onBack: () -> Unit,
) {
    var inputs by rememberSaveable(order.id) { mutableStateOf(mapOf<String, String>()) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val outstanding = order.lines.filter { it.quantityOutstanding.signum() > 0 }

    if (confirming) {
        val count = inputs.values.count { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Receive this stock?") },
            text = {
                Text(
                    "Adds the quantities entered for $count product${if (count == 1) "" else "s"} to stock " +
                        "straight away. A receipt cannot be undone in the app.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onSubmit(inputs.mapKeys { UUID.fromString(it.key) })
                }) { Text("Receive") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SubScreenHeader("Receive stock", onBack, subtitle = "${order.poNumber} · ${order.supplierName}")
        Text(
            "Enter what actually arrived. Leave a product blank if none of it came. " +
                "A partial delivery is fine — the rest stays on order.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        outstanding.forEach { line ->
            HorizontalDivider()
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(line.name, style = MaterialTheme.typography.titleMedium)
                Text(line.sku, style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("Ordered ${plain(line.quantityOrdered)}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(12.dp))
                    Text("Received ${plain(line.quantityReceived)}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(12.dp))
                    Text("To come ${plain(line.quantityOutstanding)}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = inputs[line.productId.toString()].orEmpty(),
                    onValueChange = { inputs = inputs + (line.productId.toString() to it) },
                    label = { Text("Quantity received") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = { confirming = true },
            enabled = !saving && inputs.values.any { it.isNotBlank() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saving) "Saving…" else "Receive stock") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
