package tech.mmarca.openwatch.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import tech.mmarca.openwatch.data.repository.ActivityWriteRepository
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.data.repository.BodyProfileProvider
import tech.mmarca.openwatch.data.repository.DeviceStateStore
import tech.mmarca.openwatch.comaps.CoMapsNavigationRepositoryImpl
import tech.mmarca.openwatch.data.repository.GarminSleepMinuteRepositoryImpl
import tech.mmarca.openwatch.data.repository.GarminWellnessRepositoryImpl
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.data.repository.ScaleWeighInRepositoryImpl
import tech.mmarca.openwatch.data.repository.contract.ActivityRepository
import tech.mmarca.openwatch.data.repository.contract.BodyProfilePreferences
import tech.mmarca.openwatch.data.repository.contract.BodyProfileRepository
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.DeviceStateRepository
import tech.mmarca.openwatch.data.repository.contract.WearOsSyncPreferences
import tech.mmarca.openwatch.comaps.CoMapsNavigationRepository
import tech.mmarca.openwatch.data.repository.contract.GarminSleepMinuteRepository
import tech.mmarca.openwatch.data.repository.contract.GarminWellnessRepository
import tech.mmarca.openwatch.data.repository.contract.ScaleWeighInRepository
import tech.mmarca.openwatch.data.repository.HealthRecordStore
import tech.mmarca.openwatch.data.repository.ImportedRecordRepository
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.healthconnect.HealthConnectSyncSwitch

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindActivityRepository(impl: ActivityWriteRepository): ActivityRepository

    @Binds
    @Singleton
    abstract fun bindCoMapsNavigationRepository(impl: CoMapsNavigationRepositoryImpl): CoMapsNavigationRepository

    @Binds
    abstract fun bindHealthConnectSyncSwitch(impl: OpenWatchPreferences): HealthConnectSyncSwitch

    @Binds
    @Singleton
    abstract fun bindGarminWellnessRepository(impl: GarminWellnessRepositoryImpl): GarminWellnessRepository

    @Binds
    @Singleton
    abstract fun bindGarminSleepMinuteRepository(impl: GarminSleepMinuteRepositoryImpl): GarminSleepMinuteRepository

    @Binds
    @Singleton
    abstract fun bindScaleWeighInRepository(impl: ScaleWeighInRepositoryImpl): ScaleWeighInRepository

    @Binds
    @Singleton
    abstract fun bindBodyProfilePreferences(impl: OpenWatchPreferences): BodyProfilePreferences

    /** The registry as the device modules see it (:core:devices-api); the one instance. */
    @Binds
    abstract fun bindDeviceRegistry(impl: BleDeviceRepository): DeviceRegistry

    /** What each integration keeps per device (:core:devices-api), on the device_state table. */
    @Binds
    abstract fun bindDeviceStateRepository(impl: DeviceStateStore): DeviceStateRepository

    /** The one preference the Wear OS sync reads (:devices:wearos). */
    @Binds
    abstract fun bindWearOsSyncPreferences(impl: OpenWatchPreferences): WearOsSyncPreferences

    /** The body the device estimates use (:core:devices-api): declared, with Health Connect's on top. */
    @Binds
    abstract fun bindBodyProfileRepository(impl: BodyProfileProvider): BodyProfileRepository

    /** The record sink the devices write through (:core:health-api). */
    @Binds
    abstract fun bindHealthRecordStore(impl: ImportedRecordRepository): HealthRecordStore

    /** The strict reads the devices make (:core:health-api); the gateway holds the client. */
    @Binds
    abstract fun bindHealthConnectReads(impl: HealthConnectGateway): HealthConnectReads
}
