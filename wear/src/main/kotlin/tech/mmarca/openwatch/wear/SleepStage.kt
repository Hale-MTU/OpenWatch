package tech.mmarca.openwatch.wear

import androidx.annotation.StringRes

/** Same stages as the phone app's sleep screen, top to bottom of a hypnogram. Their colors are the theme's ([tech.mmarca.openwatch.wear.ui.theme.color]). */
enum class SleepStage(@param:StringRes val label: Int) {
    AWAKE(R.string.sleep_awake),
    REM(R.string.sleep_rem),
    LIGHT(R.string.sleep_light),
    DEEP(R.string.sleep_deep),
}
