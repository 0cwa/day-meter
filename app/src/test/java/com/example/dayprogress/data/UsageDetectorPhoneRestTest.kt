package com.example.dayprogress.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.data.PhoneRestAnalyzer.Event
import com.example.dayprogress.data.PhoneRestAnalyzer.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowUsageStatsManager.EventBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class UsageDetectorPhoneRestTest {
    private lateinit var context: Context
    private lateinit var manager: UsageStatsManager
    private lateinit var detector: UsageDetector

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        detector = UsageDetector(context)
        setPermission(AppOpsManager.MODE_ALLOWED)
    }

    @Test
    fun lockedActivityCannotSplitConfirmedRestAcrossQueryChunks() {
        add(0L, UsageEvents.Event.KEYGUARD_HIDDEN)
        add(10 * HOUR, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        add(10 * HOUR + 100L, UsageEvents.Event.KEYGUARD_SHOWN)
        val first = detector.readPhoneRestEvents(0L, 14 * HOUR)!!
        val asleep = PhoneRestAnalyzer.advance(PhoneRestAnalyzer.Snapshot(), first.events, 14 * HOUR)
        assertEquals(10 * HOUR + 5 * MINUTE, asleep.confirmedEndMillis)
        assertEquals(false, first.screenInteractive)
        assertEquals(false, first.keyguardHidden)

        add(15 * HOUR, UsageEvents.Event.ACTIVITY_RESUMED, "example.alarm")
        add(15 * HOUR + 100L, UsageEvents.Event.SCREEN_INTERACTIVE)
        add(15 * HOUR + 200L, UsageEvents.Event.ACTIVITY_RESUMED, "example.alarm")
        val locked = detector.readPhoneRestEvents(14 * HOUR, 16 * HOUR, first.screenInteractive, first.keyguardHidden)!!
        val stillAsleep = PhoneRestAnalyzer.advance(asleep, locked.events, 16 * HOUR)
        assertTrue(locked.events.isEmpty())
        assertEquals(true, locked.screenInteractive)
        assertEquals(false, locked.keyguardHidden)
        assertEquals(0L, stillAsleep.currentDayStartMillis)
        assertEquals(10 * HOUR + 5 * MINUTE, stillAsleep.confirmedEndMillis)
        assertTrue(stillAsleep.history.isEmpty())

        add(16 * HOUR, UsageEvents.Event.KEYGUARD_HIDDEN)
        val awake = detector.readPhoneRestEvents(16 * HOUR, 16 * HOUR + 5 * MINUTE, locked.screenInteractive, locked.keyguardHidden)!!
        val nextDay = PhoneRestAnalyzer.advance(stillAsleep, awake.events, 16 * HOUR + 5 * MINUTE)
        assertEquals(16 * HOUR, nextDay.currentDayStartMillis)
        assertEquals(16 * HOUR, nextDay.history.single().wakeMillis)
    }

    @Test
    fun launcherUseCountsAfterUnlockButNotBehindKnownLock() {
        add(100L, UsageEvents.Event.KEYGUARD_SHOWN)
        val first = detector.readPhoneRestEvents(0L, 200L)!!
        assertEquals(listOf(Event(100L, EventType.IDLE)), first.events)

        add(250L, UsageEvents.Event.ACTIVITY_RESUMED, "example.launcher")
        add(300L, UsageEvents.Event.KEYGUARD_HIDDEN)
        add(350L, UsageEvents.Event.ACTIVITY_RESUMED, "example.launcher")
        add(400L, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        val next = detector.readPhoneRestEvents(200L, 500L, first.screenInteractive, first.keyguardHidden)!!
        assertEquals(
            listOf(Event(300L, EventType.USE), Event(350L, EventType.USE), Event(400L, EventType.IDLE)),
            next.events
        )
        assertEquals(false, next.screenInteractive)
        assertEquals(true, next.keyguardHidden)
    }

    @Test
    fun screenOffGateIsIndependentOfUnlockedKeyguard() {
        add(100L, UsageEvents.Event.ACTIVITY_RESUMED, "example.reader")
        val off = detector.readPhoneRestEvents(0L, 200L, initialScreenInteractive = false, initialKeyguardHidden = true)!!
        assertTrue(off.events.isEmpty())
        assertEquals(false, off.screenInteractive)
        assertEquals(true, off.keyguardHidden)

        add(250L, UsageEvents.Event.SCREEN_INTERACTIVE)
        val on = detector.readPhoneRestEvents(200L, 300L, off.screenInteractive, off.keyguardHidden)!!
        assertEquals(listOf(Event(250L, EventType.USE)), on.events)
        assertEquals(true, on.screenInteractive)
    }

    @Test
    fun screenLightingUpWithoutAnUnlockIsNotEvidenceOfUse() {
        add(100L, UsageEvents.Event.SCREEN_INTERACTIVE)
        val unknownLock = detector.readPhoneRestEvents(0L, 200L)!!
        assertTrue(unknownLock.events.isEmpty())
        assertEquals(true, unknownLock.screenInteractive)
        assertNull(unknownLock.keyguardHidden)

        val knownLock = detector.readPhoneRestEvents(0L, 200L, initialKeyguardHidden = false)!!
        assertTrue(knownLock.events.isEmpty())
        assertEquals(false, knownLock.keyguardHidden)
    }

    @Test
    fun unknownDeviceStateDoesNotExcludeRealForegroundUseOrCountSystemUi() {
        add(100L, UsageEvents.Event.ACTIVITY_RESUMED, "android")
        add(200L, UsageEvents.Event.ACTIVITY_RESUMED, "com.android.systemui")
        add(300L, UsageEvents.Event.ACTIVITY_RESUMED, "example.launcher")
        val batch = detector.readPhoneRestEvents(0L, 400L)!!
        assertEquals(listOf(Event(300L, EventType.USE)), batch.events)
    }

    @Test
    fun rebootEmitsUnknownAndClearsBothPersistedGates() {
        add(100L, UsageEvents.Event.DEVICE_SHUTDOWN)
        add(200L, UsageEvents.Event.DEVICE_STARTUP)
        val batch = detector.readPhoneRestEvents(0L, 300L, initialScreenInteractive = false, initialKeyguardHidden = false)!!
        assertEquals(listOf(Event(100L, EventType.UNKNOWN), Event(200L, EventType.UNKNOWN)), batch.events)
        assertNull(batch.screenInteractive)
        assertNull(batch.keyguardHidden)
    }

    @Test
    fun successfulEmptyQueryPreservesGatesAndDoesNotFabricateEvents() {
        val batch = detector.readPhoneRestEvents(0L, HOUR, initialScreenInteractive = false, initialKeyguardHidden = false)!!
        assertTrue(batch.events.isEmpty())
        assertEquals(false, batch.screenInteractive)
        assertEquals(false, batch.keyguardHidden)
    }

    @Test
    fun eventAtExclusiveQueryEndChangesGateOnlyInNextBatch() {
        add(200L, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        val first = detector.readPhoneRestEvents(0L, 200L, initialScreenInteractive = true, initialKeyguardHidden = true)!!
        assertTrue(first.events.isEmpty())
        assertEquals(true, first.screenInteractive)
        val second = detector.readPhoneRestEvents(200L, 300L, first.screenInteractive, first.keyguardHidden)!!
        assertEquals(listOf(Event(200L, EventType.IDLE)), second.events)
        assertEquals(false, second.screenInteractive)
    }

    @Test
    fun deniedUsageAccessReturnsUnavailableInsteadOfEmptyEvidence() {
        setPermission(AppOpsManager.MODE_IGNORED)
        assertFalse(UsageDetector.hasUsageStatsPermission(context))
        assertNull(detector.readPhoneRestEvents(0L, HOUR))
    }

    @Test
    @Config(sdk = [27])
    fun androidBeforeDeviceEventsReturnsUnavailable() {
        assertNull(detector.readPhoneRestEvents(0L, HOUR))
    }

    private fun setPermission(mode: Int) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName, mode)
    }

    private fun add(time: Long, type: Int, packageName: String = "android") {
        shadowOf(manager).addEvent(EventBuilder.buildEvent().setTimeStamp(time).setEventType(type).setPackage(packageName).build())
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
