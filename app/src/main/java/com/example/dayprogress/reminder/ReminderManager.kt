package com.example.dayprogress.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.Checkpoint
import com.example.dayprogress.data.CheckpointEngine
import com.example.dayprogress.data.CheckpointNotificationMode
import com.example.dayprogress.data.CheckpointOccurrence
import com.example.dayprogress.data.CheckpointState
import com.example.dayprogress.data.CheckpointStatus
import com.example.dayprogress.data.CheckpointStore
import com.example.dayprogress.data.DayIdFormatter
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.ui.SettingsActivity
import com.example.dayprogress.widget.DayProgressWidgetProvider
import com.example.dayprogress.worker.runAsync
import java.util.Date

object ReminderTransitions {
    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock, block)
}

object ReminderScheduler {
    private const val REQUEST_CODE = 2201

    fun reschedule(context: Context, forceRecompute: Boolean = false) = ReminderTransitions.run {
        ReminderNotifier.createChannels(context)
        cancelLocked(context)
        val store = CheckpointStore(context)
        if (forceRecompute) store.clearScheduledStates()
        if (!ReminderNotifier.notificationsAllowed(context)) return@run

        val schedule = CheckpointEngine(context).getNextSchedule() ?: return@run
        val states = store.getStates()
        store.putStates(schedule.occurrences.mapNotNull { occurrence ->
            if (states[occurrence.key] != null) null else CheckpointState(
                checkpointId = occurrence.checkpoint.id,
                occurrenceDayId = occurrence.occurrenceDayId,
                status = CheckpointStatus.SCHEDULED,
                snoozeAtMillis = occurrence.dueAtMillis
            )
        })
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = alarmPendingIntent(context)
        val triggerAt = schedule.triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 500L)

        if (schedule.isDetectionPoll) {
            alarmManager.set(AlarmManager.RTC, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context) = ReminderTransitions.run { cancelLocked(context) }

    private fun cancelLocked(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmPendingIntent(context))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_REMINDER_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMINDER_ALARM) return
        val appContext = context.applicationContext
        runAsync("ReminderAlarmReceiver", critical = true) { ReminderCoordinator.processDue(appContext) }
    }

    companion object {
        const val ACTION_REMINDER_ALARM = "com.example.dayprogress.ACTION_REMINDER_ALARM"
    }
}

class CheckpointActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val checkpointId = intent.getStringExtra(EXTRA_CHECKPOINT_ID) ?: return
        val occurrenceDayId = intent.getStringExtra(EXTRA_OCCURRENCE_DAY_ID) ?: return
        val deliveryToken = intent.getLongExtra(EXTRA_DELIVERY_TOKEN, -1L).takeIf { it >= 0L } ?: return
        val appContext = context.applicationContext
        runAsync("CheckpointActionReceiver", critical = true) {
            val changed = ReminderTransitions.run {
                processAction(appContext, intent, checkpointId, occurrenceDayId, deliveryToken)
            }
            if (changed) {
                ReminderScheduler.reschedule(appContext)
                DayProgressWidgetProvider.refreshWidgetsInBackground(appContext)
            }
        }
    }

    private fun processAction(
        context: Context,
        intent: Intent,
        checkpointId: String,
        occurrenceDayId: String,
        deliveryToken: Long
    ): Boolean {
        val store = CheckpointStore(context)
        val checkpoint = store.getCheckpoints().find { it.id == checkpointId } ?: return false
        if (!checkpoint.enabled) return false
        if (checkpoint.predecessorId != null && occurrenceDayId != com.example.dayprogress.data.DayIdFormatter.format(System.currentTimeMillis())) return false
        if (intent.action in setOf(ACTION_DONE, ACTION_SNOOZE) && CheckpointEngine(context).isBlocked(checkpoint, occurrenceDayId)) return false
        val currentState = store.getState(checkpointId, occurrenceDayId) ?: return false
        if (currentState.status != CheckpointStatus.NOTIFIED || currentState.notifiedAtMillis != deliveryToken) {
            return false
        }

        // Honor the duration printed on a delivered action even if settings changed later.
        val snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, -1)
            .takeIf { it in AppPreferences.SNOOZE_MINUTE_CHOICES }
            ?: AppPreferences(context).snoozeMinutes
        val snoozeMillis = snoozeMinutes * 60_000L
        val newState = when (intent.action) {
            ACTION_DONE -> currentState.copy(
                status = CheckpointStatus.DONE,
                completedAtMillis = System.currentTimeMillis(),
                snoozeAtMillis = -1L,
                snoozeAtElapsedRealtime = -1L
            )
            ACTION_SKIP -> currentState.copy(
                status = CheckpointStatus.SKIPPED,
                snoozeAtMillis = -1L,
                snoozeAtElapsedRealtime = -1L
            )
            ACTION_SNOOZE -> currentState.copy(
                status = CheckpointStatus.SNOOZED,
                snoozeAtMillis = System.currentTimeMillis() + snoozeMillis,
                snoozeAtElapsedRealtime = SystemClock.elapsedRealtime() + snoozeMillis
            )
            else -> return false
        }

        store.putState(newState)
        ReminderNotifier.cancel(context, checkpointId, occurrenceDayId)
        return true
    }

    companion object {
        const val ACTION_DONE = "com.example.dayprogress.ACTION_CHECKPOINT_DONE"
        const val ACTION_SNOOZE = "com.example.dayprogress.ACTION_CHECKPOINT_SNOOZE"
        const val ACTION_SKIP = "com.example.dayprogress.ACTION_CHECKPOINT_SKIP"
        const val EXTRA_CHECKPOINT_ID = "checkpoint_id"
        const val EXTRA_OCCURRENCE_DAY_ID = "occurrence_day_id"
        const val EXTRA_DELIVERY_TOKEN = "delivery_token"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
    }
}

