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
class PhoneRestTrackerTest {
    private lateinit var context: Context
    private lateinit var prefs: AppPreferences
    private lateinit var store: PhoneRestStore
    private lateinit var tracker: PhoneRestTracker
    private lateinit var usage: ShadowUsageStatsManager
    private val originalTimeZone = TimeZone.getDefault()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val dayStart get() = at(3, 14)
    private val history get() = listOf(PhoneRestAnalyzer.RestPeriod(at(2, 14), at(3, 6, 5), dayStart))

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context).apply {
            phoneRestEnabled = true
            minimumAwakeMinutes = 8 * 60
            minimumRestMinutes = 3 * 60
            usageThreshold = 5
        }
        store = PhoneRestStore(context)
        tracker = PhoneRestTracker(context)
        usage = shadowOf(context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
        allowUsage(true)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun stateSurvivesRecreationWithItsClockAndAvailabilityMetadata() {
        val record = PhoneRestStore.Record(
            PhoneRestAnalyzer.Snapshot(at(4, 10), dayStart, idleSinceMillis = at(4, 6),
                confirmedEndMillis = at(4, 6, 5), history = history),
            savedWallMillis = at(4, 10), savedElapsedMillis = 123_000L, available = false,
            screenInteractive = false, keyguardHidden = false
        )
        store.write(record)
        assertEquals(record, PhoneRestStore(context).read())
    }

    @Test
    fun persistedHistoryRemainsBounded() {
        val periods = (0..40).map { index ->
            val start = dayStart + index * 24 * hour
            PhoneRestAnalyzer.RestPeriod(start, start + 16 * hour, start + 24 * hour)
        }
        val latestWake = periods.last().wakeMillis
        store.write(PhoneRestStore.Record(PhoneRestAnalyzer.Snapshot(
            cursorMillis = latestWake, currentDayStartMillis = latestWake, history = periods
        )))
        assertEquals(periods.takeLast(PhoneRestAnalyzer.MAX_HISTORY_SIZE), store.read()!!.snapshot.history)
    }

    @Test
    fun corruptStateContainerSelfHealsWithoutChangingModeOrSettings() {
        val raw = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        for (corrupt in listOf("not-json", "{\"version\":999}")) {
            raw.edit().putString(PhoneRestStore.KEY, corrupt).commit()
            assertNull(store.read())
            assertFalse(raw.contains(PhoneRestStore.KEY))
        }
        raw.edit().putInt(PhoneRestStore.KEY, 42).commit()
        assertNull(store.read())
        assertFalse(raw.contains(PhoneRestStore.KEY))
        assertTrue(prefs.phoneRestEnabled)
        assertEquals(3 * 60, prefs.minimumRestMinutes)
    }

    @Test
    fun usageAccessLossKeepsHistoryButCannotBridgeTheUnobservedRest() {
        val cursor = at(4, 6, 10)
        store.write(PhoneRestStore.Record(PhoneRestAnalyzer.Snapshot(
            cursor, dayStart, idleSinceMillis = at(4, 6), history = history
        )))
        allowUsage(false)
        val unavailable = tracker.refresh(cursor + hour, elapsedMillis = hour)
        assertFalse(unavailable.available)
        assertEquals(history, unavailable.snapshot.history)
        assertEquals(dayStart, unavailable.snapshot.currentDayStartMillis)
        assertEquals(-1L, unavailable.snapshot.idleSinceMillis)
        assertEquals(-1L, unavailable.snapshot.confirmedEndMillis)

        allowUsage(true)
        val resumed = PhoneRestTracker(context).refresh(cursor + 5 * hour, elapsedMillis = 5 * hour)
        assertTrue(resumed.available)
        assertEquals(history, resumed.snapshot.history)
        assertEquals(-1L, resumed.snapshot.confirmedEndMillis)
    }

    @Test
    fun AndroidEventsAreReplayedAcrossTrackerRecreationWithoutLosingTheRestBoundary() {
        addEvent(dayStart, UsageEvents.Event.KEYGUARD_HIDDEN)
        val firstRead = dayStart + 6 * minute
        assertEquals(dayStart, tracker.refresh(firstRead, elapsedMillis = 0L).snapshot.currentDayStartMillis)

        val putDown = at(4, 6)
        addEvent(putDown, UsageEvents.Event.KEYGUARD_SHOWN)
        addEvent(putDown + 1_000L, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        val confirmedAt = putDown + 3 * hour
        val resting = PhoneRestTracker(context).refresh(confirmedAt, elapsedMillis = confirmedAt - firstRead).snapshot
        assertEquals(putDown + 5 * minute, resting.confirmedEndMillis)
        assertTrue(resting.history.isEmpty())

        val wake = at(4, 14)
        addEvent(wake, UsageEvents.Event.SCREEN_INTERACTIVE)
        addEvent(wake, UsageEvents.Event.KEYGUARD_HIDDEN)
        val awakeAt = wake + 6 * minute
        val awake = PhoneRestTracker(context).refresh(awakeAt, elapsedMillis = awakeAt - firstRead).snapshot
        assertEquals(wake, awake.currentDayStartMillis)
        assertEquals(-1L, awake.confirmedEndMillis)
        assertEquals(listOf(PhoneRestAnalyzer.RestPeriod(dayStart, putDown + 5 * minute, wake)), awake.history)
        assertEquals(awake, PhoneRestStore(context).read()?.snapshot)
    }

    @Test
    fun togglingTheModePreservesHistoryAndDropsUnfinishedEvidence() {
        val now = at(4, 10)
        store.write(PhoneRestStore.Record(PhoneRestAnalyzer.Snapshot(
            now, dayStart, idleSinceMillis = at(4, 6), confirmedEndMillis = at(4, 6, 5), history = history
        )))
        tracker.setEnabled(false, now)
        assertFalse(prefs.phoneRestEnabled)
        assertEquals(history, store.read()!!.snapshot.history)
        tracker.setEnabled(true, now + hour)
        val restarted = store.read()!!.snapshot
        assertTrue(prefs.phoneRestEnabled)
        assertEquals(now + hour, restarted.cursorMillis)
        assertEquals(history, restarted.history)
        assertEquals(-1L, restarted.currentDayStartMillis)
        assertEquals(-1L, restarted.idleSinceMillis)
        assertEquals(-1L, restarted.confirmedEndMillis)
    }

    @Test
    fun dismissingALowPhoneDayRestRejoinsItsOriginalDayAndDoesNotReplayIt() {
        val newWake = at(4, 14)
        val latest = PhoneRestAnalyzer.RestPeriod(dayStart, at(4, 6, 5), newWake)
        val now = newWake + 10 * minute
        store.write(PhoneRestStore.Record(PhoneRestAnalyzer.Snapshot(
            now, newWake, activeSinceMillis = newWake, history = history + latest
        )))

        assertTrue(tracker.discardLatestRest(now))
        val corrected = store.read()!!.snapshot
        assertEquals(dayStart, corrected.currentDayStartMillis)
        assertEquals(history, corrected.history)
        assertEquals(now, corrected.cursorMillis)
        assertEquals(-1L, corrected.activeSinceMillis)
        assertEquals(-1L, corrected.idleSinceMillis)
        assertEquals(-1L, corrected.confirmedEndMillis)
    }

    @Test
    fun aWallClockJumpCannotTurnAnUnfinishedIdleIntoSleep() {
        val before = at(4, 6, 10)
        store.write(PhoneRestStore.Record(PhoneRestAnalyzer.Snapshot(
            before, dayStart, idleSinceMillis = at(4, 6), history = history
        ), savedWallMillis = before, savedElapsedMillis = 10 * minute))

        val after = tracker.refresh(before + 8 * hour, elapsedMillis = 11 * minute).snapshot
        assertEquals(history, after.history)
        assertEquals(-1L, after.currentDayStartMillis)
        assertEquals(-1L, after.idleSinceMillis)
        assertEquals(-1L, after.confirmedEndMillis)
    }

    @Test
    fun backwardClockChangeDuringDeniedAccessDoesNotEraseHistoryOnTheNextRead() {
        store.write(PhoneRestStore.Record(PhoneRestAnalyzer.Snapshot(
            at(4, 6), dayStart, idleSinceMillis = at(4, 6), history = history
        )))
        allowUsage(false)
        val earlier = dayStart - hour
        val result = tracker.refresh(earlier, elapsedMillis = 1_000L)
        assertFalse(result.available)
        assertEquals(history, result.snapshot.history)
        assertEquals(history, PhoneRestStore(context).read()?.snapshot?.history)
        assertEquals(-1L, result.snapshot.confirmedEndMillis)
    }

    @Test
    fun discardingHistoryAfterAClockRollbackCannotRestoreAFutureDayStart() {
        val latest = PhoneRestAnalyzer.RestPeriod(dayStart, at(4, 6, 5), at(4, 14))
        val rolledBackNow = dayStart - hour
        store.write(PhoneRestStore.Record(PhoneRestAnalyzer.Snapshot(
            cursorMillis = rolledBackNow, history = history + latest
        )))
        assertTrue(tracker.discardLatestRest(rolledBackNow))
        val corrected = store.read()!!.snapshot
        assertEquals(history, corrected.history)
        assertEquals(-1L, corrected.currentDayStartMillis)
    }

    private fun allowUsage(allow: Boolean) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName,
            if (allow) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }

    private fun addEvent(time: Long, type: Int) {
        usage.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
            .setPackage("android").setClass("Device").setTimeStamp(time).setEventType(type).build())
    }

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        clear()
        set(2024, Calendar.JUNE, day, hour, minute)
    }.timeInMillis
}
