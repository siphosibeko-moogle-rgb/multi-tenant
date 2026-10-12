package com.example.inventory.mobile.ui

import com.example.inventory.api.apis.PurchasingApi
import com.example.inventory.api.infrastructure.Serializer
import com.example.inventory.api.models.PurchaseOrderDetail
import com.example.inventory.mobile.net.QueryEnumConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseOrderActionsTest {

    private val poId = UUID.randomUUID()
    private val bread = UUID.randomUUID()
    private val milk = UUID.randomUUID()

    private fun order(status: String, breadReceived: String = "0", milkReceived: String = "0") = """
        {"id":"$poId","poNumber":"PO-0007","supplierId":"${UUID.randomUUID()}","supplierName":"Acme",
         "status":"$status","totalAmount":150,"lineCount":2,"locationId":"${UUID.randomUUID()}",
         "lines":[
          {"productId":"$bread","sku":"BRD","name":"Bread","quantityOrdered":10,"quantityReceived":$breadReceived,
           "quantityOutstanding":${10 - breadReceived.toInt()},"unitCost":10,"lineTotal":100},
          {"productId":"$milk","sku":"MLK","name":"Milk","quantityOrdered":5,"quantityReceived":$milkReceived,
           "quantityOutstanding":${5 - milkReceived.toInt()},"unitCost":10,"lineTotal":50}]}
    """.trimIndent()

    private fun parse(json: String): PurchaseOrderDetail =
        Serializer.moshi.adapter(PurchaseOrderDetail::class.java).fromJson(json)!!

    @Test
    fun `mark as ordered is offered only on a draft, and only to owner and manager`() {
        val draft = parse(order("draft"))
        assertTrue(purchaseOrderActions(draft, "owner").markOrdered)
        assertTrue(purchaseOrderActions(draft, "manager").markOrdered)
        assertFalse(purchaseOrderActions(draft, "clerk").markOrdered)
        assertFalse(purchaseOrderActions(draft, "viewer").markOrdered)
        assertFalse("unknown role sees the least", purchaseOrderActions(draft, null).markOrdered)
        assertFalse("a draft cannot receive", purchaseOrderActions(draft, "owner").receive)
        for (status in listOf("ordered", "partial", "received", "cancelled")) {
            assertFalse(status, purchaseOrderActions(parse(order(status)), "owner").markOrdered)
        }
    }

    @Test
    fun `receive is offered on ordered and partial orders, to owner, manager and clerk`() {
        for (status in listOf("ordered", "partial")) {
            val o = parse(order(status, breadReceived = "4"))
            assertTrue(purchaseOrderActions(o, "owner").receive)
            assertTrue(purchaseOrderActions(o, "manager").receive)
            assertTrue("receiving is a clerk's job", purchaseOrderActions(o, "clerk").receive)
            assertFalse(purchaseOrderActions(o, "viewer").receive)
        }
        assertFalse(purchaseOrderActions(parse(order("received", "10", "5")), "owner").receive)
        assertFalse(purchaseOrderActions(parse(order("cancelled")), "owner").receive)
        assertFalse(
            "nothing outstanding, nothing to receive",
            purchaseOrderActions(parse(order("partial", "10", "5")), "owner").receive,
        )
    }

    @Test
    fun `receipt quantities follow the server's rules`() {
        val lines = parse(order("partial", breadReceived = "7")).lines

        assertEquals(
            "Enter the quantity received for at least one product.",
            (checkReceipt(lines, mapOf(bread to " ", milk to "")) as ReceiptCheck.Invalid).message,
        )
        assertTrue((checkReceipt(lines, mapOf(bread to "two")) as ReceiptCheck.Invalid).message.contains("as a number"))
        assertTrue((checkReceipt(lines, mapOf(bread to "0")) as ReceiptCheck.Invalid).message.contains("more than zero"))
        assertTrue((checkReceipt(lines, mapOf(bread to "-1")) as ReceiptCheck.Invalid).message.contains("more than zero"))
        assertEquals(
            "Only 3 of Bread are still to come on this order.",
            (checkReceipt(lines, mapOf(bread to "4")) as ReceiptCheck.Invalid).message,
        )

        val ok = checkReceipt(lines, mapOf(bread to "3", milk to "2.5")) as ReceiptCheck.Ok
        assertEquals(listOf(bread, milk), ok.lines.map { it.productId })
        assertEquals("2.5", ok.lines[1].quantityReceived.toPlainString())
        assertNull("unit cost is left to the server", ok.lines[0].unitCost)

        val partial = checkReceipt(lines, mapOf(milk to "1")) as ReceiptCheck.Ok
        assertEquals("a blank line is simply not received", listOf(milk), partial.lines.map { it.productId })
    }

    private lateinit var server: MockWebServer
    private lateinit var vm: PurchaseOrderDetailViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .client(com.example.inventory.mobile.net.NetworkModule.withoutConnectionRetry(okhttp3.OkHttpClient()))
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .addConverterFactory(QueryEnumConverterFactory)
            .build()
            .create(PurchasingApi::class.java)
        vm = PurchaseOrderDetailViewModel(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun problem(code: Int, title: String, detail: String) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/problem+json")
        .setBody("""{"type":"about:blank","title":"$title","status":$code,"detail":"$detail"}""")

    private fun loaded(): PurchaseOrderDetailViewModel.UiState.Loaded {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val s = vm.state.value
            if (s is PurchaseOrderDetailViewModel.UiState.Loaded && s.pending == null) return s
            Thread.sleep(10)
        }
        error("did not settle: ${vm.state.value}")
    }

    private fun loadOrder(status: String, breadReceived: String = "0") {
        server.enqueue(json(order(status, breadReceived)))
        vm.load(poId)
        loaded()
        server.takeRequest(5, TimeUnit.SECONDS)
    }

    @Test
    fun `mark as ordered shows the status the server returned`() {
        loadOrder("draft")
        server.enqueue(json(order("ordered")))

        vm.markOrdered()
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/v1/purchase-orders/$poId/submit", request.path)

        val after = loaded()
        assertEquals(PurchaseOrderDetail.Status.ORDERED, after.order.status)
        assertEquals("PO-0007 is now marked as ordered.", after.notice)
        assertNull(after.actionError)
    }

    @Test
    fun `a refused mark as ordered keeps the server's status and says why`() {
        loadOrder("draft")
        server.enqueue(problem(409, "Conflict", "This purchase order is ordered and cannot be submitted"))
        server.enqueue(json(order("ordered")))

        vm.markOrdered()
        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS)!!.method)

        val after = loaded()
        assertNotNull(after.actionError)
        assertEquals(PurchaseOrderDetail.Status.ORDERED, after.order.status)
    }

    @Test
    fun `a 403 changes nothing on screen`() {
        loadOrder("draft")
        server.enqueue(problem(403, "Forbidden", "Your account does not have permission"))
        server.enqueue(json(order("draft")))

        vm.markOrdered()
        val after = loaded()
        assertNotNull(after.actionError)
        assertEquals(PurchaseOrderDetail.Status.DRAFT, after.order.status)
    }

    @Test
    fun `tapping mark as ordered twice sends one request`() {
        loadOrder("draft")
        server.enqueue(json(order("ordered")).setBodyDelay(300, TimeUnit.MILLISECONDS))

        vm.markOrdered()
        vm.markOrdered()
        loaded()

        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNull("no second submit", server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a receipt sends only the lines entered, and the order comes back from the server`() {
        loadOrder("ordered")
        server.enqueue(json(order("partial", breadReceived = "4")).setResponseCode(201))

        vm.receive(mapOf(bread to "4", milk to ""))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/purchase-orders/$poId/receipts", request.path)
        assertEquals("""{"lines":[{"productId":"$bread","quantityReceived":"4"}]}""", request.body.readUtf8())
        assertNotNull("contract's Idempotency-Key is sent", request.getHeader("Idempotency-Key"))

        val after = loaded()
        assertEquals(PurchaseOrderDetail.Status.PARTIAL, after.order.status)
        assertEquals("4", after.order.lines[0].quantityReceived.toPlainString())
        assertEquals(1, after.receipts)
    }

    @Test
    fun `an invalid receipt is never sent`() {
        loadOrder("ordered")
        vm.receive(mapOf(bread to "11"))

        assertEquals("Only 10 of Bread are still to come on this order.", loaded().receiptMessage)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `the server's over-receipt refusal reaches the form`() {
        loadOrder("ordered")
        server.enqueue(problem(409, "Conflict", "Cannot receive 4 of BRD — only 2 remain outstanding on this order"))
        server.enqueue(json(order("partial", breadReceived = "8")))

        vm.receive(mapOf(bread to "4"))
        server.takeRequest(5, TimeUnit.SECONDS)
        server.takeRequest(5, TimeUnit.SECONDS)

        val after = loaded()
        assertTrue(after.receiptMessage!!.contains("only 2 remain outstanding"))
        assertEquals("the re-read figures replace the stale ones", "8", after.order.lines[0].quantityReceived.toPlainString())
        assertEquals(0, after.receipts)
    }

    @Test
    fun `tapping receive twice sends one receipt`() {
        loadOrder("ordered")
        server.enqueue(json(order("partial", breadReceived = "4")).setResponseCode(201).setBodyDelay(300, TimeUnit.MILLISECONDS))

        vm.receive(mapOf(bread to "4"))
        vm.receive(mapOf(bread to "4"))
        loaded()

        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNull("no second receipt", server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a dropped connection re-reads the order and does not resend the receipt`() {
        loadOrder("ordered")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json(order("partial", breadReceived = "4")))

        vm.receive(mapOf(bread to "4"))
        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS)!!.method)

        val after = loaded()
        assertTrue(after.receiptMessage!!.contains("not known whether that was saved"))
        assertEquals("4", after.order.lines[0].quantityReceived.toPlainString())
        assertEquals(0, after.receipts)
        assertNull("no automatic resend", server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a retried receipt reuses its idempotency key, a new one gets a new key`() {
        loadOrder("ordered")
        server.enqueue(problem(500, "Internal Server Error", "boom"))
        server.enqueue(json(order("ordered")))
        server.enqueue(json(order("partial", breadReceived = "4")).setResponseCode(201))
        server.enqueue(json(order("partial", breadReceived = "5")).setResponseCode(201))

        vm.receive(mapOf(bread to "4"))
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Idempotency-Key")
        server.takeRequest(5, TimeUnit.SECONDS)
        loaded()

        vm.receive(mapOf(bread to "4"))
        val retry = server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Idempotency-Key")
        loaded()
        assertEquals(first, retry)

        vm.receive(mapOf(bread to "1"))
        val next = server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Idempotency-Key")
        loaded()
        assertTrue(next != retry)
    }
}
