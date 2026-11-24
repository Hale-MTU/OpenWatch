package tech.mmarca.openwatch.wear.features.recording

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.features.measure.EdgeProgress
import tech.mmarca.openwatch.wear.ui.components.MetricValueRow
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.Emphasis
import tech.mmarca.openwatch.wear.ui.theme.HeartColor
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme
import tech.mmarca.openwatch.wear.workout.RecoveryState

/**
 * The two minutes of a heart rate recovery after a workout's stop: the time
 * left, filling an arc along the screen's edge, the heart rate now and what
 * to do. The recorder measures it in the service; this screen only shows it,
 * until the falls are worked out and the summary follows.
 */
@Composable
internal fun RecoveryScreen(recovery: RecoveryState, now: () -> Long = System::currentTimeMillis) {
    var at by remember { mutableLongStateOf(now()) }
    LaunchedEffect(recovery.untilMillis) {
        while (at < recovery.untilMillis) {
            delay(TICK_MILLIS)
            at = now()
        }
    }
    val total = (recovery.untilMillis - recovery.stopMillis).coerceAtLeast(1)
    val leftSeconds = ((recovery.untilMillis - at) / MILLIS_PER_SECOND).coerceAtLeast(0)
    ScreenScaffold {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EdgeProgress(
                progress = ((at - recovery.stopMillis).toFloat() / total).coerceIn(0f, 1f),
                color = HeartColor,
                trackColor = HeartColor.copy(alpha = Emphasis.subtle),
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier.padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.titleMedium)
                Text(DateUtils.formatElapsedTime(leftSeconds), style = MaterialTheme.typography.displaySmall)
                MetricValueRow(
                    value = recovery.bpm?.toString(),
                    unit = stringResource(R.string.unit_bpm),
                    valueStyle = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.recovery_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val TICK_MILLIS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L

@WearPreviews
@Composable
private fun RecoveryScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            RecoveryScreen(RecoveryState(stopMillis = 0, untilMillis = 120_000, bpm = 128), now = { 45_000 })
        }
    }
}
