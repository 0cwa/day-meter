package com.example.dayprogress.data

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.reminder.CheckpointActionReceiver
import com.example.dayprogress.reminder.CheckpointSnooze
import com.example.dayprogress.reminder.ReminderNotifier
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneRestCheckpointTest {
    private lateinit var context: Context
    private lateinit var prefs: AppPreferences
    private lateinit var store: CheckpointStore
    private lateinit var engine: CheckpointEngine
    private val originalTimeZone = TimeZone.getDefault()
    private var now = 0L
    private val day = "2024-06-03"

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        prefs = AppPreferences(context).apply {
            ignoreBefore = 6 * 60
            dayEnd = 20 * 60
            manualStartTime = at(8)
            manualStartDayId = day
            lastResetDate = day
        }
        store = CheckpointStore(context)
        engine = CheckpointEngine(context)
        now = at(12)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun percentageCheckpointsPauseAndResumeWithTheDayMode() {
        val percentage = Checkpoint(label = "Halfway", triggerType = CheckpointTriggerType.PERCENT, triggerValue = 50)
        assertTrue(store.saveCheckpoint(percentage))
        assertEquals(at(14), engine.getNextSchedule(now)?.triggerAtMillis)
        assertEquals(1, engine.getWidgetMarkers(now).size)

        prefs.phoneRestEnabled = true
        assertFalse(engine.isAvailable(percentage))
        assertNull(engine.getNextSchedule(now))
        assertNull(engine.getNextVisibleOccurrence(now))
        assertNull(engine.getCurrentOccurrence(percentage, now))
        assertTrue(engine.getWidgetMarkers(now).isEmpty())
        assertTrue(engine.getDueCheckpoints(now).due.isEmpty())
        assertTrue(store.getCheckpoints().single().enabled)

        prefs.phoneRestEnabled = false
        assertTrue(engine.isAvailable(percentage))
        assertEquals(at(14), engine.getNextSchedule(now)?.triggerAtMillis)
        assertNotNull(engine.getCurrentOccurrence(percentage, now))
        assertEquals(1, engine.getWidgetMarkers(now).size)
    }

    @Test
    fun persistedPercentageSchedulesAndSnoozesCannotDeliverWhilePaused() {
        val states = listOf(CheckpointStatus.SCHEDULED, CheckpointStatus.SNOOZED).map { status ->
            val checkpoint = Checkpoint(triggerType = CheckpointTriggerType.PERCENT, triggerValue = 50)
            assertTrue(store.saveCheckpoint(checkpoint))
            CheckpointState(checkpoint.id, day, status, snoozeAtMillis = now - 60_000L).also(store::putState)
        }
        prefs.phoneRestEnabled = true
        assertNull(engine.getNextSchedule(now))
        assertNull(engine.getNextVisibleOccurrence(now))
        assertTrue(engine.getDueCheckpoints(now).due.isEmpty())
        assertTrue(engine.getDueCheckpoints(now).missed.isEmpty())
        assertEquals(states.toSet(), store.getStates().values.toSet())

        prefs.phoneRestEnabled = false
        assertEquals(states.map { it.checkpointId }.toSet(), engine.getDueCheckpoints(now).due.map { it.checkpoint.id }.toSet())
    }

    @Test
    fun clockCheckpointsKeepTheirCalendarSchedule() {
        val clock = Checkpoint(label = "Clock", triggerValue = 13 * 60)
        val percentage = Checkpoint(triggerType = CheckpointTriggerType.PERCENT, triggerValue = 50)
        assertTrue(store.saveCheckpoint(clock))
        assertTrue(store.saveCheckpoint(percentage))
        prefs.phoneRestEnabled = true

        assertTrue(engine.isAvailable(clock))
        val schedule = engine.getNextSchedule(now)!!
        assertEquals(at(13), schedule.triggerAtMillis)
        assertEquals(listOf(clock.id), schedule.occurrences.map { it.checkpoint.id })
        assertEquals(day, schedule.occurrences.single().occurrenceDayId)
        assertEquals(clock.id, engine.getNextVisibleOccurrence(now)?.checkpoint?.id)
        assertEquals(clock.id, engine.getDueCheckpoints(at(13)).due.single().checkpoint.id)
    }

    @Test
    fun clockChainWaitsForAnUnavailablePercentagePredecessor() {
        val first = Checkpoint(triggerType = CheckpointTriggerType.PERCENT, triggerValue = 25)
        val second = Checkpoint(triggerValue = 12 * 60, predecessorId = first.id)
        assertTrue(store.saveCheckpoint(first))
        assertTrue(store.saveCheckpoint(second))
        store.putState(CheckpointState(first.id, day, CheckpointStatus.DONE, completedAtMillis = now - 60_000L))
        assertFalse(engine.isBlocked(second, day, now))

        prefs.phoneRestEnabled = true
        assertTrue(engine.isBlocked(second, day, now))
        assertTrue(engine.getDueCheckpoints(now).due.isEmpty())
        assertTrue(engine.getNextSchedule(now)?.occurrences.orEmpty().isEmpty())

        prefs.phoneRestEnabled = false
        assertFalse(engine.isBlocked(second, day, now))
        assertEquals(second.id, engine.getDueCheckpoints(now).due.single().checkpoint.id)
    }

    @Test
    fun oldPercentageNotificationActionsAndSnoozePickerAreRejected() {
        val checkpoint = Checkpoint(triggerType = CheckpointTriggerType.PERCENT, triggerValue = 50)
        assertTrue(store.saveCheckpoint(checkpoint))
        val deliveredAt = System.currentTimeMillis()
        val occurrenceDay = DayIdFormatter.format(deliveredAt)
        val state = CheckpointState(checkpoint.id, occurrenceDay, CheckpointStatus.NOTIFIED, notifiedAtMillis = deliveredAt)
        store.putState(state)
        val occurrence = CheckpointOccurrence(checkpoint, occurrenceDay, deliveredAt)
        assertTrue(ReminderNotifier.show(context, occurrence, deliveredAt))
        prefs.phoneRestEnabled = true

        assertNull(CheckpointSnooze.currentCheckpoint(context, checkpoint.id, occurrenceDay, deliveredAt))
        val process = CheckpointActionReceiver::class.java.getDeclaredMethod(
            "processAction", Context::class.java, Intent::class.java, String::class.java, String::class.java,
            java.lang.Long.TYPE
        ).apply { isAccessible = true }
        for (action in listOf(CheckpointActionReceiver.ACTION_DONE, CheckpointActionReceiver.ACTION_SKIP, CheckpointActionReceiver.ACTION_SNOOZE)) {
            assertEquals(false, process.invoke(CheckpointActionReceiver(), context, Intent(action), checkpoint.id, occurrenceDay, deliveredAt))
            assertEquals(state, store.getState(checkpoint.id, occurrenceDay))
        }
        assertFalse(ReminderNotifier.show(context, occurrence, deliveredAt))

        ReminderNotifier.refreshActiveReminders(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertNull(store.getState(checkpoint.id, occurrenceDay))
    }

    @Test
    fun percentageSnoozeCarriedOverFromYesterdayIsNotRepublished() {
        val checkpoint = Checkpoint(triggerType = CheckpointTriggerType.PERCENT, triggerValue = 50)
        assertTrue(store.saveCheckpoint(checkpoint))
        val deliveredAt = System.currentTimeMillis()
        val yesterday = Calendar.getInstance().apply { timeInMillis = deliveredAt; add(Calendar.DATE, -1) }.timeInMillis
        val occurrenceDay = DayIdFormatter.format(yesterday)
        val state = CheckpointState(checkpoint.id, occurrenceDay, CheckpointStatus.NOTIFIED, notifiedAtMillis = deliveredAt)
        store.putState(state)
        assertTrue(ReminderNotifier.show(context, CheckpointOccurrence(checkpoint, occurrenceDay, deliveredAt), deliveredAt))

        prefs.phoneRestEnabled = true
        ReminderNotifier.refreshActiveReminders(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(CheckpointStatus.MISSED, store.getState(checkpoint.id, occurrenceDay)?.status)
    }

    private fun at(hour: Int): Long = Calendar.getInstance().apply {
        clear()
        set(2024, Calendar.JUNE, 3, hour, 0)
    }.timeInMillis
}
