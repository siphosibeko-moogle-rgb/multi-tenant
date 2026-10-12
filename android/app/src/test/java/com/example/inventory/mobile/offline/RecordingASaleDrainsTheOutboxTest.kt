package com.example.inventory.mobile.offline

import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.infrastructure.Serializer
import com.example.inventory.api.models.Product
import com.example.inventory.mobile.ui.RecordSaleViewModel
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingASaleDrainsTheOutboxTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var outbox: Outbox
    private lateinit var coordinator: OutboxCoordinator
    private lateinit var viewModel: RecordSaleViewModel

    private val product = Product(
        id = UUID.randomUUID(),
        sku = "SKU-DRAIN",
        name = "Bread",
        unitOfMeasure = "each",
        costPrice = BigDecimal("7.25"),
        sellingPrice = BigDecimal("12.50"),
        taxRate = BigDecimal("0.15"),
        quantityOnHand = BigDecimal("9"),
        quantityAvailable = BigDecimal("9"),
        stockState = Product.StockState.OK,
        isActive = true,
        updatedAt = OffsetDateTime.parse("2026-08-26T09:00:00+02:00"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()

        directory = Files.createTempDirectory("drain-test").toFile()
        outbox = Outbox(File(directory, "pending.log"))

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .build()

        val sales = retrofit.create(SalesApi::class.java)
        coordinator = OutboxCoordinator(
            outbox = outbox,
            replayer = OutboxReplayer(
                outbox = outbox,
                sales = sales,
                inventory = retrofit.create(InventoryApi::class.java),
            ),
        )
        viewModel = RecordSaleViewModel(sales, outbox, coordinator)
    }

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun saleBody(saleNumber: String) = MockResponse()
        .setResponseCode(201)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"id":"${UUID.randomUUID()}","saleNumber":"$saleNumber","status":"completed",
             "itemCount":1,"locationId":"${UUID.randomUUID()}","subtotalAmount":12.50,
             "discountAmount":0.00,"taxAmount":1.88,"totalAmount":14.38,
             "soldAt":"2026-08-26T09:00:00Z","createdBy":"${UUID.randomUUID()}","lines":[]}
            """.trimIndent()
        )

    private fun waitUntil(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun queueAnOfflineSale(): UUID {
        val key = UUID.randomUUID()
        outbox.enqueue(
            OutboxEntry(
                clientRequestId = key,
                operation = OutboxOperation.SALE,
                productId = product.id,
                quantity = BigDecimal("4"),
                capturedAt = OffsetDateTime.parse("2026-08-25T18:30:00+02:00"),
            )
        )
        return key
    }

    @Test
    fun `recording a sale online drains a sale queued earlier while offline`() {
        val queuedYesterday = queueAnOfflineSale()
        assertEquals(1, outbox.size())

        server.enqueue(saleBody("S-NEW"))
        server.enqueue(saleBody("S-QUEUED"))

        viewModel.startSale(product)
        viewModel.setQuantity("1")
        viewModel.save()
        waitUntil("the new sale to be recorded") {
            viewModel.state.value.recordedSaleNumber != null
        }

        assertEquals("the new sale was recorded", "S-NEW", viewModel.state.value.recordedSaleNumber)

        waitUntil("the queued sale to be replayed") { outbox.size() == 0 }

        assertEquals(
            "recording a sale must drain the queue; the outbox still holds " +
                "${outbox.pending().map { it.clientRequestId }}",
            0, outbox.size(),
        )

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(
            "the second request must be the replay of the queued sale",
            second.body.readUtf8().contains(queuedYesterday.toString()),
        )
        assertTrue(
            "and the first must be the sale just made, not the queued one",
            !first.body.readUtf8().contains(queuedYesterday.toString()),
        )
    }

    @Test
    fun `the pending count reflects the queue after capture and after drain`() {
        server.shutdown()

        viewModel.startSale(product)
        viewModel.setQuantity("2")
        viewModel.save()
        waitUntil("the failed send to be captured") { outbox.size() == 1 }

        assertEquals("the sale was captured", 1, outbox.size())
        assertEquals(
            "the badge must show it — a queue nobody can see is a queue nobody " +
                "notices has stopped draining",
            1, coordinator.pending.value,
        )
    }

    @Test
    fun `a drain with an empty queue makes no requests`() {
        kotlinx.coroutines.runBlocking { coordinator.syncNow() }

        assertEquals(0, server.requestCount)
        assertEquals(0, coordinator.pending.value)
    }
}
