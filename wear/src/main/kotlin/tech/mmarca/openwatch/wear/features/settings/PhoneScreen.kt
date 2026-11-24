package tech.mmarca.openwatch.wear.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.TrustedPhone
import tech.mmarca.openwatch.wear.WearLinkUiState
import tech.mmarca.openwatch.wear.features.status.linkLine
import tech.mmarca.openwatch.wear.features.status.phoneItems
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * The phones that may read this watch, and the link they use: whether it is
 * up, a phone asking to be allowed, to answer on the spot, the allowed
 * phones and whether each is still paired ([phoneItems], as on the status
 * screen), and how to add one when there is none.
 */
@Composable
internal fun PhoneScreen(
    state: SettingsUiState,
    onAllow: (PendingPhone) -> Unit,
    onBlock: (PendingPhone) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val link = state.link

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.settings_phone)) }
            }
            item {
                InfoRow(
                    label = stringResource(R.string.phone_link),
                    value = stringResource(linkLine(state.access?.nearbyDevices, link)),
                )
            }
            phoneItems(link, spec, onAllow, onBlock, onOpenBluetoothSettings)
            if (link.trusted.isEmpty() && link.pending == null) {
                item {
                    Text(
                        text = stringResource(R.string.phone_none_hint),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

/** A label over its value, for a fact the wearer reads and does not tap. */
@Composable
internal fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@WearPreviews
@Composable
private fun PhoneScreenPreview() {
    val phone = TrustedPhone(address = "AA:BB", name = "Pixel 7", trustedAtMillis = 0L)
    OpenWatchWearTheme {
        AppScaffold {
            PhoneScreen(
                state = SettingsUiState(link = WearLinkUiState(listening = true, trusted = listOf(phone))),
                onAllow = {},
                onBlock = {},
                onOpenBluetoothSettings = {},
            )
        }
    }
}
