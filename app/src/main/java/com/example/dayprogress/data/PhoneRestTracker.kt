package com.example.dayprogress.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Local, bounded storage. No package names or raw usage-event history are retained. */
internal class PhoneRestStore(context: Context) {
    private val preferences = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)

    data class Record(
        val snapshot: PhoneRestAnalyzer.Snapshot,
        val savedWallMillis: Long = -1L,
        val savedElapsedMillis: Long = -1L,
        val available: Boolean = true,
        val screenInteractive: Boolean? = null,
        val keyguardHidden: Boolean? = null,
        val initialReplayPending: Boolean = false
    )

    fun read(): Record? = try {
        val text = preferences.all[KEY] as? String
        if (text == null) {
            if (preferences.contains(KEY)) clear()
            null
        } else {
            val json = JSONObject(text)
            require(json.getInt("version") == 1)
            val history = json.getJSONArray("history")
            val periods = (0 until history.length()).map { index ->
                val row = history.getJSONObject(index)
                PhoneRestAnalyzer.RestPeriod(row.getLong("start"), row.getLong("end"), row.getLong("wake")).also {
                    require(it.dayStartMillis >= 0 && it.endMillis > it.dayStartMillis && it.wakeMillis >= it.endMillis)
                }
            }.takeLast(PhoneRestAnalyzer.MAX_HISTORY_SIZE)
            val snapshot = PhoneRestAnalyzer.Snapshot(
                cursorMillis = json.getLong("cursor"),
                currentDayStartMillis = json.getLong("start"),
                activeSinceMillis = json.getLong("active"),
                idleSinceMillis = json.getLong("idle"),
                confirmedEndMillis = json.getLong("end"),
                history = periods
            )
            require(snapshot.cursorMillis >= 0L)
            require(listOf(snapshot.currentDayStartMillis, snapshot.activeSinceMillis, snapshot.idleSinceMillis,
                snapshot.confirmedEndMillis).all { it in -1L..snapshot.cursorMillis })
            require(snapshot.activeSinceMillis < 0L || snapshot.idleSinceMillis < 0L)
            require(snapshot.confirmedEndMillis < 0L ||
                snapshot.currentDayStartMillis >= 0L && snapshot.confirmedEndMillis > snapshot.currentDayStartMillis)
            Record(snapshot, json.optLong("wall", -1L), json.optLong("elapsed", -1L), json.optBoolean("available", true),
                if (json.isNull("interactive")) null else json.getBoolean("interactive"),
                if (json.isNull("unlocked")) null else json.getBoolean("unlocked"),
                json.optBoolean("initialReplayPending", false))
        }
    } catch (e: Exception) {
        Log.w("PhoneRestStore", "Discarding unreadable phone-rest state", e)
        clear()
        null
    }

    fun write(record: Record) {
        val snapshot = record.snapshot
        val history = JSONArray()
        snapshot.history.takeLast(PhoneRestAnalyzer.MAX_HISTORY_SIZE).forEach {
            history.put(JSONObject().put("start", it.dayStartMillis).put("end", it.endMillis).put("wake", it.wakeMillis))
        }
        val json = JSONObject()
            .put("version", 1)
            .put("cursor", snapshot.cursorMillis)
            .put("start", snapshot.currentDayStartMillis)
            .put("active", snapshot.activeSinceMillis)
            .put("idle", snapshot.idleSinceMillis)
            .put("end", snapshot.confirmedEndMillis)
            .put("history", history)
            .put("wall", record.savedWallMillis)
            .put("elapsed", record.savedElapsedMillis)
            .put("available", record.available)
            .put("interactive", record.screenInteractive ?: JSONObject.NULL)
            .put("unlocked", record.keyguardHidden ?: JSONObject.NULL)
            .put("initialReplayPending", record.initialReplayPending)
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    fun clear() = preferences.edit().remove(KEY).apply()

    companion object {
        internal const val KEY = "phone_rest_state_v1"
    }
}

