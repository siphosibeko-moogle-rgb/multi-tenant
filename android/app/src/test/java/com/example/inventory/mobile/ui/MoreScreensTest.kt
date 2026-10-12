package com.example.inventory.mobile.ui

import com.example.inventory.api.apis.PurchasingApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.apis.SuppliersApi
import com.example.inventory.api.apis.UsersApi
import com.example.inventory.api.infrastructure.Serializer
import com.example.inventory.api.models.UserRole
import com.example.inventory.mobile.net.QueryEnumConverterFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class MoreScreensTest {

    private lateinit var server: MockWebServer
    private lateinit var retrofit: Retrofit

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        retrofit = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .addConverterFactory(QueryEnumConverterFactory)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun problem(code: Int, title: String) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/problem+json")
        .setBody("""{"type":"about:blank","title":"$title","status":$code}""")

    private fun <S> awaitState(read: () -> S, done: (S) -> Boolean): S {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val s = read()
            if (done(s)) return s
            Thread.sleep(10)
        }
        return read()
    }

    private val sale = """{"id":"${UUID.randomUUID()}","saleNumber":"S-0001","status":"completed",
        "itemCount":2,"subtotalAmount":20,"discountAmount":0,"taxAmount":3,"totalAmount":23,
        "soldAt":"2026-10-10T09:00:00+02:00"}"""

    @Test
    fun `sales filters go on the wire in the contract's lower case`() {
        server.enqueue(json("""{"items":[],"nextCursor":null}"""))
        server.enqueue(json("""{"items":[$sale],"nextCursor":null}"""))
        val vm = SalesHistoryViewModel(retrofit.create(SalesApi::class.java))
        vm.clock = Clock.fixed(Instant.parse("2026-10-11T22:30:00Z"), java.time.ZoneId.of("America/New_York"))
        vm.updateBusinessZone(BusinessZone.Known(java.time.ZoneId.of("Africa/Johannesburg")))

        vm.loadFor(1)
        server.takeRequest(5, TimeUnit.SECONDS)
        vm.setFilters(SalesApi.StatusSalesGet.PARTIALLY_REFUNDED, SalesPeriod.WEEK)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!

        val url = request.requestUrl!!
        assertEquals("partially_refunded", url.queryParameter("status"))
        assertEquals("2026-10-06", url.queryParameter("from"))
        assertEquals("2026-10-12", url.queryParameter("to"))

        val state = awaitState({ vm.state.value }) { it is PagedListViewModel.UiState.Loaded }
        assertEquals(1, (state as PagedListViewModel.UiState.Loaded).items.size)
    }

    @Test
    fun `a failed sales load is a recoverable error, and load more appends the next page`() {
        server.enqueue(problem(500, "Internal Server Error"))
        val vm = SalesHistoryViewModel(retrofit.create(SalesApi::class.java))
        vm.loadFor(1)
        assertTrue(awaitState({ vm.state.value }) { it !is PagedListViewModel.UiState.Loading } is PagedListViewModel.UiState.Failed)

        server.enqueue(json("""{"items":[$sale],"nextCursor":"abc"}"""))
        server.enqueue(json("""{"items":[${sale.replace("S-0001", "S-0002").replace(Regex("\"id\":\"[^\"]+\""), "\"id\":\"${UUID.randomUUID()}\"")}],"nextCursor":null}"""))
        vm.reload()
        awaitState({ vm.state.value }) { it is PagedListViewModel.UiState.Loaded }
        vm.loadMore()
        val state = awaitState({ vm.state.value }) {
            it is PagedListViewModel.UiState.Loaded && it.items.size == 2
        } as PagedListViewModel.UiState.Loaded

        assertEquals(listOf("S-0001", "S-0002"), state.items.map { it.saleNumber })
        assertNull(state.nextCursor)
        server.takeRequest(); server.takeRequest()
        assertEquals("abc", server.takeRequest().requestUrl!!.queryParameter("cursor"))
    }

    @Test
    fun `purchase order status filter is sent lower case`() {
        server.enqueue(json("""{"items":[],"nextCursor":null}"""))
        server.enqueue(json("""{"items":[],"nextCursor":null}"""))
        val vm = PurchaseOrdersViewModel(retrofit.create(PurchasingApi::class.java))
        vm.loadFor(1)
        server.takeRequest(5, TimeUnit.SECONDS)
        vm.selectStatus(PurchasingApi.StatusPurchaseOrdersGet.PARTIAL)
        assertEquals("partial", server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("status"))
    }

    @Test
    fun `suppliers come only from real purchase orders and real supplier reads`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        fun po(supplier: UUID, name: String) = """{"id":"${UUID.randomUUID()}","poNumber":"PO-1",
            "supplierId":"$supplier","supplierName":"$name","status":"draft","totalAmount":10,"lineCount":1}"""
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/purchase-orders") ->
                        json("""{"items":[${po(a, "Acme")},${po(a, "Acme")},${po(b, "Bolt")}],"nextCursor":null}""")
                    path.endsWith("/suppliers/$a") -> json("""{"id":"$a","name":"Acme Ltd","leadTimeDays":7,
                        "isActive":true,"phone":"012","observedLeadTime":{"sampleSize":0},"productCount":3}""")
                    else -> problem(404, "Not Found")
                }
            }
        }
        val vm = SuppliersViewModel(
            retrofit.create(PurchasingApi::class.java),
            retrofit.create(SuppliersApi::class.java),
        )
        vm.loadFor(1)
        val state = awaitState({ vm.state.value }) { it !is SuppliersViewModel.UiState.Loading }
            as SuppliersViewModel.UiState.Loaded

        assertEquals(listOf("Acme Ltd", "Bolt"), state.entries.map { it.name })
        assertEquals(2, state.entries[0].orderCount)
        assertNull(state.entries[1].detail)
        assertTrue(state.entries[0].matches("012"))
        assertTrue(!state.entries[1].matches("acme"))
    }

    @Test
    fun `a refused role change leaves the row as it was and reports why`() {
        val id = UUID.randomUUID()
        server.enqueue(json("""{"id":"${UUID.randomUUID()}","email":"o@x.co","fullName":"Owner","role":"owner",
            "tenant":{"id":"${UUID.randomUUID()}","name":"Shop","slug":"shop"},"currencyCode":"ZAR","timezone":"UTC"}"""))
        server.enqueue(json("""{"items":[{"id":"$id","email":"c@x.co","fullName":"Clerk","role":"clerk",
            "status":"active"}],"nextCursor":null}"""))
        server.enqueue(problem(403, "Forbidden"))
        val vm = UsersViewModel(retrofit.create(UsersApi::class.java))
        vm.loadFor(1)
        val loaded = awaitState({ vm.state.value }) { it is PagedListViewModel.UiState.Loaded }
            as PagedListViewModel.UiState.Loaded

        vm.changeRole(loaded.items.single(), UserRole.MANAGER)
        val action = awaitState({ vm.action.value }) { it.busy == null && it.error != null }

        server.takeRequest(); server.takeRequest()
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("""{"role":"manager"}""", patch.body.readUtf8())
        assertTrue(action.error != null)
        val after = vm.state.value as PagedListViewModel.UiState.Loaded
        assertEquals(UserRole.CLERK, after.items.single().role)
    }

    @Test
    fun `invite form requires a name, a valid email and a role`() {
        assertEquals("Enter the person's name.", validateInvite(" ", "a@b.co", UserRole.CLERK))
        assertEquals("Enter an email address.", validateInvite("Ann", "", UserRole.CLERK))
        assertEquals("Enter a valid email address.", validateInvite("Ann", "ann@", UserRole.CLERK))
        assertEquals("Choose a role.", validateInvite("Ann", "ann@shop.co", null))
        assertNull(validateInvite("Ann", "ann@shop.co", UserRole.VIEWER))
    }

    @Test
    fun `query enum converter uses the contract spelling, including non-trivial ones`() {
        assertEquals(
            "-updatedAt",
            QueryEnumConverterFactory.wireName(com.example.inventory.api.apis.CatalogApi.SortProductsGet.MINUS_UPDATED_AT),
        )
    }
}
