package tech.mmarca.openwatch.features.watches

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.features.healthconnect.healthPermissionLabel
import tech.mmarca.openwatch.ui.components.InlineGrantPrompt

/**
 * The record types a Garmin sync left out for want of a Health Connect write grant, named
 * as Android names the permission, with the button that asks for them.
 */
@Composable
internal fun GarminUnwrittenNotice(
    withheldPermissions: Set<String>,
    onAllowGrants: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (withheldPermissions.isEmpty()) return
    val context = LocalContext.current
    val names = remember(withheldPermissions) {
        withheldPermissions.map { healthPermissionLabel(context, it) }.sorted().joinToString(", ")
    }
    InlineGrantPrompt(
        message = stringResource(R.string.settings_watch_garmin_withheld, names),
        actionLabel = stringResource(R.string.action_grant_permission),
        onGrant = { onAllowGrants(withheldPermissions) },
        modifier = modifier,
    )
}
