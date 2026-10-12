package com.example.inventory.mobile.ui

enum class Tab(val label: String) {
    HOME("Home"),
    SELL("Sell"),
    STOCK("Stock"),
    REORDERS("Reorders"),
    MORE("More"),
}

enum class MorePage {
    SALES_HISTORY,
    PURCHASE_ORDERS,
    SUPPLIERS,
    USERS,
}

fun tabsFor(role: String?): List<Tab> = when (role?.lowercase()) {
    "owner", "manager" -> listOf(Tab.HOME, Tab.SELL, Tab.STOCK, Tab.REORDERS, Tab.MORE)
    else -> listOf(Tab.HOME, Tab.SELL, Tab.STOCK, Tab.MORE)
}

fun canEditCatalog(role: String?): Boolean = role?.lowercase() in setOf("owner", "manager")

fun canManageUsers(role: String?): Boolean = role?.lowercase() == "owner"
