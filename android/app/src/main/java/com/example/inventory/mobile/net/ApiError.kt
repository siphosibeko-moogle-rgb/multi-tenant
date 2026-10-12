package com.example.inventory.mobile.net

import com.example.inventory.api.models.InsufficientStockProblem
import com.example.inventory.api.models.Problem
import com.squareup.moshi.Moshi
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import retrofit2.Response

data class ApiError(val message: String, val detail: String? = null) {

    companion object {

        fun fromNetworkFailure(e: Throwable): ApiError = when (e) {
            is UnknownHostException -> ApiError(
                "Can't reach the server. Check the connection and try again."
            )
            is SocketTimeoutException -> ApiError(
                "The server took too long to answer. Try again."
            )
            is IOException -> ApiError(
                "Can't reach the server. Check the connection and try again."
            )
            else -> ApiError("Something went wrong. Try again.")
        }
    }
}

fun <T> Response<T>.toApiError(moshi: Moshi = ProblemParsing.moshi): ApiError {
    val raw = errorBody()?.string().orEmpty()
    val problem = ProblemParsing.parse(raw, moshi)

    val insufficient = ProblemParsing.parseInsufficientStock(raw, moshi)
    if (code() == 409 && insufficient != null) {
        return ApiError(
            message = "Not enough stock to complete this sale.",
            detail = shortfall(
                requested = insufficient.requested.stripTrailingZeros().toPlainString(),
                available = insufficient.available.stripTrailingZeros().toPlainString(),
            ),
        )
    }

    if (code() == 409 && ProblemParsing.isInsufficientStock(problem)) {
        val salvaged = ProblemParsing.salvageNumbers(raw, moshi)
        if (salvaged.requested != null || salvaged.available != null) {
            return ApiError(
                message = "Not enough stock to complete this sale.",
                detail = shortfall(salvaged.requested, salvaged.available),
            )
        }
    }

    val serverDetail = problem?.detail?.takeIf { it.isNotBlank() }

    return when (code()) {
        400, 422 -> ApiError(
            "Some details need fixing.",
            serverDetail ?: problem?.errors?.firstOrNull()?.let { "${it.field} ${it.message}" },
        )
        401 -> ApiError("Your email or password was not recognised.")
        403 -> ApiError("Your account does not have permission to do that.")
        404 -> ApiError("That item no longer exists.")
        409 -> ApiError(serverDetail ?: "That conflicts with something already saved.")
        412 -> ApiError("Someone else changed this while you were editing. Reload and try again.")
        429 -> ApiError("Too many attempts. Wait a moment and try again.")
        in 500..599 -> ApiError("The server had a problem. Try again in a moment.")
        else -> ApiError(serverDetail ?: "Something went wrong. Try again.")
    }
}

private fun shortfall(requested: String?, available: String?): String = when {
    requested != null && available != null ->
        "You asked for $requested but only $available are available."
    requested != null -> "You asked for $requested, which is more than is in stock."
    available != null -> "Only $available are available."
    else -> "There is not enough stock."
}

object ProblemParsing {

    val moshi: Moshi by lazy { com.example.inventory.api.infrastructure.Serializer.moshi }

    fun parse(body: String, moshi: Moshi = this.moshi): Problem? = try {
        if (body.isBlank()) null else moshi.adapter(Problem::class.java).fromJson(body)
    } catch (e: Exception) {
        null
    }

    fun isInsufficientStock(problem: Problem?): Boolean =
        problem?.type?.toString()?.endsWith("/insufficient-stock") == true

    data class SalvagedNumbers(val requested: String?, val available: String?)

    fun salvageNumbers(body: String, moshi: Moshi = this.moshi): SalvagedNumbers = try {
        @Suppress("UNCHECKED_CAST")
        val map = moshi.adapter(Map::class.java).fromJson(body) as? Map<String, Any?>
        SalvagedNumbers(number(map?.get("requested")), number(map?.get("available")))
    } catch (e: Exception) {
        SalvagedNumbers(null, null)
    }

    private fun number(value: Any?): String? = when (value) {
        null -> null
        is java.math.BigDecimal -> value.stripTrailingZeros().toPlainString()
        is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is String -> value.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
        else -> null
    }

    fun parseInsufficientStock(body: String, moshi: Moshi = this.moshi): InsufficientStockProblem? =
        try {
            if (body.isBlank()) {
                null
            } else {
                moshi.adapter(InsufficientStockProblem::class.java).fromJson(body)
            }
        } catch (e: Exception) {
            null
        }
}
