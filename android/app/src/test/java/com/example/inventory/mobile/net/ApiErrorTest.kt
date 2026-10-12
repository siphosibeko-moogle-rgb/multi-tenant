package com.example.inventory.mobile.net

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class ApiErrorTest {

    private fun errorResponse(code: Int, body: String): Response<Unit> =
        Response.error(code, body.toResponseBody("application/problem+json".toMediaType()))

    @Test
    fun `an oversell 409 shows the real numbers from the problem body`() {
        val error = errorResponse(
            409,
            """
            {"type":"https://api.example.com/problems/insufficient-stock",
             "title":"Insufficient stock","status":409,
             "detail":"The requested quantity is not available",
             "productId":"3f2504e0-4f89-11d3-9a0c-0305e82c3301",
             "requested":5,"available":3}
            """.trimIndent(),
        ).toApiError()

        assertEquals("Not enough stock to complete this sale.", error.message)
        assertNotNull("the numbers are the actionable part", error.detail)
        assertTrue(
            "must state what was asked for: was <${error.detail}>",
            error.detail!!.contains("5"),
        )
        assertTrue(
            "and what is actually there: was <${error.detail}>",
            error.detail.contains("3"),
        )
    }

    @Test
    fun `no message is a raw JSON dump`() {
        val bodies = mapOf(
            401 to """{"title":"Authentication failed","status":401,"detail":"Invalid email or password"}""",
            403 to """{"title":"Forbidden","status":403,"detail":"Your role does not permit this action"}""",
            404 to """{"title":"Not found","status":404,"detail":"No such product"}""",
            412 to """{"title":"Precondition failed","status":412,"detail":"modified by someone else"}""",
            429 to """{"title":"Too many requests","status":429,"detail":"Too many login attempts"}""",
            500 to """{"title":"Internal server error","status":500,"detail":"The request could not be processed"}""",
        )

        bodies.forEach { (code, body) ->
            val error = errorResponse(code, body).toApiError()

            assertFalse(
                "$code: message must not contain JSON punctuation — was <${error.message}>",
                error.message.contains("{") || error.message.contains("\""),
            )
            assertFalse(
                "$code: message must not be a bare status code — was <${error.message}>",
                error.message.matches(Regex(""".*\bError \d{3}\b.*""")),
            )
            assertTrue(
                "$code: message should read as a sentence — was <${error.message}>",
                error.message.length > 10 && error.message.first().isUpperCase(),
            )
        }
    }

    @Test
    fun `a 500 does not leak the server's internal wording`() {

        val error = errorResponse(
            500,
            """{"title":"Internal server error","status":500,"detail":"could not extract ResultSet"}""",
        ).toApiError()

        assertFalse(
            "a database phrase must not reach the shop floor — was <${error.message}>",
            error.message.contains("ResultSet"),
        )
        assertEquals("The server had a problem. Try again in a moment.", error.message)
    }

    @Test
    fun `a 409 that is not an oversell still reads sensibly`() {
        val error = errorResponse(
            409,
            """{"type":"https://api.example.com/problems/tenant-slug-taken",
                "title":"Conflict","status":409,
                "detail":"A business with that slug already exists"}""",
        ).toApiError()

        assertEquals("A business with that slug already exists", error.message)
    }

    @Test
    fun `an oversell of a never-stocked product names both numbers`() {
        val captured = """{"detail":"The requested quantity is not available",""" +
            """"instance":"/api/v1/sales","status":409,"title":"Insufficient stock",""" +
            """"type":"https://api.example.com/problems/insufficient-stock",""" +
            """"productId":"5909185a-260f-4ba9-911a-78a8d0b69647",""" +
            """"requested":6,"available":0}"""

        val error = errorResponse(409, captured).toApiError()

        assertEquals("Not enough stock to complete this sale.", error.message)
        assertNotNull("the oversell branch must produce a detail line", error.detail)
        assertTrue(
            "must name what was asked for — was <${error.detail}>",
            error.detail!!.contains("6"),
        )
        assertTrue(
            "must name what is actually available, including when that is zero — " +
                "was <${error.detail}>",
            error.detail.contains("0"),
        )
    }

    @Test
    fun `zero available survives PostgreSQL's numeric formatting`() {

        val captured = """{"detail":"The requested quantity is not available",""" +
            """"instance":"/api/v1/sales","status":409,"title":"Insufficient stock",""" +
            """"type":"https://api.example.com/problems/insufficient-stock",""" +
            """"productId":"660f1fda-65a1-4712-8d62-2fd32fb836b3",""" +
            """"requested":6,"available":0.000}"""

        val error = errorResponse(409, captured).toApiError()

        assertEquals("Not enough stock to complete this sale.", error.message)
        assertNotNull(error.detail)
        assertTrue(
            "0.000 must be shown as 0 — was <${error.detail}>",
            error.detail!!.contains("0") && !error.detail.contains("0.000"),
        )
    }

    @Test
    fun `a malformed oversell still shows the number it did receive`() {
        val body = """{"detail":"The requested quantity is not available",""" +
            """"instance":"/api/v1/sales","status":409,"title":"Insufficient stock",""" +
            """"type":"https://api.example.com/problems/insufficient-stock",""" +
            """"productId":"5909185a-260f-4ba9-911a-78a8d0b69647",""" +
            """"requested":6,"available":null}"""

        val error = errorResponse(409, body).toApiError()

        assertEquals("Not enough stock to complete this sale.", error.message)
        assertNotNull("a salvageable oversell must not fall back to the server's detail", error.detail)
        assertTrue(
            "the requested quantity arrived intact and must be shown — was <${error.detail}>",
            error.detail!!.contains("6"),
        )
        assertTrue(
            "must not degrade to echoing the server's detail — was <${error.message}>",
            !error.message.contains("The requested quantity is not available"),
        )
    }

    @Test
    fun `a 409 that is not an oversell is not salvaged into one`() {
        val body = """{"type":"https://api.example.com/problems/product-sku-taken",""" +
            """"title":"Conflict","status":409,"requested":6,""" +
            """"detail":"Another product already uses that SKU"}"""

        val error = errorResponse(409, body).toApiError()

        assertEquals("Another product already uses that SKU", error.message)
    }

    @Test
    fun `a malformed or empty body still produces a message rather than throwing`() {
        listOf("", "not json at all", "{\"unclosed\":", "<html>502 Bad Gateway</html>")
            .forEach { body ->
                val error = errorResponse(502, body).toApiError()
                assertTrue(
                    "body <$body> should still yield a message",
                    error.message.isNotBlank(),
                )
                assertFalse(error.message.contains("<html>"))
            }
    }

    @Test
    fun `network failures read as connection problems, not as server errors`() {
        assertTrue(
            ApiError.fromNetworkFailure(UnknownHostException("10.0.2.2"))
                .message.contains("reach the server"),
        )
        assertTrue(
            ApiError.fromNetworkFailure(SocketTimeoutException()).message.contains("too long"),
        )
        assertTrue(
            ApiError.fromNetworkFailure(IOException("connection refused"))
                .message.contains("reach the server"),
        )
        assertFalse(
            ApiError.fromNetworkFailure(UnknownHostException("10.0.2.2"))
                .message.contains("10.0.2.2"),
        )
    }
}
