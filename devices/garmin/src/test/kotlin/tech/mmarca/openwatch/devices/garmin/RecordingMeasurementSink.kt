package tech.mmarca.openwatch.devices.garmin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.openwatch.devices.core.measure.MeasurementOutcome
import tech.mmarca.openwatch.devices.core.measure.MeasurementSink
import tech.mmarca.openwatch.devices.core.measure.PushedMeasurement
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/** A sink that writes nothing: it keeps what it is handed, and a retry reports [withheld] as waiting. */
internal class RecordingMeasurementSink(var withheld: Set<String> = emptySet()) : MeasurementSink {

    val accepted = mutableListOf<Pair<BleSensorDevice, List<PushedMeasurement>>>()
    val retried = mutableListOf<BleSensorDevice?>()

    override suspend fun accept(device: BleSensorDevice, measurements: List<PushedMeasurement>): MeasurementOutcome {
        accepted += device to measurements
        return MeasurementOutcome(written = measurements.size)
    }

    override suspend fun retryPending(device: BleSensorDevice?): MeasurementOutcome {
        retried += device
        return MeasurementOutcome(pending = withheld.size, withheld = withheld)
    }

    override val backlog: StateFlow<Map<String, MeasurementOutcome>> = MutableStateFlow(emptyMap())
}
