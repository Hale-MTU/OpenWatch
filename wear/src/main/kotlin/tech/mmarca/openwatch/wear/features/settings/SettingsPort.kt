package tech.mmarca.openwatch.wear.features.settings

import android.content.Context
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.WearGraph
import tech.mmarca.openwatch.wear.WearPermissions
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wear.features.status.GraphStatusPort

/**
 * What the settings screens read and change, apart from the screens: the
 * grants, the trust store and the unit system on the watch
 * ([GraphSettingsPort]), a fake in a test. Every call blocks; the ViewModel
 * makes them off the main thread.
 */
internal interface SettingsPort {
    /** The grants now. */
    fun access(): SensorAccess

    fun allow(phone: PendingPhone)

    fun block(phone: PendingPhone)

    fun setUnitSystem(unitSystem: UnitSystem)
}

/** [SettingsPort] over the process's [WearGraph]; a phone is allowed or blocked as on the status screen. */
internal class GraphSettingsPort(private val context: Context, private val graph: WearGraph) : SettingsPort {

    private val status = GraphStatusPort(context, graph)

    override fun access(): SensorAccess = SensorAccess(
        heartRate = WearPermissions.hasHeartRate(context),
        heartRateInBackground = WearPermissions.hasHeartRateInBackground(context),
        steps = WearPermissions.hasActivityRecognition(context).takeIf { graph.sensors.stepCounter },
        nearbyDevices = WearPermissions.hasBluetooth(context),
    )

    override fun allow(phone: PendingPhone) = status.allow(phone)

    override fun block(phone: PendingPhone) = status.block(phone)

    /** Only how readings show changes: the recorders do not follow it, so the service is not told. */
    override fun setUnitSystem(unitSystem: UnitSystem) = WearSettings.setUnitSystem(context, unitSystem)
}
