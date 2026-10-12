package com.example.inventory.mobile.offline

import com.example.inventory.api.apis.AuthenticationApi
import com.example.inventory.api.apis.CatalogApi
import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.infrastructure.Serializer
import com.example.inventory.api.models.AdjustmentRequest
import com.example.inventory.api.models.ProductWriteRequest
import com.example.inventory.api.models.SaleWriteRequest
import com.example.inventory.api.models.SaleWriteRequestLinesInner
import com.example.inventory.api.models.TenantRegistrationRequest
import java.io.File
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class OutboxLiveBackendTest {

    private companion object {
        const val BASE_URL = "http://localhost:8080/api/v1/"
        const val HEALTH_URL = "http://localhost:8080/api/v1/actuator/health"

        @Volatile var sharedToken: String? = null

        @Volatile var sharedProductId: UUID? = null
    }

    private lateinit var directory: File
    private lateinit var outbox: Outbox
    private lateinit var replayer: OutboxReplayer
    private lateinit var sales: SalesApi
    private lateinit var inventory: InventoryApi
    private lateinit var catalog: CatalogApi

    private lateinit var productId: UUID

    @Volatile
    private var accessToken: String? = null

    @Before
    fun setUp() {
        assumeTrue("no backend on :8080 — skipping the live loop", backendIsUp())

        directory = Files.createTempDirectory("live-outbox").toFile()
        outbox = Outbox(File(directory, "pending.log"))

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = accessToken?.let {
                    chain.request().newBuilder().header("Authorization", "Bearer $it").build()
                } ?: chain.request()
                chain.proceed(request)
            })
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .build()

        sales = retrofit.create(SalesApi::class.java)
        inventory = retrofit.create(InventoryApi::class.java)
        catalog = retrofit.create(CatalogApi::class.java)
        replayer = OutboxReplayer(outbox, sales, inventory)

        runBlocking {
            if (sharedToken == null) {
                registerTenantWithAStockedProduct(retrofit)
                sharedToken = accessToken
                sharedProductId = productId
            } else {
                accessToken = sharedToken
                productId = sharedProductId!!
            }
            restockTo(BigDecimal("20"))
        }
    }

    private suspend fun restockTo(target: BigDecimal) {
        val delta = target.subtract(onHand())
        if (delta.compareTo(BigDecimal.ZERO) != 0) {
            val response = inventory.inventoryAdjustmentsPost(
                AdjustmentRequest(
                    productId = productId,
                    quantityDelta = delta,
                    reason = "test top-up",
                ),
                idempotencyKey = UUID.randomUUID(),
            )
            assertTrue("restock failed: ${response.errorBody()?.string()}", response.isSuccessful)
        }
        assertQuantity("restocked", target.toPlainString(), onHand())
    }

    @After
    fun tearDown() {
        if (this::directory.isInitialized) {
            directory.deleteRecursively()
        }
    }

    private fun backendIsUp(): Boolean = try {
        (URL(HEALTH_URL).openConnection() as HttpURLConnection).run {
            connectTimeout = 1500
            readTimeout = 1500
            requestMethod = "GET"
            responseCode == 200
        }
    } catch (e: Exception) {
        false
    }

    private suspend fun registerTenantWithAStockedProduct(retrofit: Retrofit) {
        val auth = retrofit.create(AuthenticationApi::class.java)
        val slug = "outbox-live-fixture"
        val email = "owner@outbox-live-fixture.test"
        val password = "SeedPassw0rd!1"

        val registered = auth.authRegisterTenantPost(
            TenantRegistrationRequest(
                businessName = "Outbox Live Fixture",
                slug = slug,
                ownerEmail = email,
                ownerPassword = password,
                ownerName = "Olive Owner",
            )
        )

        accessToken = if (registered.isSuccessful) {
            registered.body()!!.accessToken
        } else {
            assertTrue(
                "registration failed for a reason other than an existing fixture: " +
                    "${registered.code()} ${registered.errorBody()?.string()}",
                registered.code() == 409 || registered.code() == 429,
            )
            val loggedIn = auth.authLoginPost(
                com.example.inventory.api.models.LoginRequest(
                    email = email,
                    password = password,
                    tenantSlug = slug,
                )
            )
            assertTrue("login failed: ${loggedIn.errorBody()?.string()}", loggedIn.isSuccessful)
            loggedIn.body()!!.accessToken
        }

        productId = findOrCreateProduct()
    }

    private suspend fun findOrCreateProduct(): UUID {
        val sku = "LIVE-FIXTURE"

        val existing = catalog.productsGet(limit = 200, q = sku)
        assertTrue("product lookup failed", existing.isSuccessful)
        existing.body()?.items?.firstOrNull { it.sku == sku }?.let { return it.id }

        val created = catalog.productsPost(
            ProductWriteRequest(
                sku = sku,
                name = "Live Loaf",
                costPrice = BigDecimal("4.00"),
                sellingPrice = BigDecimal("10.00"),
                taxRate = BigDecimal("0.10"),
            )
        )
        assertTrue("product create failed: ${created.errorBody()?.string()}", created.isSuccessful)
        return created.body()!!.id
    }

    private suspend fun onHand(): BigDecimal {
        val response = catalog.productsGet(limit = 200)
        assertTrue("product list failed", response.isSuccessful)
        val product = response.body()!!.items!!.single { it.id == productId }
        return product.quantityOnHand
    }

    private fun assertQuantity(message: String, expected: String, actual: BigDecimal) {
        assertEquals("$message (expected $expected, was ${actual.toPlainString()})",
            0, BigDecimal(expected).compareTo(actual))
    }

    private fun queueSale(quantity: String, key: UUID = UUID.randomUUID()): UUID {
        outbox.enqueue(
            OutboxEntry(
                clientRequestId = key,
                operation = OutboxOperation.SALE,
                productId = productId,
                quantity = BigDecimal(quantity),
                capturedAt = OffsetDateTime.now(),
            )
        )
        return key
    }

    @Test
    fun `a queued sale syncs on reconnect and moves stock exactly once`() = runBlocking {
        val before = onHand()
        queueSale("3")

        val report = replayer.replayAll()

        assertEquals("the sale should have been recorded", 1, report.recorded.size)
        assertEquals(0, report.rejected.size)
        assertEquals("the queue should be empty", 0, outbox.size())

        assertQuantity("stock must reflect the sale once",
            before.subtract(BigDecimal("3")).toPlainString(), onHand())
    }

    @Test
    fun `replaying a sale the server already recorded does not take the stock twice`() =
        runBlocking {
            val before = onHand()
            val key = UUID.randomUUID()

            queueSale("2", key)
            assertEquals(1, replayer.replayAll().recorded.size)
            val afterFirst = onHand()
            assertQuantity("the first delivery moves the stock",
                before.subtract(BigDecimal("2")).toPlainString(), afterFirst)

            queueSale("2", key)
            val report = replayer.replayAll()

            assertEquals(
                "the server must recognise the replay rather than record a second sale",
                1, report.alreadyRecorded.size,
            )
            assertEquals(0, report.recorded.size)

            assertQuantity("stock must not move twice", afterFirst.toPlainString(), onHand())
        }

    @Test
    fun `a queued sale is refused when the stock went while the device was offline`() =
        runBlocking {
            val before = onHand()

            queueSale("8")

            val elsewhere = sales.salesPost(
                SaleWriteRequest(
                    lines = listOf(
                        SaleWriteRequestLinesInner(
                            productId = productId,
                            quantity = before,
                        )
                    ),
                    clientRequestId = UUID.randomUUID(),
                )
            )
            assertTrue("the other till's sale should succeed", elsewhere.isSuccessful)
            assertQuantity("the other till emptied the shelf", "0", onHand())

            val report = replayer.replayAll()

            assertEquals(
                "the queued sale must be REFUSED, not quietly recorded", 1,
                report.rejected.size,
            )
            assertEquals(0, report.recorded.size)
            assertEquals(0, report.alreadyRecorded.size)
            assertTrue("the cashier must be told", report.needsAttention)

            val rejection = report.rejected.single()
            assertNotNull("the refusal must carry the numbers", rejection.detail)
            assertTrue(
                "detail should name what was asked for: ${rejection.detail}",
                rejection.detail!!.contains("8"),
            )

            assertQuantity("a refusal must leave the balance untouched", "0", onHand())

            assertEquals(0, outbox.size())
        }

    @Test
    fun `a replayed adjustment does not move stock twice`() = runBlocking {
        val before = onHand()
        val key = UUID.randomUUID()

        repeat(2) {
            outbox.enqueue(
                OutboxEntry(
                    clientRequestId = key,
                    operation = OutboxOperation.ADJUSTMENT,
                    productId = productId,
                    quantity = BigDecimal("-2"),
                    capturedAt = OffsetDateTime.now(),
                    reason = "Damaged in transit",
                )
            )
            replayer.replayAll()
        }

        assertQuantity("two deliveries of one adjustment must remove two units, not four",
            before.subtract(BigDecimal("2")).toPlainString(), onHand())
    }
}
