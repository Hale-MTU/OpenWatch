package tech.mmarca.openwatch.features.connectedapps

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tech.mmarca.openwatch.R

/** "5 minutes ago", or "just now" inside the first minute, against [nowMillis]. */
@Composable
internal fun relativeTime(atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    if (nowMillis - atMillis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.time_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(atMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
    }

/** A time worth showing: set, and not the stamp 1 of a profile that predates the link. */
internal fun Long.isRealTime(): Boolean = this > 1_000_000_000_000L
