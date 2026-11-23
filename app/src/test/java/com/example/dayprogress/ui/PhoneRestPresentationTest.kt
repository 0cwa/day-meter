package com.example.dayprogress.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.R
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.widget.WidgetDisplayFormatter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneRestPresentationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() = TimeZone.setDefault(originalTimeZone)

    @Test
    fun historyShowsDatedEndsAndAShortComparisonAcrossMidnight() {
        val beforeMidnight = timestamp(1, 23, 55)
        val afterMidnight = timestamp(3, 0, 5)
        val entries = PhoneRestHistoryFormatter.entries(context, listOf(beforeMidnight, afterMidnight))
        assertEquals(2, entries.size)
        assertTrue(entries.first().contains(context.getString(R.string.phone_rest_history_later, "10 minutes")))
        assertTrue(entries.first().contains("2026"))
        assertTrue(entries.last().contains(context.getString(R.string.phone_rest_history_first)))
    }

    @Test
    fun historyEarlierAndLaterLabelsHaveNoTargetSchedule() {
        val entries = PhoneRestHistoryFormatter.entries(context,
            listOf(timestamp(1, 8, 0), timestamp(2, 7, 30), timestamp(3, 7, 30)))
        assertTrue(entries[0].contains(context.getString(R.string.phone_rest_history_same)))
        assertTrue(entries[1].contains(context.getString(R.string.phone_rest_history_earlier, "30 minutes")))
    }

    @Test
    fun historyPreservesRecordedOrderAfterAClockRollback() {
        val previousRecordedEnd = timestamp(3, 7, 30)
        val latestRecordedEnd = timestamp(2, 8, 0)
        val entries = PhoneRestHistoryFormatter.entries(context, listOf(previousRecordedEnd, latestRecordedEnd))
        assertTrue(entries.first().startsWith(PhoneRestHistoryFormatter.formatDateTime(context, latestRecordedEnd)))
        assertTrue(entries.first().contains(context.getString(R.string.phone_rest_history_later, "30 minutes")))
        assertTrue(entries.last().contains(context.getString(R.string.phone_rest_history_first)))
    }

    @Test
    fun activeDayPastTheReferenceShowsComparisonInsteadOfCompletion() {
        val status = DayRepository.DayStatus(
            state = DayRepository.DayState.ACTIVE,
            progress = 100,
            startTimeMillis = 1L,
            endTimeMillis = 100_000L,
            timeRemainingMillis = 0L,
            phoneRestMode = true,
            referenceEndTimeMillis = 100_000L,
            comparisonMillis = 30 * 60_000L,
            elapsedMillis = 17 * 60 * 60_000L
        )
        val display = WidgetDisplayFormatter.format(context, status, null, emptyList(), true)
        assertEquals(context.getString(R.string.phone_rest_widget_past, "30m"), display.primary)
        assertTrue(display.contentDescription.contains("30 minutes"))
        assertTrue(display.contentDescription.contains(context.getString(R.string.phone_rest_widget_reference_description)))
        assertFalse(display.primary.contains(context.getString(R.string.widget_day_complete)))
    }

    @Test
    fun firstDayUsesElapsedTimeUntilAConfirmedReferenceExists() {
        val status = DayRepository.DayStatus(DayRepository.DayState.ACTIVE, 0, 1L, -1L, 0L,
            phoneRestMode = true, elapsedMillis = 2 * 60 * 60_000L)
        val display = WidgetDisplayFormatter.format(context, status, null, emptyList(), false)
        assertEquals(context.getString(R.string.phone_rest_widget_elapsed, "2h"), display.primary)
        assertTrue(display.contentDescription.contains("2 hours"))
    }

    private fun timestamp(day: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
    }.timeInMillis
}
