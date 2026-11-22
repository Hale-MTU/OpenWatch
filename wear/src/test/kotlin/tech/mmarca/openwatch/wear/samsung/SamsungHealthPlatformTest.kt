package tech.mmarca.openwatch.wear.samsung

import android.content.pm.ApplicationInfo
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SamsungHealthPlatformTest {

    @Test
    fun `only a service the system image ships is bound`() {
        assertTrue(SamsungHealthPlatform.isSystemApp(ApplicationInfo.FLAG_SYSTEM))
        assertTrue(SamsungHealthPlatform.isSystemApp(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
        // A store app that took the package name on another watch.
        assertFalse(SamsungHealthPlatform.isSystemApp(ApplicationInfo.FLAG_HAS_CODE))
    }

    @Test
    fun `a callback that claims an absurd count fails the read, not the allocation`() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(Int.MAX_VALUE)
            parcel.setDataPosition(0)

            val read = runCatching { SamsungHealthPlatform.readPoints(parcel) }

            assertTrue(read.exceptionOrNull() is IllegalStateException)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `a point that claims too many values fails the read too`() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(1) // one point
            parcel.writeInt(1) // present
            parcel.writeLong(1_000L)
            parcel.writeInt(SamsungHealthPlatform.MAX_VALUES + 1)
            parcel.setDataPosition(0)

            assertTrue(runCatching { SamsungHealthPlatform.readPoints(parcel) }.exceptionOrNull() is IllegalStateException)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `an empty callback reads as no points`() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(0)
            parcel.setDataPosition(0)

            assertEquals(0, SamsungHealthPlatform.readPoints(parcel).size)
        } finally {
            parcel.recycle()
        }
    }
}
