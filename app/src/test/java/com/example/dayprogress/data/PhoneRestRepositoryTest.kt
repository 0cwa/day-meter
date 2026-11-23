package com.example.dayprogress.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowUsageStatsManager
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneRestRepositoryTest {
    private lateinit var context: Context
    private lateinit var prefs: AppPreferences
    private lateinit var store: PhoneRestStore
    private val originalTimeZone = TimeZone.getDefault()
    private val minute = 60_000L
    private val dayStart get() = at(3, 14)
    private val previousRest get() = PhoneRestAnalyzer.RestPeriod(at(2, 14), at(3, 6, 5), dayStart)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context).apply { phoneRestEnabled = true }
        store = PhoneRestStore(context)
        allowUsage(true)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun passingMidnightAndThePreviousEndDoesNotCompleteOrResetTheDay() {
        val now = at(4, 7)
        seed(now, activeSince = at(4, 5))
        val repository = DayRepository(context)
        repository.checkAndResetDay(now)
        val status = repository.getDayStatus(now)

        assertEquals(DayRepository.DayState.ACTIVE, status.state)
        assertTrue(status.phoneRestMode)
        assertEquals(dayStart, status.startTimeMillis)
        assertEquals(at(4, 6, 5), status.referenceEndTimeMillis)
        assertEquals(55 * minute, status.comparisonMillis)
        assertEquals(99, status.progress)
        assertEquals("2024-06-03", repository.getCurrentDayWindow(now).logicalDayId)
        assertEquals(dayStart, repository.getEffectiveStartTime(now))
        assertEquals(-1L, repository.getCurrentDayEndTime(now))
        assertNull(prefs.lastResetDate)
    }

    @Test
    fun fiveMinutePutDownIsPendingUntilLongerRestConfirmsTheSameBoundary() {
        val putDown = at(4, 6)
        val pendingAt = putDown + 6 * minute
        seed(pendingAt, idleSince = putDown)
        val pending = DayRepository(context).getDayStatus(pendingAt)
        assertEquals(DayRepository.DayState.REST_PENDING, pending.state)
        assertEquals(putDown + 5 * minute, pending.endTimeMillis)
        assertEquals(-1L, DayRepository(context).getCurrentDayEndTime(pendingAt))

        val confirmedAt = at(4, 10)
        seed(confirmedAt, idleSince = putDown, confirmedEnd = putDown + 5 * minute)
        val repository = DayRepository(context)
        val confirmed = repository.getDayStatus(confirmedAt)
        assertEquals(DayRepository.DayState.REST_CONFIRMED, confirmed.state)
        assertEquals(putDown + 5 * minute, confirmed.endTimeMillis)
        assertEquals(putDown + 5 * minute, repository.getCurrentDayEndTime(confirmedAt))
        assertEquals(100, confirmed.progress)
        assertEquals(putDown + 5 * minute - dayStart, confirmed.elapsedMillis)
        assertEquals(0L, confirmed.comparisonMillis)
    }

    @Test
    fun firstPhoneBasedDayHasNoInventedFixedTimeReference() {
        val now = at(4, 8)
        seed(now, activeSince = dayStart, history = emptyList())
        prefs.dayEnd = 22 * 60
        val repository = DayRepository(context)
        val status = repository.getDayStatus(now)
        assertEquals(DayRepository.DayState.ACTIVE, status.state)
        assertEquals(-1L, status.referenceEndTimeMillis)
        assertNull(status.comparisonMillis)
        assertEquals(0, status.progress)
        assertEquals(-1L, repository.getCurrentDayWindow(now).dayEndMillis)
        assertEquals(-1L, repository.getCurrentDayEndTime(now))
    }

    @Test
    fun unavailableUsageIsVisibleAndKeepsCompletedHistory() {
        val now = at(4, 10)
        seed(now, idleSince = at(4, 6), confirmedEnd = at(4, 6, 5))
        allowUsage(false)
        val repository = DayRepository(context)
        val status = repository.getDayStatus(now)
        assertEquals(DayRepository.DayState.PHONE_REST_UNAVAILABLE, status.state)
        assertEquals(0, status.progress)
        assertEquals(-1L, repository.getCurrentDayEndTime(now))
        assertEquals(listOf(previousRest), repository.getPhoneRestSnapshot(now).history)
    }

    @Test
    fun fixedStartAndEndSettingsRemainUsableAfterLeavingPhoneMode() {
        val now = at(3, 20)
        prefs.apply {
            phoneRestEnabled = false
            ignoreBefore = 6 * 60
            dayEnd = 22 * 60
            manualStartTime = dayStart
            manualStartMinutes = 14 * 60
            manualStartDayId = "2024-06-03"
            lastResetDate = "2024-06-03"
        }
        val repository = DayRepository(context)
        repository.setPhoneRestEnabled(true, now)
        assertNull(repository.resolveManualStartTimeForCurrentDay(14 * 60, now))
        repository.setPhoneRestEnabled(false, now)

        assertEquals(dayStart, repository.getEffectiveStartTime(now))
        assertEquals(at(3, 22), repository.getCurrentDayEndTime(now))
        assertEquals(DayRepository.DayState.ACTIVE, repository.getDayStatus(now).state)
        assertEquals(6 * 60, prefs.ignoreBefore)
        assertEquals(22 * 60, prefs.dayEnd)
        assertEquals(14 * 60, prefs.manualStartMinutes)
    }

    @Test
    fun clearingHistoryDoesNotImmediatelyReconstructTheDeletedRecords() {
        val now = at(4, 14, 6)
        val usage = shadowOf(context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
        fun event(time: Long, type: Int) {
            usage.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage("android").setClass("Device").setTimeStamp(time).setEventType(type).build())
        }
        event(dayStart, UsageEvents.Event.KEYGUARD_HIDDEN)
        event(at(4, 6), UsageEvents.Event.KEYGUARD_SHOWN)
        event(at(4, 6, 1), UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        event(at(4, 14), UsageEvents.Event.SCREEN_INTERACTIVE)
        event(at(4, 14), UsageEvents.Event.KEYGUARD_HIDDEN)
        val latest = PhoneRestAnalyzer.RestPeriod(dayStart, at(4, 6, 5), at(4, 14))
        store.write(PhoneRestStore.Record(PhoneRestAnalyzer.Snapshot(now, at(4, 14), history = listOf(previousRest, latest))))

        val repository = DayRepository(context)
        repository.clearPhoneRestHistory(now)
        val afterClear = DayRepository(context).getPhoneRestSnapshot(now + 1L)
        assertTrue(afterClear.history.isEmpty())
        assertEquals(-1L, afterClear.currentDayStartMillis)
        assertEquals(-1L, afterClear.confirmedEndMillis)
        assertTrue(prefs.phoneRestEnabled)
    }

    @Test
    fun resettingDetectionRetainsTheDayAndHistoryButDropsAnUnfinishedRest() {
        val now = at(4, 10)
        seed(now, idleSince = at(4, 6), confirmedEnd = at(4, 6, 5))
        val repository = DayRepository(context)
        repository.resetPhoneRestTracking(now)
        val snapshot = repository.getPhoneRestSnapshot(now)
        assertEquals(dayStart, snapshot.currentDayStartMillis)
        assertEquals(listOf(previousRest), snapshot.history)
        assertEquals(-1L, snapshot.idleSinceMillis)
        assertEquals(-1L, snapshot.confirmedEndMillis)
        assertEquals(DayRepository.DayState.ACTIVE, repository.getDayStatus(now).state)
    }

    private fun seed(now: Long, activeSince: Long = -1L, idleSince: Long = -1L, confirmedEnd: Long = -1L,
        history: List<PhoneRestAnalyzer.RestPeriod> = listOf(previousRest)) {
        store.write(PhoneRestStore.Record(PhoneRestAnalyzer.Snapshot(now, dayStart, activeSince, idleSince, confirmedEnd, history)))
    }

    private fun allowUsage(allow: Boolean) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName,
            if (allow) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        clear()
        set(2024, Calendar.JUNE, day, hour, minute)
    }.timeInMillis
}
