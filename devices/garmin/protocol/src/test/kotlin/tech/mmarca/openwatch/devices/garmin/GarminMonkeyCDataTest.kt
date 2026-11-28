package tech.mmarca.openwatch.devices.garmin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Monkey C's serialised values, against bytes Gadgetbridge recorded from watches. */
class GarminMonkeyCDataTest {

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** A Home Assistant glance's request: a dictionary inside a dictionary. */
    private val glance = hex(
        "abcdabcd000000710005646174610000057479706500001072656e6465725f74656d706c61746500000f676c616e6365" +
            "54656d706c61746500000974656d706c6174650000337b7b2073746174657328" +
            "2773656e736f722e736f6c61726e65745f706f7765725f677269645f696d706f" +
            "72742729207d7d2500da7ada7a0000002d0b0000000203000000000b00000001" +
            "0300000007030000000e03000000200b000000010300000031030000003c",
    )

    /** `Communications.transmit` of one value of every type, from a test app on a watch. */
    private val everyType = hex(
        "ABCDABCD00000159000B4172726179456D7074790000084E756D6265723600000A446F75626C654D617800000A446F75626C654D696E00000544656570000006436861724100000B43686172547572746C65000008446F75626C653600000B4279746541727261793100000A4E756D6265724D617800000B4279746541727261793600000C537472696E674D6978656400000836E282ACE9BE9F000008537472696E673600000236000009466C6F61744D6178000009466C6F61744D696E0000084C6F6E674D61780000084C6F6E674D696E00000A44696374456D70747900000C426F6F6C65616E5472756500000F427974654172726179456D70747900000D426F6F6C65616E46616C736500000C537472696E67456D707479000001000007466C6F61743600000A4E756D6265724D696E0000064C6F6E673600000B537472696E674575726F000004E282AC0000094469637453776170000002620000026300DA7ADA7A0000018A0B0000001B03000000000500000000030000000D010000000603000000170F7FEFFFFFFFFFFFFF03000000230F0010000000000000030000002F050000000203000000361300000061030000003E1300009F9F030000004B0F401800000000000003000000551400000001010300000062017FFFFFFF030000006E140000000680FF0001027F030000007B03000000890300000093030000009D03000000A1027F7FFFFF03000000AC020000000103000000B70E7FFFFFFFFFFFFFFF03000000C10E800000000000000003000000CB0B0000000003000000D7090103000000E5140000000003000000F609000300000105030000011303000001160240C00000030000011F0180000000030000012B0E00000000000000060300000133030000014003000001460B0000000201000000010B00000001010000000C010000000D010000000D010000000C0300000151050000000202400000000B000000030E0000000000000006130000003803000001550E0000000000000003090105000000010F4010000000000000",
    )

    @Test
    fun `reads nested dictionaries breadth first`() {
        val root = GarminMonkeyCData.decode(glance) as JSONObject

        assertEquals("render_template", root.getString("type"))
        assertEquals(
            "{{ states('sensor.solarnet_power_grid_import') }}%",
            root.getJSONObject("data").getJSONObject("glanceTemplate").getString("template"),
        )
    }

    @Test
    fun `reads every type an app can send`() {
        val root = GarminMonkeyCData.decode(everyType) as JSONObject

        assertEquals(6, root.get("Number6"))
        assertEquals(Int.MAX_VALUE, root.get("NumberMax"))
        assertEquals(Int.MIN_VALUE, root.get("NumberMin"))
        assertEquals(6L, root.get("Long6"))
        assertEquals(Long.MIN_VALUE, root.get("LongMin"))
        assertEquals(6.0, root.get("Float6"))
        assertEquals(6.0, root.get("Double6"))
        assertEquals("6€龟", root.get("StringMixed"))
        assertEquals("", root.get("StringEmpty"))
        assertEquals(true, root.get("BooleanTrue"))
        assertEquals(false, root.get("BooleanFalse"))
        assertEquals("龟", root.get("CharTurtle"))
        assertEquals(listOf(-128, -1, 0, 1, 2, 127), root.getJSONArray("ByteArray6").toList())
        assertEquals(0, root.getJSONObject("DictEmpty").length())
        // A dictionary keyed by numbers reads with the numbers' text as its keys.
        assertEquals(13, root.getJSONObject("DictSwap").get("12"))
    }

    @Test
    fun `what it writes reads back the same`() {
        val drink = JSONObject()
            .put("timestampLocal", "2026-11-28T10:15:30.0")
            .put("valueInML", 250.5)
            .put("nothing", JSONObject.NULL)
            .put("containers", JSONArray().put(JSONObject().put("volume", 500).put("unit", "milliliter")).put("milliliter"))

        val back = GarminMonkeyCData.decode(GarminMonkeyCData.encode(drink)) as JSONObject

        assertEquals("2026-11-28T10:15:30.0", back.getString("timestampLocal"))
        assertEquals(250.5, back.getDouble("valueInML"), 0.0)
        assertEquals(JSONObject.NULL, back.get("nothing"))
        assertEquals(500, back.getJSONArray("containers").getJSONObject(0).get("volume"))
        assertEquals("milliliter", back.getJSONArray("containers").getString(1))
    }

    @Test
    fun `a whole number goes as an integer, a fraction as a float only where one holds it`() {
        fun typeOf(value: Any): Int {
            val bytes = GarminMonkeyCData.encode(value)
            // No strings: the data section's magic and length, then the value's type.
            return bytes[8].toInt()
        }

        assertEquals(0x01, typeOf(2000.0))
        assertEquals(0x0e, typeOf(5_000_000_000L))
        assertEquals(0x02, typeOf(250.5))
        assertEquals(0x0f, typeOf(0.1))
    }

    @Test
    fun `bytes that are not a serialised value are refused`() {
        assertThrows(IllegalArgumentException::class.java) { GarminMonkeyCData.decode(hex("00112233445566778899")) }
        assertThrows(IllegalArgumentException::class.java) { GarminMonkeyCData.decode(glance.copyOf(40)) }
    }

    private fun JSONArray.toList(): List<Any?> = (0 until length()).map { get(it) }
}