/** Reconciles passive refreshes and missed refreshes against Android's observed event timeline. */
internal class PhoneRestTracker(private val context: Context) {
    private val prefs = AppPreferences(context)
    private val store = PhoneRestStore(context)

    data class Result(val snapshot: PhoneRestAnalyzer.Snapshot, val available: Boolean)

    fun config(): PhoneRestAnalyzer.Config {
        // An incomplete or restored waking-hours setting must not invent a protected interval.
        val protected = prefs.protectWakeHours && prefs.protectedWakeStartMinutes != prefs.protectedWakeEndMinutes
        return PhoneRestAnalyzer.Config(
            minimumRestMillis = prefs.minimumRestMinutes * 60_000L,
            minimumAwakeMillis = prefs.minimumAwakeMinutes * 60_000L,
            wakeConfirmationMillis = prefs.usageThreshold * 60_000L,
            protectedWakeStartMinutes = prefs.protectedWakeStartMinutes.takeIf { protected },
            protectedWakeEndMinutes = prefs.protectedWakeEndMinutes.takeIf { protected }
        )
    }

    fun refresh(nowMillis: Long, elapsedMillis: Long = SystemClock.elapsedRealtime()): Result = synchronized(lock) {
        val stored = store.read()
        var snapshot = stored?.snapshot ?: PhoneRestAnalyzer.Snapshot(
            cursorMillis = (nowMillis - MAX_REPLAY_MILLIS).coerceAtLeast(0L)
        )
        var screenInteractive = stored?.screenInteractive
        var keyguardHidden = stored?.keyguardHidden
        val initialReplayPending = stored?.initialReplayPending ?: true
        if (!prefs.phoneRestEnabled) return@synchronized Result(snapshot, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !UsageDetector.hasUsageStatsPermission(context)) {
            snapshot = withoutEvidence(snapshot, nowMillis)
            store.write(PhoneRestStore.Record(snapshot, nowMillis, elapsedMillis, false,
                initialReplayPending = initialReplayPending))
            return@synchronized Result(snapshot, false)
        }

        if (initialReplayPending) {
            // The usual first-time flow grants Usage Access after enabling this option.
            // Keep its initial lookback available until that first authorized query.
            snapshot = PhoneRestAnalyzer.Snapshot(cursorMillis = (nowMillis - MAX_REPLAY_MILLIS).coerceAtLeast(0L),
                history = snapshot.history)
            screenInteractive = null
            keyguardHidden = null
        }

        // Wall-clock edits and reboots must not turn an unobserved interval into sleep.
        // A timezone/DST change does not change epoch time and needs no reset here.
        val clockDiscontinuity = !initialReplayPending && stored != null && stored.savedWallMillis >= 0L && stored.savedElapsedMillis >= 0L &&
            abs((nowMillis - stored.savedWallMillis) - (elapsedMillis - stored.savedElapsedMillis)) > CLOCK_TOLERANCE_MILLIS
        if (nowMillis < snapshot.cursorMillis && stored != null &&
            stored.savedWallMillis >= 0L && stored.savedElapsedMillis >= 0L && !clockDiscontinuity) {
            // UI and worker callers sample time before taking this lock. An older queued
            // refresh must not rewind newer evidence or masquerade as a wall-clock edit.
            return@synchronized Result(snapshot, stored.available)
        }
        if (nowMillis < snapshot.cursorMillis || clockDiscontinuity) {
            snapshot = PhoneRestAnalyzer.Snapshot(cursorMillis = nowMillis, history = snapshot.history)
            screenInteractive = null
            keyguardHidden = null
        } else if (nowMillis - snapshot.cursorMillis > MAX_REPLAY_MILLIS) {
            // Android only retains usage events for a few days. Start from unknown evidence
            // inside a bounded lookback rather than bridging the missing prefix as rest.
            snapshot = PhoneRestAnalyzer.Snapshot(
                cursorMillis = nowMillis - MAX_REPLAY_MILLIS,
                history = snapshot.history
            )
            screenInteractive = null
            keyguardHidden = null
        }

        if (nowMillis <= snapshot.cursorMillis) {
            store.write(PhoneRestStore.Record(snapshot, nowMillis, elapsedMillis, true, screenInteractive, keyguardHidden))
            return@synchronized Result(snapshot, true)
        }
        val batch = UsageDetector(context).readPhoneRestEvents(snapshot.cursorMillis, nowMillis, screenInteractive, keyguardHidden)
        if (batch == null) {
            snapshot = withoutEvidence(snapshot, nowMillis)
            store.write(PhoneRestStore.Record(snapshot, nowMillis, elapsedMillis, false,
                initialReplayPending = initialReplayPending))
            return@synchronized Result(snapshot, false)
        }
        val updated = PhoneRestAnalyzer.advance(snapshot, batch.events, nowMillis, config())
        store.write(PhoneRestStore.Record(updated, nowMillis, elapsedMillis, true, batch.screenInteractive, batch.keyguardHidden))
        Result(updated, true)
    }