private object ReminderCoordinator {
    fun processDue(context: Context) {
        ReminderTransitions.run { processDueLocked(context) }
        ReminderScheduler.reschedule(context)
        DayProgressWidgetProvider.refreshWidgetsInBackground(context)
    }

    private fun processDueLocked(context: Context) {
        val repository = DayRepository(context)
        repository.detectDayStartIfNeeded()
        if (!ReminderNotifier.notificationsAllowed(context)) {
            ReminderScheduler.cancel(context)
            return
        }

        val store = CheckpointStore(context)
        val result = CheckpointEngine(context).getDueCheckpoints()
        val enabledCheckpoints = store.getCheckpoints().filter(Checkpoint::enabled).associateBy(Checkpoint::id)
        store.putStates(result.missed.map { occurrence ->
            CheckpointState(
                checkpointId = occurrence.checkpoint.id,
                occurrenceDayId = occurrence.occurrenceDayId,
                status = CheckpointStatus.MISSED
            )
        })
        result.due.forEach { occurrence ->
            val checkpoint = enabledCheckpoints[occurrence.checkpoint.id] ?: return@forEach
            val current = store.getState(checkpoint.id, occurrence.occurrenceDayId)
            if (current != null && current.status !in setOf(CheckpointStatus.SCHEDULED, CheckpointStatus.SNOOZED)) {
                return@forEach
            }

            val deliveryToken = System.currentTimeMillis()
            val claimed = CheckpointState(
                checkpointId = checkpoint.id,
                occurrenceDayId = occurrence.occurrenceDayId,
                status = CheckpointStatus.NOTIFIED,
                notifiedAtMillis = deliveryToken
            )
            store.putState(claimed)
            val delivered = runCatching {
                ReminderNotifier.show(context, occurrence.copy(checkpoint = checkpoint), deliveryToken)
            }.getOrDefault(false)
            if (!delivered && store.getState(checkpoint.id, occurrence.occurrenceDayId) == claimed) {
                store.putState(claimed.copy(status = CheckpointStatus.MISSED, notifiedAtMillis = -1L))
            }
        }
    }
}

object ReminderNotifier {
    private const val GENTLE_CHANNEL = "day_checkpoints_gentle_v1"
    private const val SILENT_CHANNEL = "day_checkpoints_silent_v1"

