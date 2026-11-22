package tech.mmarca.openwatch.features.home

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.devices.garmin.GarminRealtimeState
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/** The home tile is one wrist: the watch being synced, else the one synced last. */
class WatchTileStateTest {

    private val now = Instant.parse("2026-11-22T10:00:00Z")

    private fun device(id: String, kind: BleDeviceKind = BleDeviceKind.WATCH, syncedMinutesAgo: Long? = null) = BleSensorDevice(
        id = id,
        displayName = id,
        address = "AA:BB:CC:DD:EE:0${id.length}",
        bluetoothName = id,
        capabilities = emptySet(),
        enabled = true,
        wheelCircumferenceMm = null,
        addedAt = now,
        kind = kind,
        integration = DeviceIntegration.GARMIN,
        lastSyncedAt = syncedMinutesAgo?.let { now.minusSeconds(it * 60) },
    )

    @Test
    fun `no watch, or only a bike computer or a scale, gives no tile`() {
        assertNull(emptyList<BleSensorDevice>().toWatchTileState(now = now))
        assertNull(listOf(device("edge", BleDeviceKind.BIKE_COMPUTER), device("s400", BleDeviceKind.SCALE)).toWatchTileState(now = now))
    }

    @Test
    fun `the watch being synced owns the tile, else the one synced last, and the rest are counted`() {
        val devices = listOf(device("venu", syncedMinutesAgo = 5), device("fenix", syncedMinutesAgo = 60), device("edge", BleDeviceKind.BIKE_COMPUTER))

        assertEquals("venu", devices.toWatchTileState(now = now)!!.deviceId)
        val syncing = devices.toWatchTileState(syncingDeviceIds = setOf("fenix"), now = now)!!
        assertEquals("fenix", syncing.deviceId)
        assertEquals(true, syncing.isSyncing)
        assertEquals(1, syncing.additionalCount)
    }

    @Test
    fun `live values show only on the watch that sent them, and only while fresh`() {
        val devices = listOf(device("venu", syncedMinutesAgo = 5))
        val live = GarminRealtimeState(deviceId = "venu", heartRateBpm = 61, heartRateAt = now.minusSeconds(10), steps = 4_200, stepsAt = now.minusSeconds(10))

        val tile = devices.toWatchTileState(live = live, now = now)!!
        assertEquals(61, tile.liveHeartRateBpm)
        assertEquals(4_200, tile.liveSteps)

        assertNull(devices.toWatchTileState(live = live.copy(deviceId = "other"), now = now)!!.liveHeartRateBpm)
        assertNull(devices.toWatchTileState(live = live, now = now.plusSeconds(3_600))!!.liveHeartRateBpm)
    }
}
