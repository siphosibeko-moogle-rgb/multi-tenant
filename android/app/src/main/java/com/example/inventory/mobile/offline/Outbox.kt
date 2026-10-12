package com.example.inventory.mobile.offline

import java.io.File
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class Outbox(private val file: File) {

    @Synchronized
    fun enqueue(entry: OutboxEntry) {
        val entries = readAll().toMutableList()
        if (entries.none { it.clientRequestId == entry.clientRequestId }) {
            entries.add(entry)
            writeAll(entries)
        }
    }

    @Synchronized
    fun pending(): List<OutboxEntry> = readAll()

    @Synchronized
    fun size(): Int = readAll().size

    @Synchronized
    fun remove(clientRequestId: UUID) {
        writeAll(readAll().filterNot { it.clientRequestId == clientRequestId })
    }

    @Synchronized
    fun recordFailure(clientRequestId: UUID, error: String) {
        writeAll(
            readAll().map {
                if (it.clientRequestId == clientRequestId) {
                    it.copy(attempts = it.attempts + 1, lastError = error)
                } else {
                    it
                }
            }
        )
    }

    @Synchronized
    fun clear() {
        writeAll(emptyList())
    }

    private fun readAll(): List<OutboxEntry> {
        if (!file.exists()) {
            return emptyList()
        }
        return file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { decode(it) }.getOrNull() }
    }

    private fun writeAll(entries: List<OutboxEntry>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(entries.joinToString("\n") { encode(it) })
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun encode(entry: OutboxEntry): String = listOf(
        FORMAT_VERSION,
        entry.clientRequestId.toString(),
        entry.operation.name,
        entry.productId.toString(),
        entry.quantity.toPlainString(),
        entry.capturedAt.toString(),
        escape(entry.reason),
        entry.attempts.toString(),
        escape(entry.lastError),
    ).joinToString(FIELD_SEPARATOR)

    private fun decode(line: String): OutboxEntry {
        val parts = line.split(FIELD_SEPARATOR)
        require(parts.size == FIELD_COUNT) { "expected $FIELD_COUNT fields, got ${parts.size}" }
        require(parts[0] == FORMAT_VERSION) { "unknown format ${parts[0]}" }
        return OutboxEntry(
            clientRequestId = UUID.fromString(parts[1]),
            operation = OutboxOperation.valueOf(parts[2]),
            productId = UUID.fromString(parts[3]),
            quantity = BigDecimal(parts[4]),
            capturedAt = OffsetDateTime.parse(parts[5]),
            reason = unescape(parts[6]),
            attempts = parts[7].toInt(),
            lastError = unescape(parts[8]),
        )
    }

    private fun escape(value: String?): String = when (value) {
        null -> NULL_MARKER
        else -> value
            .replace("\\", "\\\\")
            .replace(FIELD_SEPARATOR, "\\u001F")
            .replace("\n", "\\n")
    }

    private fun unescape(value: String): String? = when (value) {
        NULL_MARKER -> null
        else -> value
            .replace("\\n", "\n")
            .replace("\\u001F", FIELD_SEPARATOR)
            .replace("\\\\", "\\")
    }

    private companion object {
        const val FORMAT_VERSION = "v1"

        const val FIELD_COUNT = 9

        val FIELD_SEPARATOR = 0x1F.toChar().toString()

        val NULL_MARKER = 0x1E.toChar().toString()
    }
}
