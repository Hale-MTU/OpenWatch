package tech.mmarca.openwatch.devices.garmin

import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One protobuf exchange with the watch, over GFDI messages 5043/5044. The
 * envelope is `[u16 requestId][u32 dataOffset][u32 totalLength]
 * [u32 chunkLength][bytes]`; replies are matched by request id.
 *
 * A message over [MAX_CHUNK_SIZE] goes in chunks, both ways. One coming in is
 * held by offset until complete, each chunk acknowledged at its own offset.
 * One going out is sent a chunk at a time: the next once the watch accepts
 * the last at its offset. A vívoactive 5 answers a frame past its packet
 * size with a NAK for message 65535 and asks again a minute later, so an
 * oversized message was never just slow, it was lost.
 */
public class GarminProtobufTransport(
    /** Hands a built GFDI frame to the layer below. */
    private val send: suspend (ByteArray) -> Unit,
    /** Called with a message that answers no outstanding request. */
    public var onUnsolicited: ((ByteArray) -> Unit)? = null,
    /** A watch-initiated request, with the id [respond] must use. */
    public var onServiceRequest: ((requestId: Int, payload: ByteArray) -> Unit)? = null,
) {

    public class RejectedException(message: String) : Exception(message)

    public companion object {
        /** The largest payload the watch accepts in one message: a chunk's size, both ways. */
        public const val MAX_CHUNK_SIZE: Int = 375

        /** How long to wait for a reply. An unanswered request must not leave a spinner forever. */
        public val REPLY_TIMEOUT: Duration = 10.seconds
    }

    private var lastRequestId = 0

    /**
     * Whether complete messages get the extended request-id ACK. The
     * vívoactive 5 retransmits without it. A watch that NAKs
     * it with LENGTH_ERROR (Instinct 2X) gets the generic form from then on.
     */
    private var extendedAcks = true
    public val usesExtendedAcks: Boolean get() = extendedAcks
    private val pending = mutableMapOf<Int, CompletableDeferred<ByteArray>>()
    private val unmatchedMatchers = mutableMapOf<Int, (ByteArray) -> Boolean>()

    /**
     * Chunks in flight: request id to offset to bytes. Keyed by offset
     * because the watch retransmits chunks it thinks were lost.
     */
    private val incoming = mutableMapOf<Int, MutableMap<Long, ByteArray>>()

    /** A message going out in chunks, and the offset of the chunk the watch has yet to accept. */
    private class Outgoing(val payload: ByteArray) {
        var sentOffset = 0
    }

    /**
     * Messages going out in chunks, by message type and request id: a response carries the
     * watch's id and a request ours, so the id alone could name two.
     */
    private val outgoing = mutableMapOf<Pair<Int, Int>, Outgoing>()

    /** Sends [payload] as a `Smart` message. Returns the reply, or null on timeout. */
    public suspend fun request(
        payload: ByteArray,
        label: String? = null,
        timeout: Duration? = null,
        acceptUnmatched: ((ByteArray) -> Boolean)? = null,
    ): ByteArray? {
        val requestId = nextRequestId()
        val deferred = CompletableDeferred<ByteArray>()
        pending[requestId] = deferred
        if (acceptUnmatched != null) unmatchedMatchers[requestId] = acceptUnmatched

        GarminLog.log(
            "[GARMIN-PB] → ${label ?: "request"} #$requestId (${payload.size}B)",
        )
        try {
            sendMessage(GarminMessageId.PROTOBUF_REQUEST, requestId, payload)
            val reply = withTimeoutOrNull(timeout ?: REPLY_TIMEOUT) { deferred.await() }
            if (reply == null) {
                GarminLog.log(
                    "[GARMIN-PB] ✗ no reply to #$requestId within " +
                        "${(timeout ?: REPLY_TIMEOUT).inWholeSeconds}s",
                )
            }
            return reply
        } finally {
            pending.remove(requestId)
            unmatchedMatchers.remove(requestId)
            incoming.remove(requestId)
            outgoing.remove(GarminMessageId.PROTOBUF_REQUEST to requestId)
        }
    }

    public suspend fun sendUnanswered(payload: ByteArray, label: String? = null) {
        val requestId = nextRequestId()
        GarminLog.log(
            "[GARMIN-PB] → ${label ?: "request"} #$requestId (${payload.size}B, no wait)",
        )
        sendMessage(GarminMessageId.PROTOBUF_REQUEST, requestId, payload)
    }

    /**
     * Feeds an inbound protobuf message in. Returns true when consumed. The
     * watch sends requests of its own too.
     */
    public suspend fun handleInbound(frame: GarminGfdiFrame): Boolean {
        if (frame.messageType != GarminMessageId.PROTOBUF_REQUEST &&
            frame.messageType != GarminMessageId.PROTOBUF_RESPONSE
        ) {
            return false
        }
        val payload = frame.payload
        if (payload.size < 14) {
            // Malformed, but still ours: ack it or the watch repeats it.
            send(buildGenericAck(frame.messageType))
            return true
        }
        val reader = GarminByteReader(payload)
        val requestId = reader.readShort()
        val dataOffset = reader.readInt()
        val totalLength = reader.readInt()
        val chunkLength = reader.readInt()
        if (payload.size < 14 + chunkLength) {
            send(buildGenericAck(frame.messageType))
            return true
        }
        val bytes = payload.copyOfRange(14, 14 + chunkLength.toInt())

        // Accumulate by id alone, not by whether we wait for it: the watch
        // answers settings requests under its own id.
        if (totalLength != chunkLength || dataOffset != 0L) {
            val chunks = incoming.getOrPut(requestId) { mutableMapOf() }
            chunks[dataOffset] = bytes
            // A chunk needs an ack that names its received offset, not the next
            // one, or the watch resends chunk zero forever.
            send(
                buildProtobufAck(
                    originalMessageType = frame.messageType,
                    requestId = requestId,
                    dataOffset = dataOffset,
                ),
            )
            val held = chunks.values.sumOf { it.size.toLong() }
            if (held < totalLength) {
                GarminLog.log("[GARMIN-PB] ← #$requestId chunk $held/$totalLength B")
                return true
            }
            // Assembled in offset order, so a late retransmission lands where it belongs.
            val assembled = ByteArrayOutputStream()
            for (offset in chunks.keys.sorted()) {
                val chunk = chunks.getValue(offset)
                assembled.write(chunk, 0, chunk.size)
            }
            incoming.remove(requestId)
            deliver(requestId, assembled.toByteArray())
            return true
        }

        // A complete message: extended ACK unless this watch rejected it.
        send(
            if (extendedAcks) {
                buildProtobufAck(
                    originalMessageType = frame.messageType,
                    requestId = requestId,
                    dataOffset = 0,
                )
            } else {
                buildGenericAck(frame.messageType)
            },
        )
        deliver(requestId, bytes)
        return true
    }

    /** The watch NAKed one of our RESPONSE frames. LENGTH_ERROR means it wants generic ACKs. */
    public fun handleAckRejected(status: GarminStatus) {
        if (!extendedAcks || status != GarminStatus.LENGTH_ERROR) return
        extendedAcks = false
        GarminLog.log("[GARMIN-PB] watch rejects extended acks; using generic")
    }

    public suspend fun handleStatus(status: GarminProtobufStatus) {
        val key = status.requestId?.let { status.originalMessageType to it }
        if (status.accepted) {
            key?.let { sendNextChunk(it, status.dataOffset) }
            return
        }
        key?.let(outgoing::remove)
        val detail = buildString {
            append(status.status.name)
            status.errorCode?.let { append(" protobufError=").append(it) }
            status.dataOffset?.let { append(" offset=").append(it) }
        }
        GarminLog.log(
            "[GARMIN-PB] rejected #${status.requestId ?: "?"}: $detail",
        )
        status.requestId?.let { requestId ->
            pending[requestId]?.completeExceptionally(RejectedException(detail))
        }
    }

    /** Hands a complete message to its waiter, or to the unsolicited hook. */
    private fun deliver(requestId: Int, bytes: ByteArray) {
        val deferred = pending[requestId]
        if (deferred != null) {
            GarminLog.log("[GARMIN-PB] ← #$requestId (${bytes.size}B)")
            deferred.complete(bytes)
            return
        }
        val flexible = unmatchedMatchers.entries.firstOrNull { (_, matches) ->
            runCatching { matches(bytes) }.getOrDefault(false)
        }
        if (flexible != null) {
            pending[flexible.key]?.complete(bytes)
            return
        }
        // Not an answer to anything outstanding: the watch started this, or answered under its own id.
        // Service and message field numbers only: what the watch wants, never the payload.
        val services = readProtobuf(bytes).map { service ->
            val inner = service.bytes?.let { readProtobuf(it).map { field -> field.field }.distinct() }
            if (inner == null) "${service.field}" else "${service.field}:$inner"
        }.distinct()
        GarminLog.log(
            "[GARMIN-PB] ← unsolicited #$requestId (${bytes.size}B) services=$services",
        )
        onServiceRequest?.invoke(requestId, bytes)
        onUnsolicited?.invoke(bytes)
    }

    /** Answers a watch-initiated request under the id the watch chose. */
    public suspend fun respond(requestId: Int, payload: ByteArray) {
        GarminLog.log("[GARMIN-PB] → response #$requestId (${payload.size}B)")
        sendMessage(GarminMessageId.PROTOBUF_RESPONSE, requestId, payload)
    }

    /** [payload] whole, or its first chunk when it is over [MAX_CHUNK_SIZE]; the rest follow the acks. */
    private suspend fun sendMessage(messageType: Int, requestId: Int, payload: ByteArray) {
        if (payload.size <= MAX_CHUNK_SIZE) {
            send(frame(messageType, requestId, payload))
            return
        }
        outgoing[messageType to requestId] = Outgoing(payload)
        val chunks = (payload.size + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE
        GarminLog.log("[GARMIN-PB] → #$requestId in $chunks chunks")
        send(chunkFrame(messageType, requestId, payload, offset = 0))
    }

    /**
     * The watch accepted a chunk at [ackedOffset]: the next goes, or the message is done. An
     * ack for any other offset, a late repeat, moves nothing. A short ack that names no
     * offset is taken for the chunk last sent.
     */
    private suspend fun sendNextChunk(key: Pair<Int, Int>, ackedOffset: Long?) {
        val message = outgoing[key] ?: return
        if (ackedOffset != null && ackedOffset != message.sentOffset.toLong()) return
        val next = message.sentOffset + MAX_CHUNK_SIZE
        if (next >= message.payload.size) {
            outgoing.remove(key)
            return
        }
        message.sentOffset = next
        send(chunkFrame(key.first, key.second, message.payload, offset = next))
    }

    /** Fails every outstanding request, so a dropped link does not wait out the timeout. */
    public fun abort() {
        for (deferred in pending.values) {
            deferred.completeExceptionally(IllegalStateException("link closed"))
        }
        pending.clear()
        unmatchedMatchers.clear()
        incoming.clear()
        outgoing.clear()
    }

    private fun nextRequestId(): Int {
        lastRequestId = (lastRequestId + 1) % 65536
        return lastRequestId
    }

    private fun frame(messageType: Int, requestId: Int, payload: ByteArray): ByteArray =
        chunkFrame(messageType, requestId, payload, offset = 0)

    /** The chunk of [payload] at [offset], at most [MAX_CHUNK_SIZE] long, in its envelope. */
    private fun chunkFrame(messageType: Int, requestId: Int, payload: ByteArray, offset: Int): ByteArray {
        val end = minOf(offset + MAX_CHUNK_SIZE, payload.size)
        val writer = GarminByteWriter()
            .writeShort(requestId)
            .writeInt(offset)
            .writeInt(payload.size) // total
            .writeInt(end - offset) // this chunk
            .writeBytes(payload.copyOfRange(offset, end))
        return GarminGfdiFrame.build(messageType, writer.toBytes())
    }
}

/** What the watch said about a find request. */
public enum class GarminFindOutcome {
    /** It answered OK. */
    OK,

    /** It answered ERROR, the only reading that means it declined. */
    ERROR,

    /** Unrecognised or no answer. Treated as "probably ringing". */
    UNKNOWN,
    ;

    public val declined: Boolean get() = this == ERROR
}

/**
 * The battery half of Garmin's `DeviceStatusService`, the only place the
 * watch reports a percentage. The GFDI message is just good/ok/low.
 */
public object GarminDeviceStatus {

    private const val BATTERY_REQUEST = 2
    private const val BATTERY_RESPONSE = 3
    private const val LEVEL = 2

    /** Asks for the current battery level (the request message is empty). */
    public fun batteryRequest(): ByteArray {
        val service = ProtobufWriter().emptyMessage(BATTERY_REQUEST).toBytes()
        return ProtobufWriter().nested(GarminSmartService.DEVICE_STATUS, service).toBytes()
    }

    /** The percentage in [reply], or null. */
    public fun batteryLevel(reply: ByteArray?): Int? {
        if (reply == null || reply.isEmpty()) return null
        val service = protobufField(
            readProtobuf(reply),
            GarminSmartService.DEVICE_STATUS,
        )?.bytes ?: return null
        val response = protobufField(readProtobuf(service), BATTERY_RESPONSE)?.bytes
            ?: return null
        val level = protobufField(readProtobuf(response), LEVEL)?.varint ?: return null
        return level.toInt().takeIf { it in 0..100 }
    }
}

public object GarminFindMyWatch {

    private const val FIND_REQUEST = 1
    private const val FIND_RESPONSE = 2
    private const val CANCEL_REQUEST = 3
    private const val CANCEL_RESPONSE = 4
    private const val TIMEOUT = 1
    private const val STATUS = 1

    /** Long enough to find a watch, short enough to stop itself. */
    public val defaultTimeout: Duration = 60.seconds

    public fun start(timeout: Duration = defaultTimeout): ByteArray {
        val request = ProtobufWriter().varint(TIMEOUT, timeout.inWholeSeconds).toBytes()
        val service = ProtobufWriter().nested(FIND_REQUEST, request).toBytes()
        return ProtobufWriter().nested(GarminSmartService.FIND_MY_WATCH, service).toBytes()
    }

    public fun cancel(): ByteArray {
        val service = ProtobufWriter().emptyMessage(CANCEL_REQUEST).toBytes()
        return ProtobufWriter().nested(GarminSmartService.FIND_MY_WATCH, service).toBytes()
    }

    /** Whether [payload] is the watch reporting on a find. */
    public fun isFindMessage(payload: ByteArray): Boolean =
        protobufField(readProtobuf(payload), GarminSmartService.FIND_MY_WATCH) != null

    /**
     * What a reply says about the request. Only an explicit ERROR means the
     * watch declined; an unparsed reply was seen while the watch rang.
     */
    public fun outcome(reply: ByteArray?): GarminFindOutcome {
        if (reply == null || reply.isEmpty()) return GarminFindOutcome.UNKNOWN
        val service = protobufField(
            readProtobuf(reply),
            GarminSmartService.FIND_MY_WATCH,
        )
        val bytes = service?.bytes ?: return GarminFindOutcome.UNKNOWN
        val fields = readProtobuf(bytes)
        // A find is answered in field 2, a cancel in field 4; either is an answer.
        for (field in intArrayOf(FIND_RESPONSE, CANCEL_RESPONSE)) {
            val response = protobufField(fields, field)?.bytes ?: continue
            val status = protobufField(readProtobuf(response), STATUS)?.varint
            // A real watch answers with no status field. OK is 100 and ERROR is
            // 200, so a missing status must never read as zero.
            if (status == null || status == 100L) return GarminFindOutcome.OK
            if (status == 200L) return GarminFindOutcome.ERROR
            return GarminFindOutcome.UNKNOWN
        }
        return GarminFindOutcome.UNKNOWN
    }
}
