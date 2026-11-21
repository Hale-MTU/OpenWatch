package tech.mmarca.openwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import tech.mmarca.openwatch.features.healthconnect.HealthDataList
import tech.mmarca.openwatch.ui.components.OpenWatchButton
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * What OpenWatch reads and writes, and why. Health Connect opens this from its own
 * permission screen ("why does this app want this"), and Settings and the Health Connect
 * setup open it too, so there is one place that answers the question.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OpenWatchTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Text(
                            text = stringResource(R.string.health_data_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = stringResource(R.string.health_data_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HealthDataList(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.privacy_policy_body_2),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.privacy_policy_body_3),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OpenWatchButton(onClick = ::finish, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            }
        }
    }
}
