package com.example.inventory.mobile.ui

import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.infrastructure.Serializer
import com.example.inventory.mobile.net.QueryEnumConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
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
class BusinessTimeTest {

    private val joburg = ZoneId.of("Africa/Johannesburg")
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")

    private lateinit var originalDefault: TimeZone

    @Before
    fun phoneIsElsewhere() {
        originalDefault = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
    }

    @After
    fun restore() {
        TimeZone.setDefault(originalDefault)
    }

    private fun at(instant: String) = Clock.fixed(Instant.parse(instant), newYork)

    @Test
    fun `Johannesburg today is the local date, whatever zone the phone is in`() {
        assertEquals(LocalDate.parse("2026-10-11"), businessToday(at("2026-10-11T08:00:00Z"), joburg))
    }

    @Test
    fun `one minute either side of Johannesburg midnight`() {
        assertEquals(LocalDate.parse("2026-10-11"), businessToday(at("2026-10-11T21:59:00Z"), joburg))
        assertEquals(LocalDate.parse("2026-10-12"), businessToday(at("2026-10-11T22:00:00Z"), joburg))
        assertEquals(LocalDate.parse("2026-10-11"), LocalDate.now(at("2026-10-11T22:00:00Z")))
    }

    @Test
    fun `a business behind UTC rolls over later, not earlier`() {
        assertEquals(LocalDate.parse("2026-10-11"), businessToday(at("2026-10-12T03:30:00Z"), newYork))
    }

    @Test
    fun `period ranges end on the business day and include it`() {
        val today = LocalDate.parse("2026-10-12")
        assertNull(SalesPeriod.ALL.range(today))
        assertEquals(today to today, SalesPeriod.TODAY.range(today))
        assertEquals(LocalDate.parse("2026-10-06") to today, SalesPeriod.WEEK.range(today))
        assertEquals(LocalDate.parse("2026-09-13") to today, SalesPeriod.MONTH.range(today))
    }

    @Test
    fun `a month range crosses a month and a year boundary correctly`() {
        assertEquals(
            LocalDate.parse("2025-12-03") to LocalDate.parse("2026-01-01"),
            SalesPeriod.MONTH.range(LocalDate.parse("2026-01-01")),
        )
    }

    @Test
    fun `London spring forward - the 23-hour day keeps its date until local midnight`() {
        assertEquals(LocalDate.parse("2026-03-29"), businessToday(at("2026-03-29T00:30:00Z"), london))
        assertEquals(LocalDate.parse("2026-03-29"), businessToday(at("2026-03-29T22:59:00Z"), london))
        assertEquals(LocalDate.parse("2026-03-30"), businessToday(at("2026-03-29T23:00:00Z"), london))
    }

    @Test
    fun `London fall back - the 25-hour day keeps its date until local midnight`() {
        assertEquals(LocalDate.parse("2026-10-25"), businessToday(at("2026-10-24T23:00:00Z"), london))
        assertEquals(LocalDate.parse("2026-10-25"), businessToday(at("2026-10-25T23:59:00Z"), london))
        assertEquals(LocalDate.parse("2026-10-26"), businessToday(at("2026-10-26T00:00:00Z"), london))
    }

    @Test
    fun `the zone comes from the session, and missing or invalid is explicit`() {
        assertEquals(BusinessZone.Known(joburg), businessZoneOf("Africa/Johannesburg"))
        assertEquals(BusinessZone.Known(ZoneId.of("UTC")), businessZoneOf("UTC"))
        assertEquals(BusinessZone.NotLoaded, businessZoneOf(null))
        assertEquals(BusinessZone.Invalid("Mars/Olympus_Mons"), businessZoneOf("Mars/Olympus_Mons"))
        assertEquals(BusinessZone.Invalid("+02:00"), businessZoneOf("+02:00"))
        assertEquals(BusinessZone.Invalid(""), businessZoneOf(""))
    }

    private lateinit var server: MockWebServer
    private lateinit var sales: SalesApi

    private fun startServer() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        sales = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .addConverterFactory(QueryEnumConverterFactory)
            .build()
            .create(SalesApi::class.java)
    }

    private fun stopServer() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun empty() = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"items":[],"nextCursor":null}""")

    @Test
    fun `Today asks the server for the business day, not the phone's`() {
        startServer()
        try {
            repeat(2) { server.enqueue(empty()) }
            val vm = SalesHistoryViewModel(sales)
            vm.clock = at("2026-10-11T22:30:00Z")
            vm.updateBusinessZone(BusinessZone.Known(joburg))
            vm.loadFor(1)
            server.takeRequest(5, TimeUnit.SECONDS)

            vm.setFilters(null, SalesPeriod.TODAY)
            val url = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
            assertEquals("2026-10-12", url.queryParameter("from"))
            assertEquals("2026-10-12", url.queryParameter("to"))
        } finally {
            stopServer()
        }
    }

    @Test
    fun `without a business zone, date filters are refused rather than guessed`() {
        startServer()
        try {
            server.enqueue(empty())
            val vm = SalesHistoryViewModel(sales)
            vm.clock = Clock.fixed(Instant.parse("2026-10-11T22:30:00Z"), ZoneOffset.UTC)
            vm.loadFor(1)
            val first = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
            assertNull("All time needs no zone", first.queryParameter("from"))

            vm.setFilters(null, SalesPeriod.TODAY)
            assertEquals("no request on the phone's date", SalesPeriod.ALL, vm.period)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally {
            stopServer()
        }
    }

    @Test
    fun `losing the zone drops an active date filter`() {
        startServer()
        try {
            repeat(3) { server.enqueue(empty()) }
            val vm = SalesHistoryViewModel(sales)
            vm.clock = at("2026-10-11T10:00:00Z")
            vm.updateBusinessZone(BusinessZone.Known(joburg))
            vm.loadFor(1)
            vm.setFilters(null, SalesPeriod.WEEK)
            vm.updateBusinessZone(BusinessZone.Invalid("Nowhere/Land"))

            assertEquals(SalesPeriod.ALL, vm.period)
            val urls = List(3) { server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!! }
            assertEquals(1, urls.count { it.queryParameter("from") != null })
            assertTrue(urls.any { it.queryParameter("from") == null && it.queryParameter("to") == null })
        } finally {
            stopServer()
        }
    }
}
