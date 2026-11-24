package com.example.dayprogress.worker

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.widget.DayProgressWidgetProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneRestSchedulingTest {
    private lateinit var context: Context
    private lateinit var prefs: AppPreferences
    private lateinit var alarms: ShadowAlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context).apply { phoneRestEnabled = true }
        alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        allowUsage(true)
        assertFalse(AlarmScheduler.hasWidgets(context))
    }

    @Test
    fun phoneModeSchedulesPassiveFiveMinuteRefreshesWithoutWidgets() {
        val before = SystemClock.elapsedRealtime()
        AlarmScheduler.scheduleWidgetUpdates(context)
        val scheduled = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME, scheduled.getType())
        assertFalse(scheduled.isAllowWhileIdle)
        assertTrue(scheduled.triggerAtMs in (before + 5 * 60_000L)..(SystemClock.elapsedRealtime() + 5 * 60_000L))
        assertEquals(AlarmScheduler.ACTION_WIDGET_REFRESH, shadowOf(requireNotNull(scheduled.operation)).savedIntent.action)
    }

    @Test
    fun removingTheLastWidgetKeepsPhoneRestTrackingScheduled() {
        AlarmScheduler.scheduleWidgetUpdates(context)
        DayProgressWidgetProvider().onDisabled(context)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarms.scheduledAlarms.single().getType())
    }

    @Test
    fun leavingPhoneModeWithoutWidgetsCancelsItsRefresh() {
        AlarmScheduler.scheduleWidgetUpdates(context)
        assertEquals(1, alarms.scheduledAlarms.size)
        prefs.phoneRestEnabled = false
        AlarmScheduler.scheduleWidgetUpdates(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun deniedUsageAccessWithoutWidgetsCancelsUnusablePolling() {
        AlarmScheduler.scheduleWidgetUpdates(context)
        allowUsage(false)
        AlarmScheduler.scheduleWidgetUpdates(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    @Config(sdk = [23])
    fun unsupportedAndroidVersionDoesNotPollWithoutWidgets() {
        AlarmScheduler.scheduleWidgetUpdates(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    private fun allowUsage(allow: Boolean) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName,
            if (allow) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }
}
