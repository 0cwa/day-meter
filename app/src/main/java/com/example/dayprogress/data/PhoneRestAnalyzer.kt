package com.example.dayprogress.data

import java.util.Calendar
import java.util.TimeZone

/**
 * Infers day boundaries from observed phone use and phone-free intervals, without a clock cutoff.
 * A put-down is provisional after five minutes; it becomes a day end only after a longer rest.
 * These are phone-activity estimates, not measurements of sleep.
 */
object PhoneRestAnalyzer {
    const val PUT_DOWN_DELAY_MILLIS = 5 * 60_000L
    const val MAX_HISTORY_SIZE = 30

    enum class EventType { USE, IDLE, UNKNOWN }

    data class Event(val timestampMillis: Long, val type: EventType)

    data class Config(
        val minimumRestMillis: Long = 3 * 60 * 60_000L,
        val minimumAwakeMillis: Long = 8 * 60 * 60_000L,
        val wakeConfirmationMillis: Long = 5 * 60_000L,
        val protectedWakeStartMinutes: Int? = null,
        val protectedWakeEndMinutes: Int? = null,
        val timeZone: TimeZone = TimeZone.getDefault()
    ) {
        init {
            require(minimumRestMillis >= PUT_DOWN_DELAY_MILLIS)
            require(minimumAwakeMillis >= 0L)
            require(wakeConfirmationMillis > 0L)
            require((protectedWakeStartMinutes == null) == (protectedWakeEndMinutes == null))
            if (protectedWakeStartMinutes != null && protectedWakeEndMinutes != null) {
                require(protectedWakeStartMinutes in 0..1439)
                require(protectedWakeEndMinutes in 0..1439)
                require(protectedWakeStartMinutes != protectedWakeEndMinutes)
            }
        }
    }

    data class RestPeriod(
        val dayStartMillis: Long,
        val endMillis: Long,
        /** First observed use of the next sustained session, not a measured wake-up time. */
        val wakeMillis: Long
    )

    data class Snapshot(
        /** Exclusive end of the last event query; an event exactly here belongs to the next scan. */
        val cursorMillis: Long = 0L,
        val currentDayStartMillis: Long = -1L,
        val activeSinceMillis: Long = -1L,
        val idleSinceMillis: Long = -1L,
        val confirmedEndMillis: Long = -1L,
        val history: List<RestPeriod> = emptyList()
    )

    fun advance(
        snapshot: Snapshot,
        events: Iterable<Event>,
        nowMillis: Long,
        config: Config = Config()
    ): Snapshot {
        if (nowMillis <= snapshot.cursorMillis) return snapshot

        var dayStart = snapshot.currentDayStartMillis
        var activeSince = snapshot.activeSinceMillis
        var idleSince = snapshot.idleSinceMillis
        var confirmedEnd = snapshot.confirmedEndMillis
        val history = snapshot.history.takeLast(MAX_HISTORY_SIZE).toMutableList()

        fun advanceTo(timeMillis: Long) {
            if (confirmedEnd >= 0L) {
                // A short phone check remains part of the same rest. Protection applies to
                // that entire rest, but stops at the onset of a sustained waking session.
                val restEnd = if (activeSince >= 0L) activeSince else timeMillis
                if (overlapsProtectedWake(confirmedEnd - PUT_DOWN_DELAY_MILLIS, restEnd, config)) {
                    confirmedEnd = -1L
                }
            }

            if (
                confirmedEnd < 0L &&
                eligibleIdle(dayStart, idleSince, timeMillis, config) &&
                timeMillis - idleSince >= config.minimumRestMillis
            ) {
                confirmedEnd = idleSince + PUT_DOWN_DELAY_MILLIS
            }

            if (activeSince >= 0L && timeMillis - activeSince >= config.wakeConfirmationMillis) {
                if (dayStart < 0L) {
                    dayStart = activeSince
                } else if (confirmedEnd >= 0L) {
                    history += RestPeriod(dayStart, confirmedEnd, activeSince)
                    if (history.size > MAX_HISTORY_SIZE) history.removeAt(0)
                    dayStart = activeSince
                    confirmedEnd = -1L
                }
            }
        }

        events.asSequence()
            .filter { it.timestampMillis >= snapshot.cursorMillis && it.timestampMillis < nowMillis }
            .sortedBy(Event::timestampMillis)
            .forEach { event ->
                advanceTo(event.timestampMillis)
                when (event.type) {
                    EventType.USE -> {
                        if (activeSince < 0L) activeSince = event.timestampMillis
                        idleSince = -1L
                    }
                    EventType.IDLE -> {
                        if (idleSince < 0L) idleSince = event.timestampMillis
                        activeSince = -1L
                    }
                    EventType.UNKNOWN -> {
                        // Never join intervals across a reboot, failed query, or lost history.
                        // An unfinished rest is discarded; already completed history is retained.
                        activeSince = -1L
                        idleSince = -1L
                        confirmedEnd = -1L
                    }
                }
            }
        advanceTo(nowMillis)

        return Snapshot(
            cursorMillis = nowMillis,
            currentDayStartMillis = dayStart,
            activeSinceMillis = activeSince,
            idleSinceMillis = idleSince,
            confirmedEndMillis = confirmedEnd,
            history = history.toList()
        )
    }

