package com.example.inventory.mobile.ui

import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.infrastructure.Serializer
import com.example.inventory.api.models.Product
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class RecordSaleViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: RecordSaleViewModel
    private lateinit var outbox: com.example.inventory.mobile.offline.Outbox
    private lateinit var outboxDirectory: java.io.File

    private val product = Product(
        id = UUID.randomUUID(),
        sku = "SKU-1",
        name = "Bread",
        unitOfMeasure = "each",
        costPrice = BigDecimal("7.25"),
        sellingPrice = BigDecimal("12.50"),
        taxRate = BigDecimal("0.15"),
        quantityOnHand = BigDecimal("3"),
        quantityAvailable = BigDecimal("3"),
        stockState = Product.StockState.OK,
        isActive = true,
        updatedAt = OffsetDateTime.parse("2026-08-18T09:00:00+02:00"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .build()
        val api = retrofit.create(SalesApi::class.java)

        outboxDirectory = java.nio.file.Files.createTempDirectory("vm-outbox").toFile()
        outbox = com.example.inventory.mobile.offline.Outbox(
            java.io.File(outboxDirectory, "pending.log")
        )
        viewModel = RecordSaleViewModel(
            api,
            outbox,
            com.example.inventory.mobile.offline.OutboxCoordinator(
                outbox,
                com.example.inventory.mobile.offline.OutboxReplayer(
                    outbox,
                    api,
                    retrofit.create(com.example.inventory.api.apis.InventoryApi::class.java),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        outboxDirectory.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun bodyOf(request: RecordedRequest): String = request.body.readUtf8()

    private fun awaitSettled(timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!viewModel.state.value.submitting) {
                Thread.sleep(20)
                if (!viewModel.state.value.submitting) return
            }
            Thread.sleep(10)
        }
        throw AssertionError("request did not settle within ${timeoutMillis}ms")
    }

    private fun saleResponse(number: String) = MockResponse().setResponseCode(201).setBody(
        """{"id":"${UUID.randomUUID()}","saleNumber":"$number","status":"completed",
            "itemCount":1,"subtotalAmount":10.00,"discountAmount":0,"taxAmount":1.50,
            "totalAmount":11.50,"soldAt":"2026-08-18T10:00:00+02:00",
            "locationId":"${UUID.randomUUID()}","createdBy":"${UUID.randomUUID()}",
            "lines":[{"productId":"${product.id}","sku":"SKU-1","name":"Bread",
                      "quantity":1,"unitPrice":10.00,"discountAmount":0,"lineTotal":10.00,
                      "returnedQuantity":0,"outstandingQuantity":1}]}"""
    )

    @Test
    fun `clientRequestId survives a retry, so a resend cannot record the sale twice`() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(saleResponse("S-1"))

        viewModel.startSale(product)
        viewModel.setQuantity("2")
        viewModel.save()
        awaitSettled()

        assertTrue(
            "a network failure queues the sale rather than reporting an error",
            viewModel.state.value.queuedOffline,
        )
        assertNull("and it is not an error", viewModel.state.value.error)
        assertEquals(
            "the queued copy carries the same key the retry will use",
            viewModel.state.value.pendingRequestId,
            outbox.pending().single().clientRequestId,
        )
        assertNotNull(
            "and the key must be KEPT — dropping it is what would double-record",
            viewModel.state.value.pendingRequestId,
        )

        viewModel.retry()
        awaitSettled()

        val first = server.takeRequest()
        val second = server.takeRequest()
        val firstId = Regex(""""clientRequestId":"([^"]+)"""").find(bodyOf(first))?.groupValues?.get(1)
        val secondId = Regex(""""clientRequestId":"([^"]+)"""").find(bodyOf(second))?.groupValues?.get(1)

        assertNotNull("the first request must carry a clientRequestId", firstId)
        assertEquals(
            "THE assertion: the retry must reuse the SAME key. A fresh id would be a " +
                "second sale to the server, taking the stock twice, and nobody would notice " +
                "until someone counted the shelf.",
            firstId,
            secondId,
        )

        assertNull("a recorded sale clears the key", viewModel.state.value.pendingRequestId)
        assertEquals("S-1", viewModel.state.value.recordedSaleNumber)
    }

    @Test
    fun `correcting the quantity after a refusal keeps the same key`() {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/problem+json")
                .setBody(
                    """{"type":"https://api.example.com/problems/insufficient-stock",
                        "title":"Insufficient stock","status":409,
                        "productId":"${product.id}","requested":5,"available":3}"""
                )
        )
        server.enqueue(saleResponse("S-1"))

        viewModel.startSale(product)
        viewModel.setQuantity("5")
        viewModel.save()
        awaitSettled()

        val keyAfterRefusal = viewModel.state.value.pendingRequestId
        assertNotNull("a refused sale keeps its key", keyAfterRefusal)

        viewModel.setQuantity("3")
        viewModel.save()
        awaitSettled()

        val firstId = Regex(""""clientRequestId":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)
        val secondId = Regex(""""clientRequestId":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)

        assertNotNull(firstId)
        assertEquals(
            "a corrected quantity is the same sale, so it must carry the same key",
            firstId,
            secondId,
        )
        assertEquals("S-1", viewModel.state.value.recordedSaleNumber)
    }

    @Test
    fun `a new sale after a successful one gets a fresh key`() {
        server.enqueue(saleResponse("S-1"))
        server.enqueue(saleResponse("S-2"))

        viewModel.startSale(product)
        viewModel.save()
        awaitSettled()

        viewModel.startSale(product)
        viewModel.save()
        awaitSettled()

        val firstId = Regex(""""clientRequestId":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)
        val secondId = Regex(""""clientRequestId":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)

        assertNotEquals("two separate sales are two keys", firstId, secondId)
    }

    @Test
    fun `soldAt is RFC 3339 and carries an explicit UTC offset`() {
        server.enqueue(saleResponse("S-1"))

        viewModel.startSale(product)
        viewModel.save()
        awaitSettled()

        val soldAt = Regex(""""soldAt":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)

        assertNotNull("soldAt must be sent, not left for the server to invent", soldAt)
        assertTrue(
            "must be RFC 3339 with an explicit offset (or Z) — a naive local time would " +
                "let the server guess the zone, and it would guess its own: was <$soldAt>",
            soldAt!!.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})""")),
        )
    }

    @Test
    fun `soldAt is captured at the tap, not at the send`() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(saleResponse("S-1"))

        viewModel.startSale(product)
        viewModel.save()
        awaitSettled()

        viewModel.retry()
        awaitSettled()

        val first = Regex(""""soldAt":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)
        val second = Regex(""""soldAt":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)

        assertEquals("the retry must resend the original business time", first, second)
    }

    @Test
    fun `an oversell 409 shows the requested and available numbers`() {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/problem+json")
                .setBody(
                    """{"detail":"The requested quantity is not available",
                        "instance":"/api/v1/sales","status":409,
                        "title":"Insufficient stock",
                        "type":"https://api.example.com/problems/insufficient-stock",
                        "productId":"${product.id}","requested":6,"available":0}"""
                )
        )

        viewModel.startSale(product)
        viewModel.setQuantity("6")
        viewModel.save()
        awaitSettled()

        val error = viewModel.state.value.error
        assertNotNull(error)
        assertEquals("Not enough stock to complete this sale.", error!!.message)
        assertTrue(
            "the shortfall must name what was asked for: was <${error.detail}>",
            error.detail!!.contains("6"),
        )
        assertTrue(
            "and what is actually there, including when that is zero: was <${error.detail}>",
            error.detail.contains("0"),
        )
        assertTrue(
            "must not fall back to the server's detail: was <${error.message}>",
            !error.message.contains("The requested quantity is not available"),
        )
        assertNotNull(
            "a refused sale keeps its key: the cashier may fix the quantity and retry, and " +
                "that is still the same sale",
            viewModel.state.value.pendingRequestId,
        )
    }

    @Test
    fun `a sale made with the server unreachable is queued, not lost`() {
        server.shutdown()

        viewModel.startSale(product)
        viewModel.setQuantity("3")
        viewModel.save()
        awaitSettled()

        val queued = outbox.pending().single()

        assertEquals(product.id, queued.productId)
        assertEquals(0, BigDecimal("3").compareTo(queued.quantity))
        assertEquals(
            com.example.inventory.mobile.offline.OutboxOperation.SALE,
            queued.operation,
        )

        assertTrue("the cashier must be told it is queued", viewModel.state.value.queuedOffline)
        assertNull("a queued sale is not an error", viewModel.state.value.error)
        assertNull(
            "there is no receipt number until the server assigns one",
            viewModel.state.value.recordedSaleNumber,
        )
    }

    @Test
    fun `the queued sale carries the key and time the ViewModel is still holding`() {
        server.shutdown()

        viewModel.startSale(product)
        viewModel.setQuantity("2")
        viewModel.save()
        awaitSettled()

        val queued = outbox.pending().single()

        assertEquals(viewModel.state.value.pendingRequestId, queued.clientRequestId)
        assertEquals(viewModel.state.value.soldAt, queued.capturedAt)
    }

    @Test
    fun `queueing the same unsent sale twice leaves one entry`() {
        server.shutdown()

        viewModel.startSale(product)
        viewModel.setQuantity("1")
        viewModel.save()
        awaitSettled()
        val first = outbox.pending().single().clientRequestId

        viewModel.save()
        awaitSettled()

        assertEquals(1, outbox.size())
        assertEquals(first, outbox.pending().single().clientRequestId)
    }
}
