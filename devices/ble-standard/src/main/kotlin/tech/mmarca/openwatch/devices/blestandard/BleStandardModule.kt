package tech.mmarca.openwatch.devices.blestandard

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.measure.MeasurementRecords
import tech.mmarca.openwatch.devices.core.pairing.CompanionPresenceObserver

/**
 * The standard-profile family's bindings, installed by the app's graph from this module: its
 * family and its measurements' mapping, both by its persisted name, the collector Android tells
 * when a cuff, a scale or a meter wakes, and its radio.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BleStandardModule {

    // DeviceFamilies checks the key against the family's id.
    @Binds
    @IntoMap
    @StringKey("BLE_STANDARD")
    abstract fun bindStandardDeviceFamily(impl: StandardDeviceFamily): DeviceFamily

    // What the measurement sink reads this family's kept measurements back through.
    @Binds
    @IntoMap
    @StringKey("BLE_STANDARD")
    abstract fun bindStandardMeasurementRecords(impl: StandardMeasurementRecords): MeasurementRecords

    // What OpenWatchCompanionDeviceService tells when an associated device comes into range.
    @Binds
    @IntoSet
    abstract fun bindMeasurementCollector(impl: MeasurementCollector): CompanionPresenceObserver

    @Binds
    @Singleton
    abstract fun bindProfileRadio(impl: GattProfileRadio): ProfileRadio
}
