package tech.mmarca.openwatch.features.healthconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import tech.mmarca.openwatch.healthconnect.HealthDataCatalog
import tech.mmarca.openwatch.healthconnect.HealthDataEntry
import tech.mmarca.openwatch.healthconnect.HealthDataSection
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * Every health data type OpenWatch reads or writes, by the device it comes from: the
 * [HealthDataCatalog] as the user reads it. Stateless and without a view model, so the
 * screen Health Connect opens for "why does this app want this" can show it too.
 */
@Composable
fun HealthDataList(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        HealthDataCatalog.sections.forEach { (section, entries) ->
            HealthDataSectionCard(section, entries)
        }
    }
}

@Composable
private fun HealthDataSectionCard(section: HealthDataSection, entries: List<HealthDataEntry>) {
    val context = LocalContext.current
    // The platform's own name for each permission, in the phone's language.
    val labels = remember(entries) { entries.map { healthPermissionLabel(context, it.permission) } }
    OpenWatchCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = stringResource(section.titleRes),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            entries.forEachIndexed { index, entry ->
                Column {
                    Text(text = labels[index], style = MaterialTheme.typography.bodyMedium)
                    entry.noteRes?.let { note ->
                        Text(
                            text = stringResource(note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
