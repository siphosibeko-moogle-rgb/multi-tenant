package com.example.inventory.mobile.offline

import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.models.AdjustmentRequest
import com.example.inventory.api.models.SaleWriteRequest
import com.example.inventory.api.models.SaleWriteRequestLinesInner
import com.example.inventory.mobile.net.toApiError
import retrofit2.Response

class OutboxReplayer(
    private val outbox: Outbox,
    private val sales: SalesApi,
    private val inventory: InventoryApi,
) {

    suspend fun replayAll(): ReplayReport {
        var report = ReplayReport()

        for (entry in outbox.pending()) {
            when (val outcome = replay(entry)) {
                is ReplayOutcome.Recorded -> {
                    outbox.remove(entry.clientRequestId)
                    report = report.copy(recorded = report.recorded + outcome)
                }

                is ReplayOutcome.AlreadyRecorded -> {
                    outbox.remove(entry.clientRequestId)
                    report = report.copy(alreadyRecorded = report.alreadyRecorded + outcome)
                }

                is ReplayOutcome.Rejected -> {
                    outbox.remove(entry.clientRequestId)
                    report = report.copy(rejected = report.rejected + outcome)
                }

                is ReplayOutcome.Deferred -> {
                    outbox.recordFailure(entry.clientRequestId, outcome.reason)
                    report = report.copy(deferred = report.deferred + outcome)
                    return report
                }
            }
        }
        return report
    }

    private suspend fun replay(entry: OutboxEntry): ReplayOutcome = try {
        when (entry.operation) {
            OutboxOperation.SALE -> classify(entry, sendSale(entry))
            OutboxOperation.ADJUSTMENT -> classify(entry, sendAdjustment(entry))
        }
    } catch (e: Exception) {
        ReplayOutcome.Deferred(entry, describe(e))
    }

    private suspend fun sendSale(entry: OutboxEntry): Response<*> = sales.salesPost(
        SaleWriteRequest(
            lines = listOf(
                SaleWriteRequestLinesInner(
                    productId = entry.productId,
                    quantity = entry.quantity,
                )
            ),
            clientRequestId = entry.clientRequestId,
            soldAt = entry.capturedAt,
        )
    )

    private suspend fun sendAdjustment(entry: OutboxEntry): Response<*> =
        inventory.inventoryAdjustmentsPost(
            AdjustmentRequest(
                productId = entry.productId,
                quantityDelta = entry.quantity,
                reason = entry.reason ?: "Recorded offline",
                occurredAt = entry.capturedAt,
            ),
            idempotencyKey = entry.clientRequestId,
        )

    private fun classify(entry: OutboxEntry, response: Response<*>): ReplayOutcome = when {
        response.code() == 200 -> ReplayOutcome.AlreadyRecorded(entry)
        response.isSuccessful -> ReplayOutcome.Recorded(entry)

        response.code() == 401 -> ReplayOutcome.Deferred(entry, "Sign in again to sync")

        response.code() == 408 || response.code() == 429 || response.code() >= 500 ->
            ReplayOutcome.Deferred(entry, "The server is busy. Will retry.")

        else -> {
            val apiError = response.toApiError()
            ReplayOutcome.Rejected(entry, apiError.message, apiError.detail)
        }
    }

    private fun describe(e: Exception): String =
        com.example.inventory.mobile.net.ApiError.fromNetworkFailure(e).message
}
