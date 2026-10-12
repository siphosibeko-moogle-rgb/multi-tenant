package com.example.inventory.mobile.net

import com.example.inventory.api.apis.CatalogApi
import com.example.inventory.api.apis.ForecastingApi
import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.PurchasingApi
import com.example.inventory.api.apis.ReportsApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.apis.UsersApi
import java.lang.reflect.Type
import retrofit2.Converter
import retrofit2.Retrofit

object QueryEnumConverterFactory : Converter.Factory() {

    override fun stringConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, String>? {
        val enumClass = (type as? Class<*>)?.takeIf { it.isEnum } ?: return null
        if (enumClass !in handled) return null
        return Converter<Any, String> { value -> wireName(value) ?: value.toString() }
    }

    internal fun wireName(value: Any): String? = when (value) {
        is CatalogApi.StockStateProductsGet -> value.value
        is CatalogApi.SortProductsGet -> value.value
        is ForecastingApi.StatusReorderRecommendationsGet -> value.value
        is InventoryApi.StockStateInventoryGet -> value.value
        is PurchasingApi.StatusPurchaseOrdersGet -> value.value
        is ReportsApi.PeriodReportsDashboardGet -> value.value
        is ReportsApi.GroupByReportsSalesSummaryGet -> value.value
        is ReportsApi.OrderReportsTopProductsGet -> value.value
        is SalesApi.StatusSalesGet -> value.value
        is UsersApi.StatusUsersGet -> value.value
        else -> null
    }

    internal val handled: Set<Class<*>> = setOf(
        CatalogApi.StockStateProductsGet::class.java,
        CatalogApi.SortProductsGet::class.java,
        ForecastingApi.StatusReorderRecommendationsGet::class.java,
        InventoryApi.StockStateInventoryGet::class.java,
        PurchasingApi.StatusPurchaseOrdersGet::class.java,
        ReportsApi.PeriodReportsDashboardGet::class.java,
        ReportsApi.GroupByReportsSalesSummaryGet::class.java,
        ReportsApi.OrderReportsTopProductsGet::class.java,
        SalesApi.StatusSalesGet::class.java,
        UsersApi.StatusUsersGet::class.java,
    )
}
