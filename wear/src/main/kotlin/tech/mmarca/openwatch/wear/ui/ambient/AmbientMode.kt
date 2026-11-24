package tech.mmarca.openwatch.wear.ui.ambient

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientModeManager
import androidx.wear.compose.foundation.AmbientTickEffect
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager

/**
 * A screen that stays on in ambient mode while something runs, a workout or
 * a spot measurement: the [interactive] layout while the screen is awake, a
 * dim one of its own, [ambient], while it is not.
 *
 * Wear OS 6 keeps an app that targets API 36 visible in ambient mode, dimmed,
 * on every screen. A screen that observes the ambient state draws its own
 * low-power layout instead, through the Wear Compose [AmbientModeManager];
 * being composed here turns that on, and only for as long as this is
 * composed, so a caller composes it only while its work runs. On a watch
 * without the Wear SDK's ambient service the manager is not there; the system
 * then dims the screen or shows the watch face, and [interactive] is all
 * there is.
 *
 * The ambient layout follows the always-on guidance: black, no buttons,
 * nothing bright, nothing that moves. The system updates it about once a
 * minute, so it gets [latest] as of the last ambient tick ([ticked]) rather
 * than the second-by-second one, and should show nothing that would be wrong
 * in between. The interactive layout leaves composition meanwhile, so what it
 * must find again is remembered above this.
 */
@Composable
internal fun <T> AmbientAware(
    latest: T,
    ambient: @Composable (ticked: T) -> Unit,
    interactive: @Composable () -> Unit,
) {
    val manager = rememberAmbientModeManagerIfSupported()
    if (manager == null) {
        interactive()
        return
    }
    CompositionLocalProvider(LocalAmbientModeManager provides manager) {
        val current by rememberUpdatedState(latest)
        var ticked by remember { mutableStateOf(latest) }
        manager.AmbientTickEffect { ticked = current }
        val dim = manager.currentAmbientMode is AmbientMode.Ambient
        LaunchedEffect(dim) { if (dim) ticked = current }
        if (dim) ambient(ticked) else interactive()
    }
}

/**
 * The activity's ambient mode manager where the watch has the Wear SDK's
 * ambient service it rests on (a system library of recent Wear OS
 * releases); null elsewhere, where creating it would throw, since Wear
 * Compose takes the service with no fall-back. Being composed turns the
 * ambient handling on.
 */
@Composable
private fun rememberAmbientModeManagerIfSupported(): AmbientModeManager? {
    val context = LocalContext.current
    val supported = remember { hasWearSdkAmbient(context) }
    return if (supported) rememberAmbientModeManager() else null
}

/** Whether `Sdk.getWearManager(context, AmbientManager::class.java)` answers, asked by reflection: the app has no stub of the library. */
private fun hasWearSdkAmbient(context: Context): Boolean = try {
    val loader = context.classLoader
    val manager = Class.forName(WEAR_SDK_AMBIENT_MANAGER, false, loader)
    val sdk = Class.forName(WEAR_SDK, false, loader)
    sdk.getMethod("getWearManager", Context::class.java, Class::class.java).invoke(null, context.applicationContext, manager) != null
} catch (_: ReflectiveOperationException) {
    false
} catch (_: LinkageError) {
    false
} catch (_: RuntimeException) {
    false
}

private const val WEAR_SDK = "com.google.wear.Sdk"
private const val WEAR_SDK_AMBIENT_MANAGER = "com.google.wear.services.ambient.AmbientManager"
