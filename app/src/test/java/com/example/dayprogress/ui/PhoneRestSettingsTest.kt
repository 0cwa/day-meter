package com.example.dayprogress.ui

import android.app.AppOpsManager
import android.content.Context
import android.os.SystemClock
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.data.PhoneRestAnalyzer
import com.example.dayprogress.data.PhoneRestStore
import com.example.dayprogress.worker.runInBackground
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneRestSettingsTest {
    private lateinit var context: Context
    private lateinit var prefs: AppPreferences
    private lateinit var controller: ActivityController<SettingsActivity>
    private lateinit var fragment: SettingsFragment

    @Before
    fun setUp() {
        // Keep the twelve-hour seeded history valid even with an epoch-based test clock.
        ShadowSystemClock.advanceBy(Duration.ofDays(2))
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
            context.packageName, AppOpsManager.MODE_IGNORED)
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun row(key: String) = fragment.findPreference<Preference>(key)!!
    private fun toggle(key: String, enabled: Boolean) =
        fragment.findPreference<SwitchPreferenceCompat>(key)!!.callChangeListener(enabled)

    @Test
    fun modeIsOptionalAndFixedControlsRemainAvailableByDefault() {
        assertFalse(prefs.phoneRestEnabled)
        assertFalse(prefs.protectWakeHours)
        assertTrue(row(AppPreferences.KEY_DAY_END).isVisible)
        assertTrue(row(AppPreferences.KEY_IGNORE_BEFORE).isVisible)
        assertTrue(row("start_day_now").isVisible)
        assertFalse(row(AppPreferences.KEY_MINIMUM_REST_MINUTES).isVisible)
        assertFalse(row("phone_rest_history").isVisible)
    }

    @Test
    fun enablingWithoutPermissionExplainsAccessWithoutOpeningSettingsAutomatically() {
        assertTrue(toggle(AppPreferences.KEY_PHONE_REST_ENABLED, true))
        assertTrue(prefs.phoneRestEnabled)
        assertFalse(row(AppPreferences.KEY_DAY_END).isVisible)
        assertFalse(row(AppPreferences.KEY_IGNORE_BEFORE).isVisible)
        assertFalse(row("start_day_now").isVisible)
        assertFalse(row(AppPreferences.KEY_IS_MANUAL_LOCKED).isVisible)
        assertTrue(row(AppPreferences.KEY_MINIMUM_REST_MINUTES).isVisible)
        assertEquals(context.getString(R.string.phone_rest_usage_access_needed), row("start_status").summary)
        assertEquals(context.getString(R.string.phone_rest_usage_access_needed), row("usage_access_status").summary)
        assertNull(shadowOf(controller.get()).nextStartedActivity)

        assertTrue(toggle(AppPreferences.KEY_PHONE_REST_ENABLED, false))
        assertFalse(prefs.phoneRestEnabled)
        assertTrue(row(AppPreferences.KEY_DAY_END).isVisible)
        assertTrue(row("start_day_now").isVisible)
        assertFalse(row("phone_rest_history").isVisible)
        assertEquals(context.getString(R.string.label_usage_threshold), row(AppPreferences.KEY_USAGE_THRESHOLD).title)
    }

    @Test
    fun optionalAwakeHoursRejectAnEmptyRangeAndAcceptAnOvernightRange() {
        toggle(AppPreferences.KEY_PHONE_REST_ENABLED, true)
        assertFalse(toggle(AppPreferences.KEY_PROTECT_WAKE_HOURS, true))
        assertFalse(prefs.protectWakeHours)
        assertEquals(context.getString(R.string.phone_rest_protected_invalid), ShadowToast.getTextOfLatestToast())
        prefs.protectedWakeStartMinutes = 20 * 60
        prefs.protectedWakeEndMinutes = 8 * 60
        assertTrue(toggle(AppPreferences.KEY_PROTECT_WAKE_HOURS, true))
        assertTrue(prefs.protectWakeHours)
        assertEquals(20 * 60, prefs.protectedWakeStartMinutes)
        assertEquals(8 * 60, prefs.protectedWakeEndMinutes)
    }

    @Test
    fun firstPendingRestCanBeIgnoredBeforeAnyHistoryExists() {
        toggle(AppPreferences.KEY_PHONE_REST_ENABLED, true)
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
            context.packageName, AppOpsManager.MODE_ALLOWED)
        controller.pause().stop().destroy()
        // Finish the earlier activity's queued refresh before replacing persisted state.
        // The replacement below is test setup, outside the tracker's normal mutation lock.
        val refreshFinished = CountDownLatch(1)
        runInBackground("PhoneRestSettingsTest") { refreshFinished.countDown() }
        assertTrue(refreshFinished.await(5, TimeUnit.SECONDS))
        val now = System.currentTimeMillis()
        assertTrue("The seeded day start must be after the epoch", now > 12 * 60 * 60_000L)
        PhoneRestStore(context).write(PhoneRestStore.Record(
            PhoneRestAnalyzer.Snapshot(cursorMillis = now,
                currentDayStartMillis = now - 12 * 60 * 60_000L,
                idleSinceMillis = now - 10 * 60_000L),
            now, SystemClock.elapsedRealtime()
        ))
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
        fragment.refreshPermissionState()
        val discard = row("phone_rest_discard_latest")
        assertTrue(discard.isEnabled)
        assertTrue(discard.summary.toString().contains("possible end"))
        assertTrue(discard.onPreferenceClickListener!!.onPreferenceClick(discard))
        val snapshot = DayRepository(context).getPhoneRestSnapshot()
        assertEquals(-1L, snapshot.idleSinceMillis)
        assertTrue(snapshot.history.isEmpty())
        assertFalse(discard.isEnabled)
    }

    @Test
    @Config(sdk = [27])
    fun olderAndroidKeepsFixedModeAndExplainsUnsupportedEvents() {
        assertFalse(row(AppPreferences.KEY_PHONE_REST_ENABLED).isEnabled)
        assertFalse(toggle(AppPreferences.KEY_PHONE_REST_ENABLED, true))
        assertFalse(prefs.phoneRestEnabled)
        assertEquals(context.getString(R.string.phone_rest_requires_android), row(AppPreferences.KEY_PHONE_REST_ENABLED).summary)
        assertTrue(row(AppPreferences.KEY_DAY_END).isVisible)
    }

    @Test
    fun invalidAdaptiveSettingsRestoreBoundedDefaults() {
        val storage = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        storage.edit()
            .putString(AppPreferences.KEY_MINIMUM_REST_MINUTES, "-10")
            .putInt(AppPreferences.KEY_MINIMUM_AWAKE_MINUTES, 5000)
            .putInt(AppPreferences.KEY_PROTECTED_WAKE_START, 1440)
            .putBoolean(AppPreferences.KEY_PROTECTED_WAKE_END, true)
            .commit()
        assertEquals(180, prefs.minimumRestMinutes)
        assertEquals(480, prefs.minimumAwakeMinutes)
        assertEquals(0, prefs.protectedWakeStartMinutes)
        assertEquals(0, prefs.protectedWakeEndMinutes)
        assertFalse(prefs.protectWakeHours)
    }
}
