package com.example.inventory.mobile.offline

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

enum class OutboxOperation { SALE, ADJUSTMENT }

data class OutboxEntry(
    val clientRequestId: UUID,
    val operation: OutboxOperation,
    val productId: UUID,
    val quantity: BigDecimal,
    val capturedAt: OffsetDateTime,
    val reason: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
)

sealed interface ReplayOutcome {

    data class Recorded(val entry: OutboxEntry) : ReplayOutcome

    data class AlreadyRecorded(val entry: OutboxEntry) : ReplayOutcome

    data class Rejected(val entry: OutboxEntry, val message: String, val detail: String? = null)
        : ReplayOutcome

    data class Deferred(val entry: OutboxEntry, val reason: String) : ReplayOutcome
}

data class ReplayReport(
    val recorded: List<ReplayOutcome.Recorded> = emptyList(),
    val alreadyRecorded: List<ReplayOutcome.AlreadyRecorded> = emptyList(),
    val rejected: List<ReplayOutcome.Rejected> = emptyList(),
    val deferred: List<ReplayOutcome.Deferred> = emptyList(),
) {
    val syncedCount: Int get() = recorded.size + alreadyRecorded.size

    val needsAttention: Boolean get() = rejected.isNotEmpty()
}
