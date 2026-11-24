package tech.mmarca.openwatch.wear.samsung

import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The recorded callbacks ([RecordedCallbacks]) on a watch: read through the
 * watch's own [Parcel], and compared with what its framework writes for the
 * same points, so the recording is known to be the layout the platform sends.
 */
@RunWith(AndroidJUnit4::class)
class SamsungRecordedParcelDeviceTest {

    @Test
    fun recordedCallbacksReadThroughTheWatchsParcel() {
        for (callback in RecordedCallbacks.ALL) {
            val bytes = callback.bytes
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)

                val point = SamsungHealthPlatform.readPoints(parcel).single()

                assertEquals(callback.epochMillis, point.epochMillis)
                assertEquals(callback.ints, point.ints.toList())
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test
    fun thisWatchWritesTheRecordedLayout() {
        // Android 13 put a length before each value of a written list.
        val recorded = if (Build.VERSION.SDK_INT >= 33) RecordedCallbacks.HEART_RATE_API_33 else RecordedCallbacks.HEART_RATE_API_30
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(1) // one point
            parcel.writeInt(1) // present
            parcel.writeLong(recorded.epochMillis)
            parcel.writeList(recorded.ints.map(::RecordedValue))

            assertArrayEquals(recorded.bytes, parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }
}

/** A value as the platform writes one: an int, a float and a long. Its class name is [RecordedCallbacks.VALUE_CLASS]. */
class RecordedValue(private val value: Int) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(value)
        dest.writeFloat(0f)
        dest.writeLong(0L)
    }

    companion object CREATOR : Parcelable.Creator<RecordedValue> {
        override fun createFromParcel(source: Parcel): RecordedValue = RecordedValue(source.readInt()).also {
            source.readFloat()
            source.readLong()
        }

        override fun newArray(size: Int): Array<RecordedValue?> = arrayOfNulls(size)
    }
}
