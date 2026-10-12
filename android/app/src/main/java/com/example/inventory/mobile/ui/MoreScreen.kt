package com.example.inventory.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MoreScreen(
    role: String?,
    onSignOut: () -> Unit,
    onOpen: (MorePage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("More", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            role?.replaceFirstChar { it.uppercase() } ?: "Signed in",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))

        MoreRow("Sales history", "Every sale, newest first") { onOpen(MorePage.SALES_HISTORY) }
        MoreRow("Purchase orders", "What you have ordered and received") { onOpen(MorePage.PURCHASE_ORDERS) }
        MoreRow("Suppliers", "Who you buy from, and their lead times") { onOpen(MorePage.SUPPLIERS) }

        if (canManageUsers(role)) {
            MoreRow("Users", "Invite staff and set what they can do") { onOpen(MorePage.USERS) }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onSignOut) { Text("Sign out") }
    }
}

@Composable
private fun MoreRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}