    private const val VIBRATE_CHANNEL = "day_checkpoints_vibrate_v1"
    private const val PROMINENT_CHANNEL = "day_checkpoints_prominent_v1"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val gentle = NotificationChannel(
            GENTLE_CHANNEL,
            context.getString(R.string.notification_channel_gentle),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_gentle_description)
            enableVibration(false)
        }
        val silent = NotificationChannel(
            SILENT_CHANNEL,
            context.getString(R.string.notification_channel_silent),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_silent_description)
            enableVibration(false)
            setSound(null, null)
        }
        val vibrate = NotificationChannel(
            VIBRATE_CHANNEL,
            context.getString(R.string.notif_channel_vibrate),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_vibrate_description)
            enableVibration(true)
            setSound(null, null)
        }
        val prominent = NotificationChannel(
            PROMINENT_CHANNEL,
            context.getString(R.string.notif_channel_prominent),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_prominent_description)
            enableVibration(true)
        }
        // Android channel settings belong to the user. Only create missing channels;
        // rescheduling must never reapply defaults over system customization.
        manager.createNotificationChannels(listOf(gentle, silent, vibrate, prominent).filter {
            manager.getNotificationChannel(it.id) == null
        })
    }

    fun notificationsAllowed(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notificationsReady(context: Context): Boolean {
        if (!notificationsAllowed(context)) return false
        createChannels(context)
        return CheckpointStore(context).getCheckpoints()
            .filter { it.enabled }
            .all { channelAllowed(context, it.notificationMode) }
    }

    fun channelAllowed(context: Context, mode: CheckpointNotificationMode): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(channelId(mode))?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Apply reminder preferences immediately without sounding already delivered reminders again. */
    fun refreshActiveReminders(context: Context) = ReminderTransitions.run {
        val store = CheckpointStore(context)
        val engine = CheckpointEngine(context)
        val now = System.currentTimeMillis()
        val enabled = store.getCheckpoints().filter(Checkpoint::enabled).associateBy(Checkpoint::id)
        val allowed = notificationsAllowed(context)
        store.getStates().values.filter { it.status == CheckpointStatus.NOTIFIED }.forEach { state ->
            val checkpoint = enabled[state.checkpointId]
            val occurrence = checkpoint?.let { engine.getCurrentOccurrence(it, now) }
                ?.takeIf { it.occurrenceDayId == state.occurrenceDayId && it.dueAtMillis <= now }
                ?.takeUnless { engine.isBlocked(it.checkpoint, it.occurrenceDayId, now) }
            if (!allowed || occurrence == null) {
                cancel(context, state.checkpointId, state.occurrenceDayId)
                if (state.occurrenceDayId != DayIdFormatter.format(now)) {
                    store.putState(state.copy(status = CheckpointStatus.MISSED, notifiedAtMillis = -1L))
                } else {
                    // A prerequisite or system permission may become available later today.
                    // Removing the delivery claim lets the scheduler evaluate it again.
                    store.removeState(state.checkpointId, state.occurrenceDayId)
                }
                return@forEach
            }
            // Rotate action identity before replacing the notification. Captured old actions
            // must not change a reminder whose displayed snooze duration has just changed.
            val token = maxOf(now, state.notifiedAtMillis + 1L)
            val refreshed = state.copy(notifiedAtMillis = token)
            store.putState(refreshed)
            if (!runCatching { show(context, occurrence, token) }.getOrDefault(false)) {
                cancel(context, state.checkpointId, state.occurrenceDayId)
                store.removeState(state.checkpointId, state.occurrenceDayId)
            }
        }
    }

    fun show(context: Context, occurrence: CheckpointOccurrence, deliveryToken: Long): Boolean {
        if (!notificationsAllowed(context)) return false
        createChannels(context)
        if (!channelAllowed(context, occurrence.checkpoint.notificationMode)) return false
        val checkpoint = occurrence.checkpoint
        val dueTime = DateFormat.getTimeFormat(context).format(Date(occurrence.dueAtMillis))
        val content = context.getString(R.string.notification_checkpoint_content, dueTime)
        val channel = channelId(checkpoint.notificationMode)

        val preferences = AppPreferences(context)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_day_meter)
            .setColor(Color.rgb(64, 224, 208))
            .setContentTitle(checkpoint.displayLabel())
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup("day_checkpoints")
            .setVisibility(if (preferences.showLockScreenDetails) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setOngoing(preferences.reminderPersistent)
            .setAutoCancel(!preferences.reminderPersistent)
            .setContentIntent(openAppIntent(context, occurrence))
            .addAction(0, context.getString(R.string.checkpoint_action_done), actionIntent(context, occurrence, CheckpointActionReceiver.ACTION_DONE, deliveryToken))
            .addAction(0, context.getString(R.string.notif_action_snooze_minutes, preferences.snoozeMinutes), actionIntent(context, occurrence, CheckpointActionReceiver.ACTION_SNOOZE, deliveryToken, preferences.snoozeMinutes))
            .addAction(0, context.getString(R.string.checkpoint_action_skip), actionIntent(context, occurrence, CheckpointActionReceiver.ACTION_SKIP, deliveryToken))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            when (checkpoint.notificationMode) {
                CheckpointNotificationMode.GENTLE -> builder
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setDefaults(NotificationCompat.DEFAULT_SOUND)
                CheckpointNotificationMode.SILENT -> builder
                    .setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true)
                CheckpointNotificationMode.VIBRATE -> builder
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                CheckpointNotificationMode.PROMINENT -> builder
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            }
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationTag(occurrence), notificationId(occurrence), builder.build())
            return true
        } catch (_: SecurityException) {
            // Permission can be revoked between the explicit check and notify().
            return false
        }
    }

    fun cancel(context: Context, checkpointId: String, occurrenceDayId: String) {
        NotificationManagerCompat.from(context).cancel(
            notificationTag(checkpointId, occurrenceDayId),
            notificationId(checkpointId, occurrenceDayId)
        )
    }

    private fun openAppIntent(context: Context, occurrence: CheckpointOccurrence): PendingIntent {
        val intent = Intent(context, SettingsActivity::class.java).apply {
            data = "daymeter://checkpoint/${occurrence.checkpoint.id}/${occurrence.occurrenceDayId}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId(occurrence),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(
        context: Context,
        occurrence: CheckpointOccurrence,
        action: String,
        deliveryToken: Long,
        snoozeMinutes: Int? = null
    ): PendingIntent {
        val intent = Intent(context, CheckpointActionReceiver::class.java).apply {
            this.action = action
            data = "daymeter://checkpoint/${occurrence.checkpoint.id}/${occurrence.occurrenceDayId}/${action.substringAfterLast('.')}/$deliveryToken".toUri()
            putExtra(CheckpointActionReceiver.EXTRA_CHECKPOINT_ID, occurrence.checkpoint.id)
            putExtra(CheckpointActionReceiver.EXTRA_OCCURRENCE_DAY_ID, occurrence.occurrenceDayId)
            putExtra(CheckpointActionReceiver.EXTRA_DELIVERY_TOKEN, deliveryToken)
            snoozeMinutes?.let { putExtra(CheckpointActionReceiver.EXTRA_SNOOZE_MINUTES, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            (occurrence.key + action + deliveryToken).hashCode() and Int.MAX_VALUE,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun channelId(mode: CheckpointNotificationMode): String = when (mode) {
        CheckpointNotificationMode.GENTLE -> GENTLE_CHANNEL
        CheckpointNotificationMode.SILENT -> SILENT_CHANNEL
        CheckpointNotificationMode.VIBRATE -> VIBRATE_CHANNEL
        CheckpointNotificationMode.PROMINENT -> PROMINENT_CHANNEL
    }

    fun channelSettingsIntent(context: Context, mode: CheckpointNotificationMode): Intent {
        createChannels(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId(mode))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${context.packageName}".toUri())
        }
    }

    private fun notificationTag(occurrence: CheckpointOccurrence) = notificationTag(occurrence.checkpoint.id, occurrence.occurrenceDayId)
    private fun notificationTag(checkpointId: String, occurrenceDayId: String) = "checkpoint:$checkpointId:$occurrenceDayId"
    private fun notificationId(occurrence: CheckpointOccurrence) = notificationId(occurrence.checkpoint.id, occurrence.occurrenceDayId)
    private fun notificationId(checkpointId: String, occurrenceDayId: String) =
        (checkpointId + occurrenceDayId).hashCode() and Int.MAX_VALUE
}
