package tech.mmarca.openwatch.wear.features.status

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.WearLinkUiState

/**
 * The phones on the status screen: the one asking to be allowed, new or
 * with a new key, with Allow and Block; the trusted ones; and the trusted
 * ones that are no longer paired, with a way to Bluetooth settings.
 */
internal fun TransformingLazyColumnScope.phoneItems(
    link: WearLinkUiState,
    spec: TransformationSpec,
    onAllow: (PendingPhone) -> Unit,
    onBlock: (PendingPhone) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val pending = link.pending
    if (pending != null) {
        item(key = "pending-${pending.address}") {
            if (pending.newKey) {
                StatusLine(stringResource(R.string.status_phone_new_key, pending.name), title = true)
            } else {
                StatusLine(stringResource(R.string.status_phone_pending, pending.name), title = true)
            }
        }
        if (pending.newKey) item { StatusLine(stringResource(R.string.phone_new_key_reason)) }
        statusButton(spec, R.string.action_allow) { onAllow(pending) }
        item {
            OutlinedButton(
                onClick = { onBlock(pending) },
                label = { Text(stringResource(R.string.action_block)) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
            )
        }
    }
    for (phone in link.trusted) {
        val lost = link.bondLost.any { it.address == phone.address }
        item(key = "phone-${phone.address}") {
            StatusLine(stringResource(if (lost) R.string.status_phone_bond_lost else R.string.status_phone_trusted, phone.name))
        }
    }
    if (link.bondLost.isNotEmpty()) {
        item {
            Button(
                onClick = onOpenBluetoothSettings,
                label = { Text(stringResource(R.string.action_open_bluetooth)) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
            )
        }
    }
}
