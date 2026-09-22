package com.filestech.pass_tech.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.filestech.pass_tech.R

/** 2.7.1 format: under a minute in seconds, round minutes alone, otherwise minutes and seconds. */
fun countdownText(resources: Resources, millis: Long): String {
    val total = ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
    val minutes = total / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return when {
        minutes == 0 -> resources.getString(R.string.unit_seconds_short, seconds)
        seconds == 0 -> resources.getString(R.string.unit_minutes_short, minutes)
        else -> resources.getString(R.string.unit_minutes_seconds_short, minutes, seconds)
    }
}

@Composable
fun countdownText(millis: Long): String = countdownText(LocalResources.current, millis)

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60
