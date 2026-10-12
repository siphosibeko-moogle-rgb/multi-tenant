package com.example.inventory.mobile.ui

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

sealed interface BusinessZone {
    data class Known(val zone: ZoneId) : BusinessZone

    data object NotLoaded : BusinessZone

    data class Invalid(val raw: String) : BusinessZone
}

fun businessZoneOf(timezone: String?): BusinessZone = when {
    timezone == null -> BusinessZone.NotLoaded
    timezone in ZoneId.getAvailableZoneIds() -> BusinessZone.Known(ZoneId.of(timezone))
    else -> BusinessZone.Invalid(timezone)
}

fun businessToday(clock: Clock, zone: ZoneId): LocalDate = clock.instant().atZone(zone).toLocalDate()

fun SalesPeriod.range(today: LocalDate): Pair<LocalDate, LocalDate>? =
    days?.let { today.minusDays(it) to today }
