package tech.mmarca.openwatch.devices.xiaomi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RFC 3610, packet vector 1: the cipher is right in general, not just for the scale's sizes. */
class AesCcmTest {

    private val key = ByteArray(16) { (0xC0 + it).toByte() }
    private val nonce = "00000003020100A0A1A2A3A4A5".hexToByteArray()
    private val header = ByteArray(8) { it.toByte() }
    private val ciphertext = "588C979A61C663D2F066D0C2C0F989806D5F6B61DAC384".hexToByteArray()
    private val tag = "17E8D12CFDF926E0".hexToByteArray()

    @Test
    fun `the published vector decrypts to its plaintext`() {
        assertArrayEquals(
            ByteArray(23) { (0x08 + it).toByte() },
            AesCcm.decrypt(key, nonce, header, ciphertext, tag),
        )
    }

    @Test
    fun `a change to the tag, the ciphertext or the header is refused`() {
        fun ByteArray.withFirstBitFlipped() = copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertNull(AesCcm.decrypt(key, nonce, header, ciphertext, tag.withFirstBitFlipped()))
        assertNull(AesCcm.decrypt(key, nonce, header, ciphertext.withFirstBitFlipped(), tag))
        assertNull(AesCcm.decrypt(key, nonce, header.withFirstBitFlipped(), ciphertext, tag))
    }

    @Test
    fun `the second published vector, a whole block longer, decrypts too`() {
        assertArrayEquals(
            ByteArray(24) { (0x08 + it).toByte() },
            AesCcm.decrypt(
                key,
                "00000004030201A0A1A2A3A4A5".hexToByteArray(),
                header,
                "72C91A36E135F8CF291CA894085C87E3CC15C439C9E43A3B".hexToByteArray(),
                "A091D56E10400916".hexToByteArray(),
            ),
        )
    }

    @Test
    fun `sizes CCM does not define are refused, an empty tag first`() {
        assertNull(AesCcm.decrypt(key, nonce, header, ciphertext, ByteArray(0)))
        assertNull(AesCcm.decrypt(key, nonce, header, ciphertext, tag.copyOf(3)))
        assertNull(AesCcm.decrypt(key, nonce, header, ciphertext, tag.copyOf(7)))
        assertNull(AesCcm.decrypt(key, ByteArray(6), header, ciphertext, tag))
        assertNull(AesCcm.decrypt(key, ByteArray(14), header, ciphertext, tag))
    }
}
