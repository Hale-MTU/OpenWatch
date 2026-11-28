package tech.mmarca.openwatch.devices.garmin

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.json.JSONTokener

/**
 * One request the watch made through Garmin's HTTP-proxy service. Modern
 * watches treat the phone as their internet. Nothing here reaches a
 * network: every request is answered by an interceptor or refused.
 *
 * Two shapes arrive the same way: the firmware's raw requests, and the web
 * requests of a Connect IQ app, whose body and headers are Monkey C values
 * ([GarminMonkeyCData]); the proxy hands the latter on with a JSON body.
 */
public data class GarminHttpRequest(
    val url: String,
    val domain: String,
    val path: String,
    val query: Map<String, String>,
    /** Garmin's method enum ([GarminHttpMethod]). Absent on plain GETs. */
    val method: Int?,
    /** Header names lowercased, since the watch is inconsistent about case. */
    val headers: Map<String, String>,
    val body: ByteArray?,
    /** The watch asked for the body over the data-transfer service instead. */
    val useDataTransfer: Boolean,
)

/** Garmin's HTTP method enum, the same in both request shapes. */
public object GarminHttpMethod {
    public const val GET: Int = 1
    public const val PUT: Int = 2
    public const val POST: Int = 3
}

/** What to answer with. [onSent] fires once the watch has the bytes, the last chunk included. */
public data class GarminHttpResponse(
    val status: Int = 200,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    val onSent: (() -> Unit)? = null,
)

/** Answers one family of requests: [supports] claims by URL, [handle] answers or returns null. */
public interface GarminHttpInterceptor {
    public fun supports(request: GarminHttpRequest): Boolean
    public fun handle(request: GarminHttpRequest): GarminHttpResponse?
}

/**
 * The HTTP-proxy plumbing: unwrap the protobuf, run the interceptors, frame
 * the reply inline, gzipped, or via the data-transfer service.
 */
public class GarminHttpProxy(private val interceptors: List<GarminHttpInterceptor>) {

    // Protobuf field numbers (gdi_http_service / gdi_data_transfer).
    private companion object {
        const val CIQ_REQUEST = 1
        const val CIQ_RESPONSE = 2
        const val RAW_REQUEST = 5
        const val RAW_RESPONSE = 6
        const val REQ_URL = 1
        const val REQ_METHOD = 3
        const val REQ_BODY = 4
        const val REQ_HEADER = 5
        const val REQ_USE_DATA_XFER = 6
        const val REQ_RAW_BODY = 7

        const val RESP_STATUS = 1
        const val RESP_HTTP_STATUS = 2
        const val RESP_BODY = 3
        const val RESP_XFER_DATA = 4
        const val RESP_HEADER = 5
        const val HEADER_KEY = 1
        const val HEADER_VALUE = 2
        const val XFER_ID = 1
        const val XFER_SIZE = 2

        const val DOWNLOAD_REQUEST = 1
        const val DOWNLOAD_RESPONSE = 2
        const val DL_REQ_ID = 1
        const val DL_REQ_OFFSET = 2
        const val DL_REQ_MAX_CHUNK = 3
        const val DL_RESP_STATUS = 1
        const val DL_RESP_ID = 2
        const val DL_RESP_OFFSET = 3
        const val DL_RESP_PAYLOAD = 4

        const val STATUS_OK = 100
        const val STATUS_UNKNOWN = 0
        const val DL_SUCCESS = 1
        const val DL_INVALID_ID = 2

        // A Connect IQ web request and its response.
        const val CIQ_URL = 1
        const val CIQ_METHOD = 2
        const val CIQ_HEADERS = 3
        const val CIQ_BODY = 4
        const val CIQ_MAX_RESPONSE = 5
        const val CIQ_INCLUDE_HEADERS = 6
        const val CIQ_RESPONSE_TYPE = 8
        const val CIQ_RESP_STATUS = 1
        const val CIQ_RESP_HTTP_STATUS = 2
        const val CIQ_RESP_BODY = 3
        const val CIQ_RESP_HEADERS = 4
        const val CIQ_RESP_INFLATED_SIZE = 5
        /** The response type an app asks for when it names none. */
        const val CIQ_TYPE_JSON = 0L
        const val CIQ_STATUS_INVALID_BODY = 400
        const val CIQ_STATUS_TOO_LARGE = 402

        /** A date in a path, which a log line leaves out. */
        val PATH_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

        /** Conservative chunk floor when the watch names no maximum. */
        const val DEFAULT_CHUNK = 500
    }

