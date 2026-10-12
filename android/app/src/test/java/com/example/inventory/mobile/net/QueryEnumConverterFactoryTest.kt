package com.example.inventory.mobile.net

import com.example.inventory.api.apis.AuthenticationApi
import com.example.inventory.api.apis.CatalogApi
import com.example.inventory.api.apis.ForecastingApi
import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.PurchasingApi
import com.example.inventory.api.apis.ReportsApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.apis.SuppliersApi
import com.example.inventory.api.apis.SyncApi
import com.example.inventory.api.apis.UsersApi
import com.squareup.moshi.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryEnumConverterFactoryTest {

    private val apis = listOf(
        AuthenticationApi::class.java, CatalogApi::class.java, ForecastingApi::class.java,
        InventoryApi::class.java, PurchasingApi::class.java, ReportsApi::class.java,
        SalesApi::class.java, SuppliersApi::class.java, SyncApi::class.java, UsersApi::class.java,
    )

    private val queryEnums = apis.flatMap { api -> api.declaredClasses.filter { it.isEnum } }

    @Test
    fun `every generated query enum is mapped`() {
        assertTrue("expected some generated query enums", queryEnums.isNotEmpty())
        val missing = queryEnums.filterNot { it in QueryEnumConverterFactory.handled }
        assertTrue("not mapped in QueryEnumConverterFactory: ${missing.map { it.name }}", missing.isEmpty())
    }

    @Test
    fun `every constant renders as its Json name`() {
        for (enumClass in queryEnums) {
            for (constant in enumClass.enumConstants) {
                val expected = enumClass.getField((constant as Enum<*>).name).getAnnotation(Json::class.java).name
                assertEquals("${enumClass.simpleName}.${constant.name}", expected, QueryEnumConverterFactory.wireName(constant))
            }
        }
    }

    @Test
    fun `values that are not generated query enums are left alone`() {
        assertEquals(null, QueryEnumConverterFactory.wireName("plain"))
        assertEquals(null, QueryEnumConverterFactory.wireName(Thread.State.NEW))
    }
}
