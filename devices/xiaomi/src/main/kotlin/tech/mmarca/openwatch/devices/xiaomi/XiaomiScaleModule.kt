package tech.mmarca.openwatch.devices.xiaomi

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey
import javax.inject.Singleton
import tech.mmarca.openwatch.StartupActor
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.pairing.CompanionPresenceObserver
import tech.mmarca.openwatch.wearlink.SecretSeal

/**
 * The S400 scale's bindings, installed by the app's graph from this module: its family by its
 * persisted name, its presence observer, its scan and foreground ports, its store and what it
 * starts with the process. The weigh-in table it writes to is the app's
 * (`ScaleWeighInRepository`, bound in the app's `RepositoryModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class XiaomiScaleModule {

    // DeviceFamilies checks the key against the family's id.
    @Binds
    @IntoMap
    @StringKey("XIAOMI")
    abstract fun bindXiaomiScaleFamily(impl: XiaomiScaleFamily): DeviceFamily

    // What OpenWatchCompanionDeviceService tells when the scale wakes.
    @Binds
    @IntoSet
    abstract fun bindScalePresenceObserver(impl: XiaomiScaleListener): CompanionPresenceObserver

    @Binds
    @Singleton
    abstract fun bindScaleScanRadio(impl: SystemScaleScanRadio): ScaleScanRadio

    @Binds
    @Singleton
    abstract fun bindScaleForeground(impl: ScaleListeningForeground): ScaleForeground

    companion object {
        @Provides
        @IntoSet
        fun provideScaleListenerAtStart(listener: XiaomiScaleListener) =
            // A reboot or Bluetooth going off drops the scan that hears the scale.
            StartupActor("scale listener", listener::onAppStart)

        // One instance: the receiver, the worker and the screen read the same state.
        @Provides
        @Singleton
        fun provideXiaomiScaleStore(
            @ApplicationContext context: Context,
            seal: SecretSeal,
        ): XiaomiScaleStore = XiaomiScaleStore(context, seal)
    }
}
