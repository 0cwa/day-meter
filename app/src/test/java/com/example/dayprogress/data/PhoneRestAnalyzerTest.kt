package com.example.dayprogress.data

import com.example.dayprogress.data.PhoneRestAnalyzer.Config
import com.example.dayprogress.data.PhoneRestAnalyzer.Event
import com.example.dayprogress.data.PhoneRestAnalyzer.EventType
import com.example.dayprogress.data.PhoneRestAnalyzer.RestPeriod
import com.example.dayprogress.data.PhoneRestAnalyzer.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PhoneRestAnalyzerTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val config = Config(timeZone = utc)

    @Test
    fun initialDayRequiresSustainedUseAndIsBackdatedToItsOnset() {
        val interrupted = analyze(6 * MINUTE, use(0), idle(4 * MINUTE))
        assertEquals(-1L, interrupted.currentDayStartMillis)

        val started = PhoneRestAnalyzer.advance(interrupted, listOf(use(10 * MINUTE)), 15 * MINUTE, config)
        assertEquals(10 * MINUTE, started.currentDayStartMillis)
        assertTrue(started.history.isEmpty())
    }

    @Test
    fun fiveMinutesIsProvisionalAndLongRestConfirmsOriginalBoundary() {
        val events = listOf(use(0), idle(10 * HOUR))
        val before = PhoneRestAnalyzer.advance(Snapshot(), events, 10 * HOUR + 5 * MINUTE - 1, config)
        assertNull(PhoneRestAnalyzer.candidateEndMillis(before, before.cursorMillis, config))

        val provisional = PhoneRestAnalyzer.advance(before, events, 10 * HOUR + 5 * MINUTE, config)
        assertEquals(10 * HOUR + 5 * MINUTE, PhoneRestAnalyzer.candidateEndMillis(provisional, provisional.cursorMillis, config))
        assertEquals(-1L, provisional.confirmedEndMillis)

        val confirmed = PhoneRestAnalyzer.advance(provisional, events, 13 * HOUR, config)
        assertEquals(10 * HOUR + 5 * MINUTE, confirmed.confirmedEndMillis)
        assertEquals(0L, confirmed.currentDayStartMillis)
        assertTrue(confirmed.history.isEmpty())
    }

    @Test
    fun ordinaryBreakDoesNotEndTheDay() {
        val result = analyze(11 * HOUR, use(0), idle(10 * HOUR), use(10 * HOUR + 30 * MINUTE))
        assertEquals(0L, result.currentDayStartMillis)
        assertEquals(-1L, result.confirmedEndMillis)
        assertNull(PhoneRestAnalyzer.candidateEndMillis(result, result.cursorMillis, config))
        assertTrue(result.history.isEmpty())
    }

    @Test
    fun earlyLowPhonePeriodCannotBecomeEligibleMerelyByLastingLonger() {
        val result = analyze(36 * HOUR, use(0), idle(HOUR))
        assertEquals(0L, result.currentDayStartMillis)
        assertEquals(-1L, result.confirmedEndMillis)
        assertNull(PhoneRestAnalyzer.candidateEndMillis(result, result.cursorMillis, config))
    }

    @Test
    fun minimumAwakeDurationIsCheckedAtPutDownWithInclusiveThreshold() {
        val early = analyze(12 * HOUR, use(0), idle(8 * HOUR - 1))
        val eligible = analyze(12 * HOUR, use(0), idle(8 * HOUR))
        assertEquals(-1L, early.confirmedEndMillis)
        assertEquals(8 * HOUR + 5 * MINUTE, eligible.confirmedEndMillis)
    }

    @Test
    fun sustainedWakeStartsNextDayAndStoresCompletedRest() {
        val result = analyze(18 * HOUR + 5 * MINUTE, use(0), idle(10 * HOUR), use(18 * HOUR))
        assertEquals(18 * HOUR, result.currentDayStartMillis)
        assertEquals(-1L, result.confirmedEndMillis)
        assertEquals(listOf(RestPeriod(0L, 10 * HOUR + 5 * MINUTE, 18 * HOUR)), result.history)
    }

    @Test
    fun briefPhoneChecksKeepOriginalConfirmedEndUntilSustainedWake() {
        val events = listOf(
            use(0), idle(10 * HOUR),
            use(14 * HOUR), idle(14 * HOUR + 2 * MINUTE),
            use(18 * HOUR)
        )
        val beforeWake = PhoneRestAnalyzer.advance(Snapshot(), events, 18 * HOUR + 5 * MINUTE - 1, config)
        assertEquals(0L, beforeWake.currentDayStartMillis)
        assertEquals(10 * HOUR + 5 * MINUTE, beforeWake.confirmedEndMillis)
        assertTrue(beforeWake.history.isEmpty())

        val awake = PhoneRestAnalyzer.advance(beforeWake, events, 18 * HOUR + 5 * MINUTE, config)
        assertEquals(18 * HOUR, awake.currentDayStartMillis)
        assertEquals(10 * HOUR + 5 * MINUTE, awake.history.single().endMillis)
    }

    @Test
    fun ongoingDayDoesNotResetAtMidnightOrAfterTwentyFourHours() {
        val result = analyze(50 * HOUR, use(0), idle(2 * HOUR), use(3 * HOUR))
        assertEquals(0L, result.currentDayStartMillis)
        assertEquals(-1L, result.confirmedEndMillis)
        assertTrue(result.history.isEmpty())
    }

    @Test
    fun rotatingLongCyclesAreNotTiedToCalendarDates() {
        val result = analyze(
            66 * HOUR + 5 * MINUTE,
            use(0), idle(20 * HOUR), use(31 * HOUR), idle(55 * HOUR), use(66 * HOUR)
        )
        assertEquals(66 * HOUR, result.currentDayStartMillis)
        assertEquals(
            listOf(
                RestPeriod(0L, 20 * HOUR + 5 * MINUTE, 31 * HOUR),
                RestPeriod(31 * HOUR, 55 * HOUR + 5 * MINUTE, 66 * HOUR)
            ),
            result.history
        )
    }

    @Test
    fun duplicateAndUnsortedEventsPreserveRealUseAndPutDownOnsets() {
        val result = analyze(
            18 * HOUR + 5 * MINUTE,
            idle(10 * HOUR + MINUTE), use(18 * HOUR + 2 * MINUTE),
            use(0), idle(10 * HOUR), use(18 * HOUR), idle(10 * HOUR), use(0)
        )
        assertEquals(18 * HOUR, result.currentDayStartMillis)
        assertEquals(10 * HOUR + 5 * MINUTE, result.history.single().endMillis)
    }

    @Test
    fun queryEndIsExclusiveAndNextScanIncludesEventExactlyAtCursor() {
        val events = listOf(use(0), idle(10 * HOUR), use(18 * HOUR))
        val first = PhoneRestAnalyzer.advance(Snapshot(), events, 10 * HOUR, config)
        assertEquals(-1L, first.idleSinceMillis)
        val second = PhoneRestAnalyzer.advance(first, events, 18 * HOUR, config)
        assertEquals(10 * HOUR, second.idleSinceMillis)
        assertEquals(10 * HOUR + 5 * MINUTE, second.confirmedEndMillis)
        val third = PhoneRestAnalyzer.advance(second, events, 18 * HOUR + 5 * MINUTE, config)
        assertEquals(18 * HOUR, third.currentDayStartMillis)
        assertEquals(1, third.history.size)
    }

    @Test
    fun repeatedScansDoNotDuplicateHistoryOrChangeBoundaries() {
        val events = listOf(use(0), idle(10 * HOUR), use(18 * HOUR))
        val first = PhoneRestAnalyzer.advance(Snapshot(), events, 19 * HOUR, config)
        assertEquals(first, PhoneRestAnalyzer.advance(first, events, 19 * HOUR, config))
        assertEquals(first, PhoneRestAnalyzer.advance(first, events, 18 * HOUR, config))
        val later = PhoneRestAnalyzer.advance(first, events, 20 * HOUR, config)
        assertEquals(first.history, later.history)
        assertEquals(first.currentDayStartMillis, later.currentDayStartMillis)
    }

    @Test
    fun futureEventsCannotChangeCurrentState() {
        val result = analyze(6 * MINUTE, use(0), idle(10 * HOUR), unknown(20 * HOUR))
        assertEquals(0L, result.currentDayStartMillis)
        assertEquals(0L, result.activeSinceMillis)
        assertEquals(-1L, result.idleSinceMillis)
        assertEquals(-1L, result.confirmedEndMillis)
    }

    @Test
    fun emptyHistoryAndUnobservedLeadingSilenceDoNotInventSleep() {
        val empty = analyze(48 * HOUR)
        assertEquals(-1L, empty.currentDayStartMillis)
        assertEquals(-1L, empty.confirmedEndMillis)

        val onlyOff = analyze(48 * HOUR, idle(HOUR))
        assertEquals(-1L, onlyOff.currentDayStartMillis)
        assertEquals(-1L, onlyOff.confirmedEndMillis)

        val knownStart = PhoneRestAnalyzer.advance(Snapshot(currentDayStartMillis = 0), emptyList(), 48 * HOUR, config)
        assertEquals(-1L, knownStart.confirmedEndMillis)
    }

    @Test
    fun unknownDataBreaksPendingRestAndNeverBridgesToLaterSilence() {
        val result = analyze(30 * HOUR, use(0), idle(10 * HOUR), unknown(11 * HOUR))
        assertEquals(-1L, result.idleSinceMillis)
        assertEquals(-1L, result.activeSinceMillis)
        assertEquals(-1L, result.confirmedEndMillis)
        assertTrue(result.history.isEmpty())
    }

    @Test
    fun unknownDataDiscardsUnfinishedConfirmedRestButKeepsCompletedHistory() {
        val result = analyze(
            50 * HOUR,
            use(0), idle(10 * HOUR), use(18 * HOUR),
            idle(30 * HOUR), unknown(34 * HOUR), use(40 * HOUR)
        )
        assertEquals(18 * HOUR, result.currentDayStartMillis)
        assertEquals(-1L, result.confirmedEndMillis)
        assertEquals(listOf(RestPeriod(0L, 10 * HOUR + 5 * MINUTE, 18 * HOUR)), result.history)
    }

    @Test
    fun historyIsBoundedToMostRecentThirtyCompletedRests() {
        val events = mutableListOf(use(0))
        repeat(32) { index ->
            events += idle(index * DAY + 16 * HOUR)
            events += use((index + 1) * DAY)
        }
        val result = PhoneRestAnalyzer.advance(Snapshot(), events, 32 * DAY + 5 * MINUTE, config)
        assertEquals(30, result.history.size)
        assertEquals(2 * DAY, result.history.first().dayStartMillis)
        assertEquals(31 * DAY, result.history.last().dayStartMillis)
        assertEquals(32 * DAY, result.currentDayStartMillis)
    }

    @Test
    fun protectedWakeHoursRejectAnyOverlapNotOnlyRestOnset() {
        val protected = config.copy(protectedWakeStartMinutes = 12 * 60, protectedWakeEndMinutes = 13 * 60)
        val result = PhoneRestAnalyzer.advance(Snapshot(), listOf(use(0), idle(10 * HOUR)), 15 * HOUR, protected)
        assertEquals(-1L, result.confirmedEndMillis)
        assertNull(PhoneRestAnalyzer.candidateEndMillis(result, result.cursorMillis, protected))
    }

    @Test
    fun protectedWakeHoursCanCrossMidnight() {
        val protected = config.copy(protectedWakeStartMinutes = 22 * 60, protectedWakeEndMinutes = 6 * 60)
        val result = PhoneRestAnalyzer.advance(Snapshot(), listOf(use(0), idle(21 * HOUR)), 29 * HOUR, protected)
        assertEquals(-1L, result.confirmedEndMillis)
        assertNull(PhoneRestAnalyzer.candidateEndMillis(result, result.cursorMillis, protected))
    }

    @Test
    fun restEndingExactlyAtProtectedWakeStartIsEligible() {
        val protected = config.copy(protectedWakeStartMinutes = 9 * 60, protectedWakeEndMinutes = 17 * 60)
        val result = PhoneRestAnalyzer.advance(
            Snapshot(), listOf(use(12 * HOUR), idle(24 * HOUR), use(33 * HOUR)), 33 * HOUR + 5 * MINUTE, protected
        )
        assertEquals(33 * HOUR, result.currentDayStartMillis)
        assertEquals(24 * HOUR + 5 * MINUTE, result.history.single().endMillis)
    }

    @Test
    fun continuingRestIntoProtectedWakeHoursRevokesPendingConfirmation() {
        val protected = config.copy(protectedWakeStartMinutes = 9 * 60, protectedWakeEndMinutes = 17 * 60)
        val events = listOf(use(12 * HOUR), idle(24 * HOUR))
        val initiallyConfirmed = PhoneRestAnalyzer.advance(Snapshot(), events, 28 * HOUR, protected)
        assertEquals(24 * HOUR + 5 * MINUTE, initiallyConfirmed.confirmedEndMillis)
        val later = PhoneRestAnalyzer.advance(initiallyConfirmed, events, 34 * HOUR, protected)
        assertEquals(-1L, later.confirmedEndMillis)
        assertTrue(later.history.isEmpty())
    }

    @Test
    fun daylightSavingGapUsesActualElapsedRestDuration() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val start = localTime(zone, 2025, Calendar.MARCH, 8, 14, 0)
        val off = localTime(zone, 2025, Calendar.MARCH, 9, 1, 30)
        val events = listOf(use(start), idle(off))
        val before = PhoneRestAnalyzer.advance(
            Snapshot(), events, localTime(zone, 2025, Calendar.MARCH, 9, 4, 30), config.copy(timeZone = zone)
        )
        assertEquals(-1L, before.confirmedEndMillis)
        val after = PhoneRestAnalyzer.advance(
            before, events, localTime(zone, 2025, Calendar.MARCH, 9, 5, 30), config.copy(timeZone = zone)
        )
        assertEquals(off + 5 * MINUTE, after.confirmedEndMillis)
    }

    @Test
    fun protectedWakeRangeAcrossDaylightSavingGapStillRejectsActualOverlap() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val protected = config.copy(timeZone = zone, protectedWakeStartMinutes = 110, protectedWakeEndMinutes = 190)
        val result = PhoneRestAnalyzer.advance(
            Snapshot(),
            listOf(use(localTime(zone, 2025, Calendar.MARCH, 8, 14, 0)), idle(localTime(zone, 2025, Calendar.MARCH, 9, 1, 30))),
            localTime(zone, 2025, Calendar.MARCH, 9, 5, 30),
            protected
        )
        assertEquals(-1L, result.confirmedEndMillis)
    }

    @Test
    fun referenceEndKeepsLocalClockAcrossSpringAndFallChanges() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val springReference = PhoneRestAnalyzer.referenceEndMillis(
            localTime(zone, 2025, Calendar.MARCH, 8, 4, 5),
            localTime(zone, 2025, Calendar.MARCH, 8, 12, 0), zone
        )
        val fallReference = PhoneRestAnalyzer.referenceEndMillis(
            localTime(zone, 2025, Calendar.NOVEMBER, 1, 4, 5),
            localTime(zone, 2025, Calendar.NOVEMBER, 1, 12, 0), zone
        )
        assertEquals(localTime(zone, 2025, Calendar.MARCH, 9, 4, 5), springReference)
        assertEquals(localTime(zone, 2025, Calendar.NOVEMBER, 2, 4, 5), fallReference)
    }

    @Test
    fun earlierAndLaterComparisonsAreContinuousAcrossMidnight() {
        val previous = localTime(utc, 2025, Calendar.JUNE, 1, 23, 55)
        val later = localTime(utc, 2025, Calendar.JUNE, 3, 0, 5)
        assertEquals(10, PhoneRestAnalyzer.comparisonDeltaMinutes(previous, later, utc))
        assertEquals(-10, PhoneRestAnalyzer.comparisonDeltaMinutes(later, previous, utc))
    }

    @Test
    fun sameLocalClockDoesNotShowAnArtificialDstShift() {
        val zone = TimeZone.getTimeZone("America/New_York")
        assertEquals(
            0,
            PhoneRestAnalyzer.comparisonDeltaMinutes(
                localTime(zone, 2025, Calendar.MARCH, 8, 4, 5),
                localTime(zone, 2025, Calendar.MARCH, 9, 4, 5), zone
            )
        )
    }

    private fun analyze(now: Long, vararg events: Event): Snapshot =
        PhoneRestAnalyzer.advance(Snapshot(), events.asList(), now, config)

    private fun use(time: Long) = Event(time, EventType.USE)
    private fun idle(time: Long) = Event(time, EventType.IDLE)
    private fun unknown(time: Long) = Event(time, EventType.UNKNOWN)

    private fun localTime(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
