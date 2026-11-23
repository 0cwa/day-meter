package com.example.dayprogress.data

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class UsageDetector(private val context: Context) {
    private val excludedPackages: Set<String> by lazy { buildExcludedPackages() }

    internal data class PhoneRestEventBatch(
        val events: List<PhoneRestAnalyzer.Event>,
        val screenInteractive: Boolean?,
        val keyguardHidden: Boolean?
    )

    /**
     * Device-level rest evidence, available since Android 9. A missing foreground
     * app is not evidence of rest: only an observed lock/screen-off starts it.
     * Launcher use counts here, unlike the fixed-mode day-start threshold.
     * Null means unavailable data, rather than an empty (but successful) query.
     */
    @SuppressLint("InlinedApi", "NewApi")
    internal fun readPhoneRestEvents(
        fromMillis: Long,
        untilMillis: Long,
        initialScreenInteractive: Boolean? = null,
        initialKeyguardHidden: Boolean? = null
    ): PhoneRestEventBatch? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasUsageStatsPermission(context)) return null
        return try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val events = manager.queryEvents(fromMillis, untilMillis) ?: return null
            val result = mutableListOf<PhoneRestAnalyzer.Event>()
            var screenInteractive = initialScreenInteractive
            var keyguardHidden = initialKeyguardHidden
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.timeStamp < fromMillis || event.timeStamp >= untilMillis) continue
                val type = when (event.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        screenInteractive = true
                        // A notification lighting up the lock screen is not a wake.
                        PhoneRestAnalyzer.EventType.USE.takeIf { keyguardHidden == true }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        screenInteractive = false
                        PhoneRestAnalyzer.EventType.IDLE
                    }
                    UsageEvents.Event.KEYGUARD_SHOWN -> {
                        keyguardHidden = false
                        PhoneRestAnalyzer.EventType.IDLE
                    }
                    UsageEvents.Event.KEYGUARD_HIDDEN -> {
                        keyguardHidden = true
                        screenInteractive = true
                        PhoneRestAnalyzer.EventType.USE
                    }
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (event.packageName.isNullOrBlank() || event.packageName in SYSTEM_PACKAGES) continue
                        // Alarms and calls may resume an activity without the phone being
                        // unlocked. Persist these gates across event-query boundaries.
                        PhoneRestAnalyzer.EventType.USE.takeIf { screenInteractive != false && keyguardHidden != false }
                    }
                    UsageEvents.Event.DEVICE_SHUTDOWN,
                    UsageEvents.Event.DEVICE_STARTUP -> {
                        screenInteractive = null
                        keyguardHidden = null
                        PhoneRestAnalyzer.EventType.UNKNOWN
                    }
                    else -> continue
                }
                if (type != null) result += PhoneRestAnalyzer.Event(event.timeStamp, type)
            }
            PhoneRestEventBatch(result, screenInteractive, keyguardHidden)
        } catch (e: Exception) {
            Log.w(TAG, "Phone-rest usage history is unavailable", e)
            null
        }
    }

    fun findThresholdCrossingTime(
        logicalDayStartMillis: Long,
        ignoreBeforeMillis: Long,
        thresholdMinutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        if (nowMillis <= ignoreBeforeMillis) {
            return null
        }
        if (thresholdMinutes <= 0) {
            return ignoreBeforeMillis
        }

        val result = analyzeUsage(logicalDayStartMillis, ignoreBeforeMillis, thresholdMinutes, nowMillis)
        Log.d(TAG, "Usage threshold analysis completed")
        return result.thresholdCrossedAtMillis
    }

    @SuppressLint("InlinedApi")
    private fun analyzeUsage(
        logicalDayStartMillis: Long,
        ignoreBeforeMillis: Long,
        thresholdMinutes: Int,
        nowMillis: Long
    ): UsageEventAnalyzer.Result {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return UsageEventAnalyzer.Result(null, 0L)
            val queryStartMillis = minOf(logicalDayStartMillis, ignoreBeforeMillis)
            val usageEvents = usageStatsManager.queryEvents(queryStartMillis, nowMillis)
            val events = sequence {
                val androidEvent = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(androidEvent)
                    adaptEvent(androidEvent)?.let { yield(it) }
                }
            }.asIterable()

            UsageEventAnalyzer.analyze(
                queryStartMillis = queryStartMillis,
                ignoreBeforeMillis = ignoreBeforeMillis,
                nowMillis = nowMillis,
                thresholdMillis = thresholdMinutes * MINUTE_MILLIS,
                excludedPackages = excludedPackages,
                events = events
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error querying usage events", e)
            UsageEventAnalyzer.Result(null, 0L)
        }
    }

    @SuppressLint("InlinedApi", "NewApi")
    private fun adaptEvent(event: UsageEvents.Event): UsageEventAnalyzer.Event? {
        val type = when (event.eventType) {
            // MOVE_TO_FOREGROUND/BACKGROUND use the same values on older Android versions.
            UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventAnalyzer.EventType.ACTIVITY_RESUMED
            UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventAnalyzer.EventType.ACTIVITY_PAUSED
            UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventAnalyzer.EventType.ACTIVITY_STOPPED
            UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventAnalyzer.EventType.SCREEN_INTERACTIVE
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventAnalyzer.EventType.SCREEN_NON_INTERACTIVE
            UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEventAnalyzer.EventType.KEYGUARD_HIDDEN
            UsageEvents.Event.KEYGUARD_SHOWN -> UsageEventAnalyzer.EventType.KEYGUARD_SHOWN
            else -> return null
        }

        val activityId = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            type in ACTIVITY_EVENT_TYPES
        ) {
            "class:${event.className.orEmpty()}"
        } else {
            null
        }

        return UsageEventAnalyzer.Event(
            timestampMillis = event.timeStamp,
            type = type,
            packageName = event.packageName,
            activityId = activityId
        )
    }

    private fun buildExcludedPackages(): Set<String> {
        val packages = linkedSetOf("android", "com.android.systemui")

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val homePackages = context.packageManager
                .queryIntentActivities(homeIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }

            packages.addAll(homePackages)
            Log.d(TAG, "Ignoring ${homePackages.size} launcher/home packages during start detection")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to resolve launcher/home packages", e)
        }

        return packages
    }

    companion object {
        private const val TAG = "UsageDetector"
        private const val MINUTE_MILLIS = 60_000L
        private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")

        @Suppress("DEPRECATION")
        fun hasUsageStatsPermission(context: Context): Boolean {
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        context.packageName
                    )
                } else {
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        context.packageName
                    )
                }
                mode == AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
        }

        private val ACTIVITY_EVENT_TYPES = setOf(
            UsageEventAnalyzer.EventType.ACTIVITY_RESUMED,
            UsageEventAnalyzer.EventType.ACTIVITY_PAUSED,
            UsageEventAnalyzer.EventType.ACTIVITY_STOPPED
        )
    }
}
