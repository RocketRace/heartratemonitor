package com.heartratemonitor.app

import android.content.Context
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class HighlightEvent(val kind: HighlightKind, val timestamp: LocalDateTime, private val lastRrMsInterval: Int) {
    // Make sure to only call this in a valid context
    fun format(context: Context, zero: LocalDateTime): String {
        val highlightString = context.getString(kind.id)
        val datetime = formatDateTime(timestamp)
        val sinceStart = formatDuration(
            Duration.ofSeconds(zero.until(timestamp, ChronoUnit.SECONDS)
        ))
        return "$datetime\t$sinceStart\t$lastRrMsInterval\t$highlightString"
    }
}

fun formatDuration(duration: Duration): String {
    val seconds = duration.seconds
    val minutes = duration.toMinutes()
    val hours = duration.toHours()
    if (hours == 0L) {
        return "%02d:%02d".format(minutes, seconds)
    }
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

fun formatDateTime(timestamp: LocalDateTime, pathSafe: Boolean = false): String {
    val formatter = if (pathSafe) {
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")
    }
    else {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
    return timestamp.format(formatter)
}