package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
fun StockScreen(
    role: String?,
    tenantName: String,
    loadKey: Int,
    onSignOut: () -> Unit,
    onAddProduct: () -> Unit,
    onOpenDetail: (UUID, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (canEditCatalog(role)) {
            Button(
                onClick = onAddProduct,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) { Text("Add product") }
        }
        ProductListScreen(
            tenantName = tenantName,
            onSignOut = onSignOut,
            onOpenDetail = onOpenDetail,
            loadKey = loadKey,
        )
    }
}
