package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wear.MetricTile.FLOORS
import tech.mmarca.openwatch.wear.MetricTile.HEART_RATE
import tech.mmarca.openwatch.wear.MetricTile.HRV
import tech.mmarca.openwatch.wear.MetricTile.STEPS

/** The dashboard's chosen tiles, as stored. How a choice is edited is `ChoicesTest`, in `:wear:recording-core`. */
class WearTilePreferencesTest {

    private val prefs = FakeSharedPreferences()

    @Test
    fun `nothing chosen yet is the default tiles`() {
        assertEquals(MetricTile.DefaultTiles, WearTilePreferences(prefs).chosen.value)
    }

    @Test
    fun `a choice survives a restart, stored as the tiles' names`() {
        WearTilePreferences(prefs).edit { listOf(HEART_RATE, STEPS, FLOORS, HRV) }

        assertEquals(listOf(HEART_RATE, STEPS, FLOORS, HRV), WearTilePreferences(prefs).chosen.value)
        assertEquals("HEART_RATE,STEPS,FLOORS,HRV", prefs.getString(WearTilePreferences.KEY_TILES, null))
    }

    @Test
    fun `every tile switched off stays off`() {
        WearTilePreferences(prefs).edit { emptyList() }

        assertEquals(emptyList<MetricTile>(), WearTilePreferences(prefs).chosen.value)
    }

    @Test
    fun `each change starts from the last one`() {
        val tiles = WearTilePreferences(prefs)

        tiles.edit { Choices.switched(it, FLOORS, on = true) }
        tiles.edit { Choices.moved(it, FLOORS, up = true) { true } }

        assertEquals(MetricTile.DefaultTiles.dropLast(1) + FLOORS + MetricTile.DefaultTiles.last(), tiles.chosen.value)
    }

    @Test
    fun `the tiles' names are stored, so none is ever renamed`() {
        // A new tile adds its name here; a name that changed would drop its tile from every stored choice.
        assertEquals(
            listOf(
                "STEPS", "HEART_RATE", "DISTANCE", "ACTIVE_CALORIES", "FLOORS", "ELEVATION", "BLOOD_OXYGEN", "VO2_MAX",
                "RESTING_HEART_RATE", "HRV", "SLEEP", "HYDRATION", "SKIN_TEMPERATURE", "RESPIRATORY_RATE", "WEIGHT",
            ),
            MetricTile.entries.map { it.name },
        )
    }

    @Test
    fun `a reset forgets the choice, so the defaults of a later build apply`() {
        val tiles = WearTilePreferences(prefs)
        tiles.edit { listOf(FLOORS) }

        tiles.reset()

        assertEquals(MetricTile.DefaultTiles, tiles.chosen.value)
        assertNull(prefs.getString(WearTilePreferences.KEY_TILES, null))
    }
}
