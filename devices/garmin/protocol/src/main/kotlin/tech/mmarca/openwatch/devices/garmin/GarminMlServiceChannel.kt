package tech.mmarca.openwatch.devices.garmin

/** A non-GFDI service opened on the ML transport for one job. Lives only inside [useServiceChannel]. */
public interface GarminServiceChannel {
    public val serviceCode: Int

    public suspend fun send(payload: ByteArray)
}

/** The first of [codes] with no handle, or null when the watch has every one open. */
public fun GarminMlTransport.freeService(codes: IntArray): Int? = codes.firstOrNull { !isServiceOpen(it) }

/**
 * Opens [serviceCode], runs [block] on it, then closes it and drops the
 * handlers whatever happened. A transfer that fails halfway cannot leave a
 * handle registered or a handler pointing at dead state.
 */
public suspend fun <T> GarminMlTransport.useServiceChannel(
    serviceCode: Int,
    reliable: Boolean = false,
    onData: (ByteArray) -> Unit,
    onClosed: () -> Unit,
    block: suspend (GarminServiceChannel) -> T,
): T {
    setServiceHandler(serviceCode, onData, onClosed)
    try {
        openService(serviceCode, reliable)
        val channel = object : GarminServiceChannel {
            override val serviceCode: Int = serviceCode

            override suspend fun send(payload: ByteArray) = sendServiceData(serviceCode, payload)
        }
        return block(channel)
    } finally {
        // Teardown that must finish: cancellation is swallowed here on purpose.
        runCatching { closeService(serviceCode) }
        clearServiceHandler(serviceCode)
    }
}
