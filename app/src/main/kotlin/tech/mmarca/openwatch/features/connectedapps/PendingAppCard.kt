package tech.mmarca.openwatch.features.connectedapps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.theme.Spacing

/** On the home screen while an app waits for an answer; Review opens Connected apps. */
@Composable
fun PendingAppCard(label: String, onReview: () -> Unit, modifier: Modifier = Modifier) {
    OpenWatchCard(modifier = modifier) {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(text = stringResource(R.string.connected_apps_pending_title, label), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.connected_apps_pending_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onReview) { Text(stringResource(R.string.connected_apps_review)) }
        }
    }
}
