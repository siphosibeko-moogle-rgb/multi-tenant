package com.example.inventory.mobile.ui

enum class Tab(val label: String) {
    HOME("Home"),
    SELL("Sell"),
    STOCK("Stock"),
    MORE("More"),
}

enum class MorePage {
    SALES_HISTORY,
    PURCHASE_ORDERS,
    SUPPLIERS,
    USERS,
}

fun tabsFor(@Suppress("UNUSED_PARAMETER") role: String?): List<Tab> =
    listOf(Tab.HOME, Tab.SELL, Tab.STOCK, Tab.MORE)

fun canEditCatalog(role: String?): Boolean = role?.lowercase() in setOf("owner", "manager")

fun canManageUsers(role: String?): Boolean = role?.lowercase() == "owner"
