package tech.mmarca.openwatch.features.scales

import androidx.compose.runtime.Composable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Inject
import tech.mmarca.openwatch.features.device.DeviceScreenContribution
import tech.mmarca.openwatch.features.device.DeviceScreenHost

/** The S400 scale's own destination, in place of the device screen: its weigh-ins, its key, its listening. */
class XiaomiScaleScreen @Inject constructor() : DeviceScreenContribution {
    @Composable
    override fun Screen(host: DeviceScreenHost) {
        ScaleDeviceRoute(onOpenBodyProfile = host.onOpenBodyProfile, onRemoved = host.onRemoved)
    }
}

/** The scale's contribution to the generic screens, by the family's persisted name. */
@Module
@InstallIn(SingletonComponent::class)
internal interface XiaomiScaleFeatureModule {
    @Binds
    @IntoMap
    @StringKey("XIAOMI")
    fun bindScreen(screen: XiaomiScaleScreen): DeviceScreenContribution
}
