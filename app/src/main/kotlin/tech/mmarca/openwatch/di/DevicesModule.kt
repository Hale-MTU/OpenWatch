package tech.mmarca.openwatch.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.core.pairing.BleWatchPairing
import tech.mmarca.openwatch.devices.core.pairing.WatchPairingPort
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPort
import tech.mmarca.openwatch.devices.wearos.BluetoothWearOsNodePort
import tech.mmarca.openwatch.devices.wearos.WearOsNodePort
import tech.mmarca.openwatch.devices.wearos.WearOsWatchSyncService

/** Wiring for the `devices/` layer, kept apart so [AppModule] carries no watch knowledge. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DevicesModule {

    @Binds
    @Singleton
    abstract fun bindWatchPairingPort(impl: BleWatchPairing): WatchPairingPort

    @Binds
    @Singleton
    abstract fun bindWearOsSyncPort(impl: WearOsWatchSyncService): DeviceSyncPort

    @Binds
    @Singleton
    abstract fun bindWearOsNodePort(impl: BluetoothWearOsNodePort): WearOsNodePort
}
