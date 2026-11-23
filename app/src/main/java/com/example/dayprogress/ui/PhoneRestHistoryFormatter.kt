package com.example.dayprogress.ui

import android.content.Context
import android.text.format.DateFormat
import com.example.dayprogress.R
import com.example.dayprogress.data.PhoneRestAnalyzer
import com.example.dayprogress.widget.WidgetDisplayFormatter
import java.util.Date

/** Dated, neutral comparisons; a calendar boundary is never an end-of-day signal. */
internal object PhoneRestHistoryFormatter {
    fun entries(context: Context, endTimes: List<Long>): List<String> {
        // Records follow observation order even if the user moves the device clock backward.
        val recorded = endTimes.filter { it >= 0L }
        return recorded.mapIndexed { index, end ->
            val timestamp = formatDateTime(context, end)
            val comparison = if (index == 0) {
                context.getString(R.string.phone_rest_history_first)
            } else {
                val minutes = PhoneRestAnalyzer.comparisonDeltaMinutes(recorded[index - 1], end)
                when {
                    minutes == 0 -> context.getString(R.string.phone_rest_history_same)
                    minutes < 0 -> context.getString(R.string.phone_rest_history_earlier,
                        WidgetDisplayFormatter.formatDuration(context, -minutes * 60_000L))
                    else -> context.getString(R.string.phone_rest_history_later,
                        WidgetDisplayFormatter.formatDuration(context, minutes * 60_000L))
                }
            }
            context.getString(R.string.phone_rest_history_entry, timestamp, comparison)
        }.reversed()
    }

    fun formatDateTime(context: Context, millis: Long): String {
        val date = Date(millis)
        return context.getString(R.string.phone_rest_date_time,
            DateFormat.getMediumDateFormat(context).format(date),
            DateFormat.getTimeFormat(context).format(date))
    }
}