    /** The five-minute boundary, if currently supported; confirmation is a separate state. */
    fun candidateEndMillis(
        snapshot: Snapshot,
        nowMillis: Long,
        config: Config = Config()
    ): Long? {
        if (snapshot.confirmedEndMillis >= 0L) {
            val restEnd = snapshot.activeSinceMillis.takeIf { it >= 0L } ?: nowMillis
            return snapshot.confirmedEndMillis.takeUnless {
                overlapsProtectedWake(it - PUT_DOWN_DELAY_MILLIS, restEnd, config)
            }
        }
        return snapshot.idleSinceMillis.takeIf {
            eligibleIdle(snapshot.currentDayStartMillis, it, nowMillis, config) &&
                nowMillis - it >= PUT_DOWN_DELAY_MILLIS
        }?.plus(PUT_DOWN_DELAY_MILLIS)
    }

    /**
     * Project the previous end's local clock time to its next occurrence after this day starts.
     * Calendar dates, rather than fixed 24-hour durations, preserve the reference through DST.
     */
    fun referenceEndMillis(
        previousEndMillis: Long,
        dayStartMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        require(previousEndMillis >= 0L && dayStartMillis >= 0L)
        val previous = Calendar.getInstance(timeZone).apply { timeInMillis = previousEndMillis }
        val day = startOfDay(dayStartMillis, timeZone)
        val minutes = previous.get(Calendar.HOUR_OF_DAY) * 60 + previous.get(Calendar.MINUTE)
        val subMinute = previous.get(Calendar.SECOND) * 1_000L + previous.get(Calendar.MILLISECOND)
        val sameDate = DayWindowCalculator.atClockMillis(day.timeInMillis, minutes, timeZone = timeZone) + subMinute
        return if (sameDate > dayStartMillis) sameDate else {
            DayWindowCalculator.atClockMillis(day.timeInMillis, minutes, 1, timeZone) + subMinute
        }
    }

    /** Signed local-clock shift; midnight is continuous and neither direction is a judgment. */
    fun comparisonDeltaMinutes(
        previousEndMillis: Long,
        currentEndMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Int {
        fun clockMinutes(time: Long): Int = Calendar.getInstance(timeZone).run {
            timeInMillis = time
            get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
        }
        val difference = clockMinutes(currentEndMillis) - clockMinutes(previousEndMillis)
        return when {
            difference > 12 * 60 -> difference - 24 * 60
            difference < -12 * 60 -> difference + 24 * 60
            else -> difference
        }
    }

    private fun eligibleIdle(dayStart: Long, idleSince: Long, now: Long, config: Config): Boolean {
        return dayStart >= 0L && idleSince >= dayStart && now >= idleSince &&
            idleSince - dayStart >= config.minimumAwakeMillis &&
            !overlapsProtectedWake(idleSince, now, config)
    }

    /** Half-open intervals allow a rest ending exactly when protected waking hours begin. */
    private fun overlapsProtectedWake(start: Long, end: Long, config: Config): Boolean {
        val startMinutes = config.protectedWakeStartMinutes ?: return false
        val endMinutes = config.protectedWakeEndMinutes ?: return false
        if (end <= start) return false
        val day = startOfDay(start, config.timeZone).apply { add(Calendar.DAY_OF_MONTH, -1) }
        while (day.timeInMillis < end) {
            val protectedStart = DayWindowCalculator.atClockMillis(day.timeInMillis, startMinutes, timeZone = config.timeZone)
            val protectedEnd = DayWindowCalculator.atClockMillis(
                day.timeInMillis,
                endMinutes,
                if (endMinutes < startMinutes) 1 else 0,
                config.timeZone
            )
            if (protectedStart < protectedEnd && start < protectedEnd && end > protectedStart) return true
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        return false
    }

    private fun startOfDay(timeMillis: Long, timeZone: TimeZone): Calendar =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
