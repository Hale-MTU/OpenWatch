package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorClaimTest {

    @Test
    fun `one holds the sensor until it lets go, and the other waits`() {
        val claim = SensorClaim()
        assertTrue(claim.take("night"))
        assertTrue(claim.take("night"))
        assertFalse(claim.take("spot"))
        assertEquals("night", claim.holder)

        // Only the holder lets go.
        claim.release("spot")
        assertEquals("night", claim.holder)
        claim.release("night")
        assertNull(claim.holder)
        assertTrue(claim.take("spot"))
    }
}
