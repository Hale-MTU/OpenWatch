package tech.mmarca.openwatch.devices.blestandard

/**
 * The few Record Access Control Point (characteristic 0x2A52) messages a collector needs to have
 * a glucose meter hand over what it stored, as the Glucose Profile lays them out: an op code, an
 * operator, and an operand that depends on both. The meter answers a request by notifying the
 * records, then indicates a Response Code: op code 6, operator 0 (null), the request's op code
 * and the outcome.
 */
public object RecordAccessControlPoint {
    public const val OP_REPORT_STORED_RECORDS: Int = 0x01
    public const val OP_RESPONSE_CODE: Int = 0x06
    private const val OPERATOR_NULL = 0x00
    private const val OPERATOR_ALL_RECORDS = 0x01
    private const val OPERATOR_GREATER_OR_EQUAL = 0x03
    private const val FILTER_SEQUENCE_NUMBER = 0x01

    public const val RESPONSE_SUCCESS: Int = 0x01
    public const val RESPONSE_NO_RECORDS_FOUND: Int = 0x06

    /** "Report stored records: all records". */
    public fun reportAll(): ByteArray = bytes(OP_REPORT_STORED_RECORDS, OPERATOR_ALL_RECORDS)

    /** "Report stored records: sequence number greater than or equal to [sequenceNumber]". */
    public fun reportFrom(sequenceNumber: Int): ByteArray = bytes(
        OP_REPORT_STORED_RECORDS,
        OPERATOR_GREATER_OR_EQUAL,
        FILTER_SEQUENCE_NUMBER,
        sequenceNumber and 0xFF,
        (sequenceNumber shr 8) and 0xFF,
    )

    /** The outcome a Response Code to a report request carries, or null for any other indication. */
    public fun reportResponse(value: ByteArray): Int? {
        val fields = GattFields(value)
        if (fields.uint8() != OP_RESPONSE_CODE || fields.uint8() != OPERATOR_NULL) return null
        if (fields.uint8() != OP_REPORT_STORED_RECORDS) return null
        return fields.uint8()
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