    /** Bodies the watch asked to pull over the data-transfer service. */
    private class PendingTransfer(val data: ByteArray, val onSent: (() -> Unit)?)

    private val transfers = mutableMapOf<Int, PendingTransfer>()
    private var nextTransferId = 1

    /** Handles one watch-initiated `Smart` message, or returns null when it is not ours. */
    public fun handle(payload: ByteArray): ByteArray? {
        val fields = readProtobuf(payload)
        protobufField(fields, GarminSmartService.HTTP)?.bytes?.let { return handleHttp(it) }
        protobufField(fields, GarminSmartService.DATA_TRANSFER)?.bytes
            ?.let { return handleDataTransfer(it) }
        return null
    }

    // The HTTP service.

    private fun handleHttp(service: ByteArray): ByteArray? {
        val fields = readProtobuf(service)
        protobufField(fields, RAW_REQUEST)?.bytes?.let { return handleRaw(it) }
        protobufField(fields, CIQ_REQUEST)?.bytes?.let { return handleConnectIq(it) }
        // An image, an OAuth or a web-page ask is not spoken: an explicit UNKNOWN.
        GarminLog.log("[GARMIN-HTTP] unsupported http request shape")
        return rawResponseError()
    }

    private fun handleRaw(raw: ByteArray): ByteArray {
        val request = parseRequest(raw) ?: return rawResponseError()
        val response = answer(request) ?: return rawResponseError()
        return smartHttp(
            ProtobufWriter().nested(RAW_RESPONSE, rawResponse(request, response)).toBytes(),
        )
    }

    /** The first interceptor's answer, or null when none claims [request] or it refuses. */
    private fun answer(request: GarminHttpRequest): GarminHttpResponse? {
        val interceptor = interceptors.firstOrNull { it.supports(request) }
        if (interceptor == null) {
            GarminLog.log("[GARMIN-HTTP] not serving ${request.domain}${loggable(request.path)}")
            return null
        }
        return interceptor.handle(request)
    }

    private fun parseRequest(raw: ByteArray): GarminHttpRequest? {
        val fields = readProtobuf(raw)
        val url = protobufField(fields, REQ_URL)?.bytes?.toString(Charsets.UTF_8) ?: return null
        val headers = fields.filter { it.field == REQ_HEADER }.mapNotNull { header ->
            val headerFields = readProtobuf(header.bytes ?: return@mapNotNull null)
            val key = protobufField(headerFields, HEADER_KEY)?.bytes?.toString(Charsets.UTF_8)
            val value = protobufField(headerFields, HEADER_VALUE)?.bytes?.toString(Charsets.UTF_8)
            if (key == null || value == null) null else key.lowercase() to value
        }.toMap()
        val (domain, path, query) = parseUrl(url)
        return GarminHttpRequest(
            url = url,
            domain = domain,
            path = path,
            query = query,
            method = protobufField(fields, REQ_METHOD)?.varint?.toInt(),
            headers = headers,
            // A text body comes in its own field; the bytes field is for the rest.
            body = protobufField(fields, REQ_BODY)?.bytes ?: protobufField(fields, REQ_RAW_BODY)?.bytes,
            useDataTransfer = protobufField(fields, REQ_USE_DATA_XFER)?.varint == 1L,
        )
    }

    // A Connect IQ app's web request: Monkey C values in and out, the body as JSON in between.

