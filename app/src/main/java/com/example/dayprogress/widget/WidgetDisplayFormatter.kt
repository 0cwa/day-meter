package com.example.dayprogress.widget

import android.content.Context
import com.example.dayprogress.R
import com.example.dayprogress.data.CheckpointOccurrence
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.data.WidgetCheckpointMarker
import android.text.format.DateFormat
import java.util.Date

internal data class WidgetDisplayText(
    val primary: String,
    val nextCheckpoint: String?,
    val contentDescription: String
)

internal object WidgetDisplayFormatter {
    // Large combined mode needs room for two text lines as well as its 14dp rail.
    fun canShowCheckpoint(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
        widthDp >= 220 && heightDp >= maxOf(64f, 25f * fontScale + 22f)

    fun format(
        context: Context,
        status: DayRepository.DayStatus,
        nextCheckpoint: CheckpointOccurrence?,
        markers: List<WidgetCheckpointMarker>,
        expanded: Boolean
    ): WidgetDisplayText {
        val primary = when (status.state) {
            DayRepository.DayState.ACTIVE -> if (status.phoneRestMode) {
                formatPhoneRestActive(context, status, compact = true)
            } else if (expanded) {
                context.getString(R.string.widget_progress_time_left, status.progress, formatDuration(context, status.timeRemainingMillis))
            } else {
                context.getString(R.string.progress_percent, status.progress)
            }
            DayRepository.DayState.STARTS_IN_FUTURE -> context.getString(
                R.string.widget_starts_in,
                formatDuration(context, status.timeRemainingMillis)
            )
            DayRepository.DayState.BEFORE_WINDOW -> context.getString(R.string.widget_before_window)
            DayRepository.DayState.USAGE_ACCESS_NEEDED -> context.getString(R.string.widget_usage_access_needed)
            DayRepository.DayState.WAITING_FOR_START -> context.getString(
                if (status.phoneRestMode) R.string.phone_rest_widget_waiting else R.string.widget_waiting_for_start
            )
            DayRepository.DayState.COMPLETE -> context.getString(R.string.widget_day_complete)
            DayRepository.DayState.ENDED_WITHOUT_START -> context.getString(R.string.widget_no_start)
            DayRepository.DayState.REST_PENDING -> context.getString(R.string.phone_rest_widget_pending)
            DayRepository.DayState.REST_CONFIRMED -> context.getString(
                R.string.phone_rest_widget_confirmed,
                DateFormat.getTimeFormat(context).format(Date(status.endTimeMillis))
            )
            DayRepository.DayState.PHONE_REST_UNAVAILABLE -> context.getString(R.string.phone_rest_widget_unavailable)
        }
        val next = nextCheckpoint?.let {
            val time = DateFormat.getTimeFormat(context).format(Date(it.dueAtMillis))
            context.getString(R.string.widget_next_checkpoint, it.checkpoint.displayLabel(), time)
        }
        val description = buildString {
            append(if (status.phoneRestMode && status.state == DayRepository.DayState.ACTIVE)
                formatPhoneRestActive(context, status, compact = false) else primary)
            if (status.phoneRestMode && status.referenceEndTimeMillis >= 0L) {
                append(". ").append(context.getString(R.string.phone_rest_widget_reference_description))
            }
            if (next != null) append(". ").append(next)
            if (markers.isNotEmpty()) {
                append(". ").append(
                    context.resources.getQuantityString(R.plurals.widget_checkpoint_markers, markers.size, markers.size)
                )
                val labels = markers.map(WidgetCheckpointMarker::label).distinct().take(3).joinToString(", ")
                if (labels.isNotBlank()) append(": ").append(labels)
            }
        }
        return WidgetDisplayText(primary, next, description)
    }

    private fun formatPhoneRestActive(context: Context, status: DayRepository.DayStatus, compact: Boolean): String {
        fun duration(millis: Long): String = if (compact) {
            val minutes = ((millis + 59_999L) / 60_000L).coerceAtLeast(0L)
            when {
                minutes >= 60 && minutes % 60 > 0 -> context.getString(R.string.phone_rest_duration_hours_minutes, minutes / 60, minutes % 60)
                minutes >= 60 -> context.getString(R.string.phone_rest_duration_hours, minutes / 60)
                else -> context.getString(R.string.phone_rest_duration_minutes, minutes)
            }
        } else formatDuration(context, millis)
        val comparison = status.comparisonMillis
        return when {
            comparison == null -> context.getString(R.string.phone_rest_widget_elapsed, duration(status.elapsedMillis))
            comparison < 0L -> context.getString(R.string.phone_rest_widget_until, duration(-comparison))
            comparison > 0L -> context.getString(R.string.phone_rest_widget_past, duration(comparison))
            else -> context.getString(R.string.phone_rest_widget_at_reference)
        }
    }

    fun formatDuration(context: Context, durationMillis: Long): String {
        val totalMinutes = ((durationMillis + 59_999L) / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> context.getString(R.string.duration_hours_minutes, hours, minutes)
            hours > 0 -> context.resources.getQuantityString(R.plurals.duration_hours, hours.toInt(), hours)
            else -> context.resources.getQuantityString(R.plurals.duration_minutes, minutes.toInt(), minutes)
        }
    }
}
