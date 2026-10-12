package com.example.inventory.mobile.offline

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class OutboxCoordinator @Inject constructor(
    private val outbox: Outbox,
    private val replayer: OutboxReplayer,
) {

    private val _pending = MutableStateFlow(0)

    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val _lastReport = MutableStateFlow<ReplayReport?>(null)

    val lastReport: StateFlow<ReplayReport?> = _lastReport.asStateFlow()

    private val draining = Mutex()

    fun refreshPendingCount() {
        _pending.value = outbox.size()
    }

    suspend fun syncNow(): ReplayReport = draining.withLock {
        val report = replayer.replayAll()
        _pending.value = outbox.size()
        if (report.syncedCount > 0 || report.needsAttention) {
            _lastReport.value = report
        }
        report
    }

    fun acknowledgeReport() {
        _lastReport.value = null
    }
}