    private fun handleConnectIq(raw: ByteArray): ByteArray {
        val fields = readProtobuf(raw)
        val url = protobufField(fields, CIQ_URL)?.bytes?.toString(Charsets.UTF_8)
            ?: return connectIqError()
        val (domain, path, query) = parseUrl(url)
        val request = GarminHttpRequest(
            url = url,
            domain = domain,
            path = path,
            query = query,
            method = protobufField(fields, CIQ_METHOD)?.varint?.toInt(),
            headers = protobufField(fields, CIQ_HEADERS)?.bytes?.let(::connectIqHeaders).orEmpty(),
            body = protobufField(fields, CIQ_BODY)?.bytes?.let(::connectIqBody),
            useDataTransfer = false,
        )
        val response = answer(request) ?: return connectIqError()

        val body = connectIqResponseBody(response, protobufField(fields, CIQ_RESPONSE_TYPE)?.varint ?: CIQ_TYPE_JSON)
            ?: return connectIqError(CIQ_STATUS_INVALID_BODY, response.status)
        val maxLength = protobufField(fields, CIQ_MAX_RESPONSE)?.varint
        if (maxLength != null && maxLength > 0 && body.size > maxLength) {
            GarminLog.log("[GARMIN-HTTP] ${loggable(path)}: ${body.size}B is past the app's $maxLength")
            return connectIqError(CIQ_STATUS_TOO_LARGE)
        }
        val writer = ProtobufWriter()
            .varint(CIQ_RESP_STATUS, STATUS_OK)
            .varint(CIQ_RESP_HTTP_STATUS, response.status)
            .nested(CIQ_RESP_BODY, body)
            // Zero says the body is not compressed.
            .varint(CIQ_RESP_INFLATED_SIZE, 0)
        if (protobufField(fields, CIQ_INCLUDE_HEADERS)?.varint == 1L) {
            val headers = JSONObject()
            response.headers.forEach { (key, value) -> headers.put(key, value) }
            writer.nested(CIQ_RESP_HEADERS, GarminMonkeyCData.encode(headers))
        }
        GarminLog.log("[GARMIN-HTTP] serving ${loggable(path)} to an app (${body.size}B)")
        response.onSent?.invoke()
        return smartHttp(ProtobufWriter().nested(CIQ_RESPONSE, writer.toBytes()).toBytes())
    }

    /** An app's headers, a Monkey C dictionary, by lowercased name; none for bytes that are not one. */
    private fun connectIqHeaders(bytes: ByteArray): Map<String, String> {
        val headers = runCatching { GarminMonkeyCData.decode(bytes) }.getOrNull() as? JSONObject ?: return emptyMap()
        return headers.keys().asSequence().associate { key -> key.lowercase() to headers.opt(key).toString() }
    }

    /** An app's body as JSON text; bytes that are not a Monkey C value are handed on as they came. */
    private fun connectIqBody(bytes: ByteArray): ByteArray {
        val value = runCatching { GarminMonkeyCData.decode(bytes) }.getOrNull() ?: return bytes
        val text = if (value is String) value else value.toString()
        return text.toByteArray(Charsets.UTF_8)
    }

    /**
     * [response]'s body as the Monkey C value the app reads: a JSON body as its value, any other
     * as one string. Null when the app asked for JSON ([responseType]) and the body is not.
     */
    private fun connectIqResponseBody(response: GarminHttpResponse, responseType: Long): ByteArray? {
        val text = response.body.toString(Charsets.UTF_8)
        val json = response.headers.entries.any { (key, value) ->
            key.equals("content-type", ignoreCase = true) && value.startsWith("application/json")
        }
        if (!json) return if (responseType == CIQ_TYPE_JSON) null else GarminMonkeyCData.encode(text)
        val value = if (text.isBlank()) JSONObject.NULL else runCatching { JSONTokener(text).nextValue() }.getOrNull()
        return value?.let(GarminMonkeyCData::encode)
    }

    private fun connectIqError(status: Int = STATUS_UNKNOWN, httpStatus: Int = 0): ByteArray = smartHttp(
        ProtobufWriter()
            .nested(
                CIQ_RESPONSE,
                ProtobufWriter().varint(CIQ_RESP_STATUS, status).varint(CIQ_RESP_HTTP_STATUS, httpStatus).toBytes(),
            )
            .toBytes(),
    )

    /** [path] for a log line: a date in it is a record's day, which the log never holds. */
    private fun loggable(path: String): String = PATH_DATE.replace(path, "<date>")

