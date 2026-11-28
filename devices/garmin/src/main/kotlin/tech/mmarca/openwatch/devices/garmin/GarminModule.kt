package tech.mmarca.openwatch.devices.garmin

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
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.DeviceStateRepository
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.measure.MeasurementRecords
import tech.mmarca.openwatch.devices.core.pairing.CompanionPresenceObserver
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPort
import tech.mmarca.openwatch.devices.notifications.NotificationSink

/**
 * The Garmin watches' bindings, installed by the app's graph from this module: the family by
 * its persisted name, the records of the hydration widget's drinks, the sync port, the radio,
 * the notification bridge (the watches' presence observer and the one sink the notification
 * listener feeds), the stores, and what
 * the family starts with the process. Its classifiers are ranked against the other families'
 * in the app's `DevicesModule`, and the app binds every watch's auto-sync interval to
 * [GarminDeviceStateStore] there, since that store keeps them all under their old key.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GarminModule {

    // DeviceFamilies checks the key against the family's id.
    @Binds
    @IntoMap
    @StringKey("GARMIN")
    abstract fun bindGarminFamily(impl: GarminFamily): DeviceFamily

    // The drinks the hydration widget logs, as the measurement sink writes them.
    @Binds
    @IntoMap
    @StringKey("GARMIN")
    abstract fun bindGarminMeasurementRecords(impl: GarminHydrationRecords): MeasurementRecords

    // One port per integration; the composite hands each device to the one that claims it.
    @Binds
    @IntoSet
    abstract fun bindGarminSyncPort(impl: GarminWatchSyncService): DeviceSyncPort

    @Binds
    @Singleton
    abstract fun bindGarminRadio(impl: GarminGattRadio): GarminRadio

    // What OpenWatchCompanionDeviceService tells when an associated watch comes into range.
    @Binds
    @IntoSet
    abstract fun bindWatchPresenceObserver(impl: GarminNotificationBridge): CompanionPresenceObserver

    // The one integration that mirrors the phone's notifications today.
    @Binds
    abstract fun bindNotificationSink(impl: GarminNotificationBridge): NotificationSink

    companion object {
        // What the family starts at every process start (AppStartupActors).
        @Provides
        @IntoSet
        fun provideGarminNotificationsAtStart(bridge: GarminNotificationBridge) =
            // Companion mode must be re-armed on every start.
            StartupActor("garmin notifications", bridge::onAppStart)

        @Provides
        @IntoSet
        fun provideGarminNavigationAtStart(relay: GarminNavigationRelay) = StartupActor("garmin navigation", relay::start)

        @Provides
        @IntoSet
        fun provideGarminMusicAtStart(relay: GarminMusicRelay) = StartupActor("garmin music", relay::start)

        @Provides
        @IntoSet
        fun provideGarminPruningAtStart(localData: GarminLocalData) =
            // Watch files and sleep minutes age out even when no watch syncs any more.
            StartupActor("garmin pruning", localData::pruneOnAppStart)

        @Provides
        @Singleton
        fun provideGarminTransportProbe(
            @ApplicationContext context: Context,
        ): GarminTransportProbe = GarminGattProbe(context)

        @Provides
        @Singleton
        fun provideGarminDeviceStateStore(
            @ApplicationContext context: Context,
            state: DeviceStateRepository,
            registry: DeviceRegistry,
        ): GarminDeviceStateStore = GarminDeviceStateStore(context, state, registry.devices.mapTo(HashSet()) { it.id })

        // One instance: the store finds a file's pending note by the object that saved it.
        @Provides
        @Singleton
        fun provideGarminFileStore(
            @ApplicationContext context: Context,
        ): GarminFileStore = garminFileStore(context)

        @Provides
        @Singleton
        fun provideGarminCounterWatermarkStore(
            @ApplicationContext context: Context,
        ): GarminCounterWatermarkStore = GarminCounterWatermarkStore(context)
    }
}
