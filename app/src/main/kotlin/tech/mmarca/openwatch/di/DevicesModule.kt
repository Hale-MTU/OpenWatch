package tech.mmarca.openwatch.di

import tech.mmarca.openwatch.devices.wearos.WearOsFamily
import tech.mmarca.openwatch.devices.garmin.GarminFamily
import tech.mmarca.openwatch.devices.core.DeviceFamily
import dagger.multibindings.StringKey
import dagger.multibindings.IntoMap
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.core.pairing.BleWatchPairing
import tech.mmarca.openwatch.devices.core.pairing.CompanionPresenceObserver
import tech.mmarca.openwatch.devices.core.pairing.WatchPairingPort
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPort
import tech.mmarca.openwatch.devices.garmin.GarminCounterWatermarkStore
import tech.mmarca.openwatch.devices.garmin.GarminDeviceStateStore
import tech.mmarca.openwatch.devices.core.sync.AutoSyncIntervalStore
import tech.mmarca.openwatch.devices.garmin.GarminFileStore
import tech.mmarca.openwatch.devices.garmin.GarminGattProbe
import tech.mmarca.openwatch.devices.garmin.GarminGattRadio
import tech.mmarca.openwatch.devices.garmin.GarminNotificationBridge
import tech.mmarca.openwatch.devices.garmin.GarminRadio
import tech.mmarca.openwatch.devices.garmin.GarminTransportProbe
import tech.mmarca.openwatch.devices.garmin.GarminWatchSyncService
import tech.mmarca.openwatch.devices.garmin.garminFileStore
import tech.mmarca.openwatch.devices.media.AndroidPhoneMediaSource
import tech.mmarca.openwatch.devices.media.PhoneMediaSource
import tech.mmarca.openwatch.devices.xiaomi.ScaleForeground
import tech.mmarca.openwatch.devices.xiaomi.ScaleListeningForeground
import tech.mmarca.openwatch.devices.xiaomi.ScaleScanRadio
import tech.mmarca.openwatch.devices.xiaomi.SystemScaleScanRadio
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleListener
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleStore
import tech.mmarca.openwatch.devices.core.sync.CompositeDeviceSyncPort
import tech.mmarca.openwatch.devices.wearos.BluetoothWearOsNodePort
import tech.mmarca.openwatch.devices.wearos.WearOsNodePort
import tech.mmarca.openwatch.devices.wearos.WearOsWatchSyncService
import tech.mmarca.openwatch.devices.wearos.WearOsDeviceClassifier
import tech.mmarca.openwatch.devices.garmin.GarminScanClassifier
import tech.mmarca.openwatch.devices.garmin.GarminDeviceClassifier
import tech.mmarca.openwatch.devices.core.DeviceScanClassifier
import tech.mmarca.openwatch.devices.core.DeviceClassifier
import tech.mmarca.openwatch.devices.notifications.NotificationSink
import tech.mmarca.openwatch.devices.core.sync.SyncWakeLock
import tech.mmarca.openwatch.devices.core.sync.AndroidSyncWakeLock

/** Wiring for the `devices/` layer, kept apart so [AppModule] carries no watch knowledge. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DevicesModule {

    @Binds
    @Singleton
    abstract fun bindWatchPairingPort(impl: BleWatchPairing): WatchPairingPort

    // One port per integration; the composite hands each device to the one that claims it.
    @Binds
    @IntoSet
    abstract fun bindGarminSyncPort(impl: GarminWatchSyncService): DeviceSyncPort

    @Binds
    @IntoSet
    abstract fun bindWearOsSyncPort(impl: WearOsWatchSyncService): DeviceSyncPort

    @Binds
    @Singleton
    abstract fun bindPhoneMediaSource(impl: AndroidPhoneMediaSource): PhoneMediaSource

    @Binds
    @Singleton
    abstract fun bindGarminRadio(impl: GarminGattRadio): GarminRadio

    @Binds
    @Singleton
    abstract fun bindScaleScanRadio(impl: SystemScaleScanRadio): ScaleScanRadio

    @Binds
    @Singleton
    abstract fun bindScaleForeground(impl: ScaleListeningForeground): ScaleForeground

    // What OpenWatchCompanionDeviceService tells when an associated device comes into range.
    @Binds
    @IntoSet
    abstract fun bindScalePresenceObserver(impl: XiaomiScaleListener): CompanionPresenceObserver

    @Binds
    @IntoSet
    abstract fun bindWatchPresenceObserver(impl: GarminNotificationBridge): CompanionPresenceObserver

    // The one integration that mirrors the phone's notifications today.
    @Binds
    abstract fun bindNotificationSink(impl: GarminNotificationBridge): NotificationSink

    @Binds
    @Singleton
    abstract fun bindWearOsNodePort(impl: BluetoothWearOsNodePort): WearOsNodePort

    // Every watch's sync interval, Wear OS included, lives in the Garmin store under its old key.
    @Binds
    abstract fun bindAutoSyncIntervalStore(impl: GarminDeviceStateStore): AutoSyncIntervalStore

    @Binds
    abstract fun bindSyncWakeLock(impl: AndroidSyncWakeLock): SyncWakeLock

    // Each family by its persisted name; DeviceFamilies checks the key against the family's id.
    @Binds
    @IntoMap
    @StringKey("GARMIN")
    abstract fun bindGarminFamily(impl: GarminFamily): DeviceFamily

    @Binds
    @IntoMap
    @StringKey("WEAROS")
    abstract fun bindWearOsFamily(impl: WearOsFamily): DeviceFamily

    companion object {

        /**
         * Each integration's verdict on a scanned device. A list, not a set: the first that
         * claims a device wins, so the order is part of the rule. A new integration adds its
         * classifier here, and nothing in `sensors/` changes.
         */
        @Provides
        fun provideDeviceClassifiers(): List<@JvmSuppressWildcards DeviceClassifier> =
            listOf(GarminDeviceClassifier(), WearOsDeviceClassifier())

        @Provides
        fun provideDeviceScanClassifiers(): List<@JvmSuppressWildcards DeviceScanClassifier> =
            listOf(GarminScanClassifier())

        @Provides
        @Singleton
        fun provideDeviceSyncPort(ports: Set<@JvmSuppressWildcards DeviceSyncPort>): DeviceSyncPort =
            CompositeDeviceSyncPort(ports)

        @Provides
        @Singleton
        fun provideGarminTransportProbe(
            @ApplicationContext context: Context,
        ): GarminTransportProbe = GarminGattProbe(context)

        @Provides
        @Singleton
        fun provideGarminDeviceStateStore(
            @ApplicationContext context: Context,
        ): GarminDeviceStateStore = GarminDeviceStateStore(context)

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

        // One instance: the receiver, the worker and the screen read the same state.
        @Provides
        @Singleton
        fun provideXiaomiScaleStore(
            @ApplicationContext context: Context,
        ): XiaomiScaleStore = XiaomiScaleStore(context)
    }
}