    private fun rawResponse(
        request: GarminHttpRequest,
        response: GarminHttpResponse,
    ): ByteArray {
        val writer = ProtobufWriter()
            .varint(RESP_STATUS, STATUS_OK)
            .varint(RESP_HTTP_STATUS, response.status)
        val headers = response.headers.toMutableMap()

        if (request.useDataTransfer) {
            val id = nextTransferId++
            transfers[id] = PendingTransfer(response.body, response.onSent)
            GarminLog.log(
                "[GARMIN-HTTP] serving ${loggable(request.path)} " +
                    "(${response.body.size}B as transfer $id)",
            )
            writer.nested(
                RESP_XFER_DATA,
                ProtobufWriter()
                    .varint(XFER_ID, id)
                    .varint(XFER_SIZE, response.body.size)
                    .toBytes(),
            )
        } else {
            var body = response.body
            if (request.headers["accept-encoding"] == "gzip") {
                body = gzip(body)
                headers["Content-Encoding"] = "gzip"
            }
            GarminLog.log("[GARMIN-HTTP] serving ${loggable(request.path)} (${body.size}B)")
            writer.nested(RESP_BODY, body)
        }

        for ((key, value) in headers) {
            writer.nested(
                RESP_HEADER,
                ProtobufWriter().string(HEADER_KEY, key).string(HEADER_VALUE, value).toBytes(),
            )
        }
        // An inline body leaves with this reply; a chunked one reports on its last chunk.
        if (!request.useDataTransfer) response.onSent?.invoke()
        return writer.toBytes()
    }

    private fun rawResponseError(): ByteArray = smartHttp(
        ProtobufWriter()
            .nested(
                RAW_RESPONSE,
                ProtobufWriter().varint(RESP_STATUS, STATUS_UNKNOWN).toBytes(),
            )
            .toBytes(),
    )

    private fun smartHttp(service: ByteArray): ByteArray =
        ProtobufWriter().nested(GarminSmartService.HTTP, service).toBytes()

    // The data-transfer service (chunked response bodies).

    private fun handleDataTransfer(service: ByteArray): ByteArray? {
        val request = protobufField(readProtobuf(service), DOWNLOAD_REQUEST)?.bytes ?: return null
        val fields = readProtobuf(request)
        val id = protobufField(fields, DL_REQ_ID)?.varint?.toInt() ?: return null
        val offset = protobufField(fields, DL_REQ_OFFSET)?.varint?.toInt() ?: return null
        val maxChunk = protobufField(fields, DL_REQ_MAX_CHUNK)?.varint?.toInt() ?: DEFAULT_CHUNK

        val transfer = transfers[id]
        val response = ProtobufWriter()
        if (transfer == null || offset > transfer.data.size) {
            GarminLog.log("[GARMIN-HTTP] transfer $id@$offset: no such transfer")
            response.nested(
                DOWNLOAD_RESPONSE,
                ProtobufWriter()
                    .varint(DL_RESP_STATUS, DL_INVALID_ID)
                    .varint(DL_RESP_ID, id)
                    .varint(DL_RESP_OFFSET, offset)
                    .toBytes(),
            )
        } else {
            val data = transfer.data
            val end = minOf(offset + maxChunk, data.size)
            val chunk = data.copyOfRange(offset, end)
            GarminLog.log("[GARMIN-HTTP] transfer $id: $offset..$end of ${data.size}")
            if (end == data.size) {
                // Served in full; only now does the watch have the whole body.
                transfers.remove(id)
                transfer.onSent?.invoke()
            }
            response.nested(
                DOWNLOAD_RESPONSE,
                ProtobufWriter()
                    .varint(DL_RESP_STATUS, DL_SUCCESS)
                    .varint(DL_RESP_ID, id)
                    .varint(DL_RESP_OFFSET, offset)
                    .nested(DL_RESP_PAYLOAD, chunk)
                    .toBytes(),
            )
        }
        return ProtobufWriter()
            .nested(GarminSmartService.DATA_TRANSFER, response.toBytes())
            .toBytes()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun parseUrl(url: String): Triple<String, String, Map<String, String>> {
        val withoutScheme = url.substringAfter("://", url)
        val domain = withoutScheme.substringBefore("/")
        val pathAndQuery = withoutScheme.removePrefix(domain)
        val path = pathAndQuery.substringBefore("?")
        val query = pathAndQuery.substringAfter("?", "")
            .split("&")
            .filter { it.isNotBlank() }
            .associate { pair ->
                pair.substringBefore("=") to
                    java.net.URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
            }
        return Triple(domain, path, query)
    }
}