    fun setEnabled(enabled: Boolean, nowMillis: Long) = synchronized(lock) {
        if (prefs.phoneRestEnabled == enabled) return@synchronized
        val previous = store.read()?.snapshot
        prefs.phoneRestEnabled = enabled
        // Re-enabling must not resurrect an unfinished rest from before the mode was disabled.
        // The first opt-in can reconstruct recent observed activity; subsequent opt-ins start now.
        val cursor = if (enabled && previous == null) (nowMillis - MAX_REPLAY_MILLIS).coerceAtLeast(0L) else nowMillis
        val snapshot = PhoneRestAnalyzer.Snapshot(cursorMillis = cursor, history = previous?.history.orEmpty())
        store.write(PhoneRestStore.Record(snapshot, initialReplayPending = enabled && previous == null))
    }

    fun resetEvidence(nowMillis: Long, resetDay: Boolean = false) = synchronized(lock) {
        val stored = store.read()
        val snapshot = stored?.snapshot ?: PhoneRestAnalyzer.Snapshot()
        val updated = withoutEvidence(snapshot, nowMillis).let {
            if (resetDay) it.copy(currentDayStartMillis = -1L) else it
        }
        store.write(PhoneRestStore.Record(updated, nowMillis, SystemClock.elapsedRealtime(),
            initialReplayPending = stored?.initialReplayPending == true))
    }

    fun discardLatestRest(nowMillis: Long): Boolean = synchronized(lock) {
        val snapshot = store.read()?.snapshot ?: return@synchronized false
        val updated = when {
            snapshot.confirmedEndMillis >= 0L || PhoneRestAnalyzer.candidateEndMillis(snapshot, nowMillis, config()) != null ->
                withoutEvidence(snapshot, nowMillis)
            snapshot.history.isNotEmpty() -> withoutEvidence(snapshot, nowMillis).copy(
                currentDayStartMillis = snapshot.history.last().dayStartMillis.takeIf { it <= nowMillis } ?: -1L,
                history = snapshot.history.dropLast(1)
            )
            else -> return@synchronized false
        }
        store.write(PhoneRestStore.Record(updated, nowMillis, SystemClock.elapsedRealtime()))
        true
    }

    fun clear(nowMillis: Long) = synchronized(lock) {
        // Keep a cursor so clearing history cannot immediately replay and restore deleted entries.
        store.write(PhoneRestStore.Record(PhoneRestAnalyzer.Snapshot(cursorMillis = nowMillis),
            nowMillis, SystemClock.elapsedRealtime()))
    }

    private fun withoutEvidence(snapshot: PhoneRestAnalyzer.Snapshot, nowMillis: Long) = snapshot.copy(
        cursorMillis = nowMillis,
        currentDayStartMillis = snapshot.currentDayStartMillis.takeIf { it <= nowMillis } ?: -1L,
        activeSinceMillis = -1L,
        idleSinceMillis = -1L,
        confirmedEndMillis = -1L
    )

    companion object {
        private val lock = Any()
        internal const val MAX_REPLAY_MILLIS = 48 * 60 * 60_000L
        private const val CLOCK_TOLERANCE_MILLIS = 2 * 60_000L
    }
}
