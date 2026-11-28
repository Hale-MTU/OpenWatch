package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

class SkinTemperatureRecorderTest {

    private val minute = 1_794_100_020_000L - 1_794_100_020_000L % 60_000L

    @Test
    fun `good points become one row per minute, the mean of each`() {
        val rows = SkinTemperatureRecorder.minutes(
            listOf(
                SkinReading(minute + 1_000, 34.0f, 22.0f, measured = true),
                SkinReading(minute + 31_000, 34.2f, 22.4f, measured = true),
                SkinReading(minute + 61_000, 33.9f, 22.1f, measured = true),
            ),
        )
        assertEquals(
            listOf(WearLinkProtocol.SkinMinute(minute, 3410, 2220), WearLinkProtocol.SkinMinute(minute + 60_000, 3390, 2210)),
            rows,
        )
    }

    @Test
    fun `a flagged, missing or impossible point is left out`() {
        val rows = SkinTemperatureRecorder.minutes(
            listOf(
                SkinReading(minute, 34.0f, 22.0f, measured = false),
                SkinReading(minute, Float.NaN, 22.0f, measured = true),
                SkinReading(minute, 12.0f, 22.0f, measured = true),
            ),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a skin-only minute, the Pixel channel, stamps the placeholder ambient and still round-trips`() {
        val rows = SkinTemperatureRecorder.minutes(
            listOf(
                SkinReading(minute + 1_000, 34.0f, Float.NaN, measured = true),
                SkinReading(minute + 31_000, 34.2f, Float.NaN, measured = true),
            ),
        )
        assertEquals(listOf(WearLinkProtocol.SkinMinute(minute, 3410, SkinTemperatureRecorder.PLACEHOLDER_AMBIENT100)), rows)
        // The wire line parses back to the same row: the placeholder keeps it in bounds.
        val line = WearLinkProtocol.formatSkinMinute(rows.single())
        assertEquals(rows.single(), WearLinkProtocol.parseSkinMinute(line))
    }

    @Test
    fun `a mixed minute averages only the real ambients, the missing one says nothing`() {
        val rows = SkinTemperatureRecorder.minutes(
            listOf(
                SkinReading(minute + 1_000, 34.0f, 22.0f, measured = true),
                SkinReading(minute + 31_000, 34.2f, Float.NaN, measured = true),
                SkinReading(minute + 41_000, 34.4f, 22.4f, measured = true),
            ),
        )
        assertEquals(listOf(WearLinkProtocol.SkinMinute(minute, 3420, 2220)), rows)
    }
}

class SkinMinuteBufferTest {

    private val minute = 1_794_100_020_000L - 1_794_100_020_000L % 60_000L

    @Test
    fun `a minute is written once it closes, the mean of points that came one by one`() {
        val buffer = SkinMinuteBuffer()
        assertTrue(buffer.add(listOf(SkinReading(minute + 1_000, 34.0f, Float.NaN, measured = true))).isEmpty())
        assertTrue(buffer.add(listOf(SkinReading(minute + 31_000, 34.2f, Float.NaN, measured = true))).isEmpty())
        val closed = buffer.add(listOf(SkinReading(minute + 61_000, 33.0f, Float.NaN, measured = true)))
        assertEquals(listOf(WearLinkProtocol.SkinMinute(minute, 3410, SkinTemperatureRecorder.PLACEHOLDER_AMBIENT100)), closed)
    }

    @Test
    fun `flush writes the open minute and empties the buffer`() {
        val buffer = SkinMinuteBuffer()
        buffer.add(listOf(SkinReading(minute + 1_000, 34.0f, Float.NaN, measured = true)))
        assertEquals(listOf(minute), buffer.flush(minute + 30_000).map { it.epochMillis })
        assertTrue(buffer.flush(minute + 30_000).isEmpty())
    }

    @Test
    fun `a batch of whole minutes closes all but its newest`() {
        val buffer = SkinMinuteBuffer()
        val closed = buffer.add(
            listOf(
                SkinReading(minute + 1_000, 34.0f, 22.0f, measured = true),
                SkinReading(minute + 61_000, 34.1f, 22.0f, measured = true),
                SkinReading(minute + 121_000, 34.2f, 22.0f, measured = true),
            ),
        )
        assertEquals(listOf(minute, minute + 60_000), closed.map { it.epochMillis })
    }

    private fun skin(at: Long, celsius: Float) = SkinReading(at, celsius, Float.NaN, measured = true)

    @Test
    fun `an on-change source's silent minutes repeat its last value until the next point`() {
        val buffer = SkinMinuteBuffer(holdLastMillis = 90 * 60_000L)
        buffer.add(listOf(skin(minute + 1_000, 34.0f), skin(minute + 31_000, 34.2f)))
        val closed = buffer.add(listOf(skin(minute + 3 * 60_000 + 1_000, 34.4f)))
        assertEquals(listOf(minute, minute + 60_000, minute + 120_000), closed.map { it.epochMillis })
        // The minute that had points is their mean; the silent ones hold the last point, not the mean.
        assertEquals(listOf(3410, 3420, 3420), closed.map { it.skin100 })
    }

    @Test
    fun `a silence longer than the hold limit is filled only up to the limit`() {
        val buffer = SkinMinuteBuffer(holdLastMillis = 30 * 60_000L)
        val written = buffer.add(listOf(skin(minute + 1_000, 34.0f))) +
            buffer.add(listOf(skin(minute + 40 * 60_000 + 1_000, 34.2f))) +
            buffer.add(listOf(skin(minute + 41 * 60_000 + 1_000, 34.4f)))
        // 30 minutes after the reading at 0:01 the value no longer stands: 0 to 30, then 40.
        assertEquals((0..30).map { minute + it * 60_000L } + (minute + 40 * 60_000), written.map { it.epochMillis })
    }

    @Test
    fun `the held minutes are written as time passes, not only when the next point comes`() {
        val buffer = SkinMinuteBuffer(holdLastMillis = 90 * 60_000L)
        buffer.add(listOf(skin(minute + 1_000, 34.0f)))
        // A phone syncing at 0:10 finds the minutes up to 0:09 already there.
        assertEquals((0..9).map { minute + it * 60_000L }, buffer.tick(minute + 10 * 60_000 + 5_000).map { it.epochMillis })
        assertTrue(buffer.tick(minute + 10 * 60_000 + 30_000).isEmpty())
        // The value is kept: the next point fills on from where the tick stopped.
        val next = buffer.add(listOf(skin(minute + 20 * 60_000 + 1_000, 34.2f)))
        assertEquals((10..19).map { minute + it * 60_000L }, next.map { it.epochMillis })
        assertEquals(setOf(3400), next.map { it.skin100 }.toSet())
    }

    @Test
    fun `a point the sensor does not stand behind ends the hold`() {
        val buffer = SkinMinuteBuffer(holdLastMillis = 90 * 60_000L)
        val written = buffer.add(listOf(skin(minute + 1_000, 34.0f))) +
            buffer.add(listOf(SkinReading(minute + 5 * 60_000 + 1_000, 33.0f, Float.NaN, measured = false))) +
            buffer.add(listOf(skin(minute + 20 * 60_000 + 1_000, 34.2f))) +
            buffer.add(listOf(skin(minute + 21 * 60_000 + 1_000, 34.4f)))
        // Held up to the poor-contact point at 0:05, nothing from there until the next good one.
        assertEquals((0..4).map { minute + it * 60_000L } + (minute + 20 * 60_000), written.map { it.epochMillis })
    }

    @Test
    fun `an impossible point ends the hold too, and the limit counts from the last real reading`() {
        val buffer = SkinMinuteBuffer(holdLastMillis = 60 * 60_000L)
        val written = listOf(0, 60, 120, 121).flatMap { at ->
            buffer.add(listOf(skin(minute + at * 60_000L + 1_000, if (at == 60 || at == 120) 19.5f else 34.0f)))
        } + buffer.tick(minute + 130 * 60_000L)
        // 19.5 °C is off the wrist: no row, and the 34.0 before it is not carried across it.
        assertEquals((0..59).map { minute + it * 60_000L } + (121..129).map { minute + it * 60_000L }, written.map { it.epochMillis })
    }

    @Test
    fun `flush holds the value up to the stop and then forgets it`() {
        val buffer = SkinMinuteBuffer(holdLastMillis = 90 * 60_000L)
        buffer.add(listOf(skin(minute + 1_000, 34.0f)))
        val flushed = buffer.flush(minute + 4 * 60_000 + 5_000)
        assertEquals(listOf(minute, minute + 60_000, minute + 120_000, minute + 180_000), flushed.map { it.epochMillis })
        // Off the wrist in between: nothing is held across the stop.
        buffer.add(listOf(skin(minute + 20 * 60_000 + 1_000, 34.2f)))
        val closed = buffer.add(listOf(skin(minute + 21 * 60_000 + 1_000, 34.4f)))
        assertEquals(listOf(minute + 20 * 60_000), closed.map { it.epochMillis })
    }

    @Test
    fun `a batching source without a hold limit fills nothing`() {
        val buffer = SkinMinuteBuffer()
        buffer.add(listOf(skin(minute + 1_000, 34.0f)))
        val closed = buffer.add(listOf(skin(minute + 5 * 60_000 + 1_000, 34.2f)))
        assertEquals(listOf(minute), closed.map { it.epochMillis })
        assertEquals(listOf(minute + 5 * 60_000), buffer.flush(minute + 9 * 60_000).map { it.epochMillis })
    }
}
