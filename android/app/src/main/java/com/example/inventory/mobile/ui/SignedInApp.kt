package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.util.UUID

@Composable
fun SignedInApp(
    role: String?,
    tenantName: String,
    timezone: String?,
    onSignOut: () -> Unit,
    outboxCoordinator: com.example.inventory.mobile.offline.OutboxCoordinator,
) {
    val tabs = tabsFor(role)
    var selected by remember(role) { mutableStateOf(Tab.HOME) }
    var loadKey by remember { mutableStateOf(0) }

    var detail by remember { mutableStateOf<Pair<UUID, String>?>(null) }
    var addingProduct by remember { mutableStateOf(false) }
    var morePage by remember { mutableStateOf<MorePage?>(null) }

    LaunchedEffect(tabs) {
        if (selected !in tabs) {
            selected = Tab.HOME
        }
    }

    LaunchedEffect(Unit) {
        outboxCoordinator.syncNow()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab && detail == null && !addingProduct,
                        onClick = {
                            detail = null
                            addingProduct = false
                            morePage = null
                            selected = tab
                            loadKey++
                        },
                        icon = {},
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)

        val currentDetail = detail

        when {
            addingProduct -> androidx.compose.foundation.layout.Box(content) {
                AddProductScreen(
                    onDone = {
                        addingProduct = false
                        loadKey++
                    },
                    onBack = { addingProduct = false },
                )
            }

            currentDetail != null -> androidx.compose.foundation.layout.Box(content) {
                ProductDetailScreen(
                    productId = currentDetail.first,
                    productName = currentDetail.second,
                    canAdjustStock = canEditCatalog(role),
                    onStockChanged = { loadKey++ },
                    onBack = { detail = null },
                )
            }

            else -> androidx.compose.foundation.layout.Box(content) {
                when (selected) {
                    Tab.HOME -> HomeScreen(
                        loadKey = loadKey,
                        onRecordSale = { selected = Tab.SELL; loadKey++ },
                        onSeeAllReorders = null,
                    )

                    Tab.SELL -> ProductListScreen(
                        tenantName = tenantName,
                        onSignOut = onSignOut,
                        onOpenDetail = { id, name -> detail = id to name },
                        loadKey = loadKey,
                    )

                    Tab.STOCK -> StockScreen(
                        role = role,
                        tenantName = tenantName,
                        loadKey = loadKey,
                        onSignOut = onSignOut,
                        onAddProduct = { addingProduct = true },
                        onOpenDetail = { id, name -> detail = id to name },
                    )

                    Tab.MORE -> when (morePage) {
                        null -> MoreScreen(
                            role = role,
                            onSignOut = onSignOut,
                            onOpen = {
                                morePage = it
                                loadKey++
                            },
                        )

                        MorePage.SALES_HISTORY -> SalesHistoryScreen(
                            loadKey = loadKey,
                            onBack = { morePage = null },
                            timezone = timezone,
                        )

                        MorePage.PURCHASE_ORDERS -> PurchaseOrdersScreen(
                            loadKey = loadKey,
                            onBack = { morePage = null },
                            role = role,
                            timezone = timezone,
                            onStockChanged = { loadKey++ },
                        )

                        MorePage.SUPPLIERS -> SupplierDirectoryScreen(
                            loadKey = loadKey,
                            role = role,
                            tenantName = tenantName,
                            onBack = { morePage = null },
                        )

                        MorePage.USERS ->
                            if (canManageUsers(role)) {
                                UsersScreen(role = role, loadKey = loadKey, onBack = { morePage = null })
                            } else {
                                LaunchedEffect(Unit) { morePage = null }
                            }
                    }
                }
            }
        }
    }
}
