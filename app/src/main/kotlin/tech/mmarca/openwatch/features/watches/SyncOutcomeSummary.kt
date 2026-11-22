package tech.mmarca.openwatch.features.watches

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.presentation.localizedList
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncResult
import tech.mmarca.openwatch.devices.core.sync.HealthSeries
import tech.mmarca.openwatch.devices.core.sync.SkipReason
import tech.mmarca.openwatch.devices.core.sync.SkippedSeries
import tech.mmarca.openwatch.devices.core.sync.SyncTally
import tech.mmarca.openwatch.features.healthconnect.healthPermissionLabel
import tech.mmarca.openwatch.ui.components.InlineGrantPrompt
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * What a run wrote and left out, for any device: a line per count, a line per thing left out,
 * and one button for every grant those wait for. Only this session's run; nothing persists it.
 */
@Composable
internal fun SyncOutcomeSummary(
    outcome: DeviceSyncResult.Succeeded,
    onAllowGrants: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = remember(outcome) { outcome.lines() }
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        for ((tally, count) in lines.written) {
            Text(
                text = pluralStringResource(tally.plural, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (lines.nothingNew) {
            Text(
                text = stringResource(R.string.settings_watch_wearos_synced_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        for (note in lines.notes) {
            Text(
                text = stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (lines.grants.isNotEmpty()) {
            val named = lines.grantSentences.map { stringResource(it) }
            val labels = remember(lines.unnamedPermissions) {
                localizedList(lines.unnamedPermissions.map { healthPermissionLabel(context, it) }.sorted())
            }
            val unnamed = if (labels.isEmpty()) null else stringResource(R.string.settings_watch_garmin_withheld, labels)
            InlineGrantPrompt(
                message = (named + listOfNotNull(unnamed)).joinToString("\n"),
                actionLabel = stringResource(R.string.action_grant_permission),
                onGrant = { onAllowGrants(lines.grants) },
            )
        }
    }
}

/** A run's outcome sorted into what the summary says, before it is worded. */
internal data class SyncOutcomeLines(
    /** The counts above zero, in [SyncTally] order. */
    val written: List<Pair<SyncTally, Int>>,
    /** Nothing written and nothing left out. With something left out it would contradict the lines below. */
    val nothingNew: Boolean,
    /** Things left out that no grant fixes, such as an estimate without a body weight. */
    @param:StringRes val notes: List<Int>,
    /** Things left out for want of a grant, each with its own sentence. */
    @param:StringRes val grantSentences: List<Int>,
    /** Grants of what has no sentence of its own, named by their platform labels. */
    val unnamedPermissions: Set<String>,
    /** Every grant the run waits for: what the one button asks for. */
    val grants: Set<String>,
)

internal fun DeviceSyncResult.Succeeded.lines(): SyncOutcomeLines {
    val ordered = skipped.sortedWith(compareBy<SkippedSeries>({ it.reason }, { it.series }))
    return SyncOutcomeLines(
        written = written.filterValues { it > 0 }.toSortedMap().toList(),
        nothingNew = written.values.all { it == 0 } && skipped.isEmpty(),
        notes = ordered.filter { it.permissions.isEmpty() }.mapNotNull { it.sentence() },
        grantSentences = ordered.filter { it.permissions.isNotEmpty() }.mapNotNull { it.sentence() },
        unnamedPermissions = ordered.filter { it.sentence() == null }.flatMapTo(sortedSetOf()) { it.permissions },
        grants = withheldPermissions,
    )
}

/** The sentence for what was left out, or null for what is named by its grants instead. */
@StringRes
private fun SkippedSeries.sentence(): Int? = when (reason) {
    SkipReason.PERMISSION -> when (series) {
        HealthSeries.STEPS -> R.string.settings_watch_wearos_needs_steps_write
        HealthSeries.WALKING_ENERGY -> R.string.settings_watch_wearos_needs_calories_write
        HealthSeries.RESTING_HEART_RATE -> R.string.settings_watch_wearos_needs_resting_heart_rate
        HealthSeries.HEART_RATE_VARIABILITY -> R.string.settings_watch_wearos_needs_hrv_write
        HealthSeries.RESPIRATORY_RATE -> R.string.settings_watch_wearos_needs_respiratory_write
        HealthSeries.OXYGEN_SATURATION -> R.string.settings_watch_wearos_needs_spo2_write
        HealthSeries.SKIN_TEMPERATURE -> R.string.settings_watch_wearos_needs_skin_write
        HealthSeries.WORKOUTS -> R.string.settings_watch_wearos_needs_workout_write
        HealthSeries.WORKOUT_ENERGY, HealthSeries.RECORDS -> null
    }
    SkipReason.NO_BODY_WEIGHT -> when (series) {
        HealthSeries.WALKING_ENERGY -> if (permissions.isEmpty()) {
            R.string.settings_watch_wearos_needs_weight
        } else {
            R.string.settings_watch_wearos_needs_weight_read
        }
        HealthSeries.WORKOUT_ENERGY -> R.string.settings_watch_garmin_needs_weight
        else -> null
    }
}

private val SyncTally.plural: Int
    @PluralsRes get() = when (this) {
        SyncTally.FILES -> R.plurals.sync_written_files
        SyncTally.HEART_RATE_SAMPLES -> R.plurals.settings_watch_wearos_synced_samples
        SyncTally.STEP_MINUTES -> R.plurals.settings_watch_wearos_synced_step_minutes
        SyncTally.SLEEP_NIGHTS -> R.plurals.settings_watch_wearos_synced_nights
    }
