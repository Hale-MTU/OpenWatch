package tech.mmarca.openwatch.activityimport

import java.time.Instant
import tech.mmarca.openwatch.domain.model.BleRecordingSampleBuffer
import tech.mmarca.openwatch.domain.model.ExerciseRoutePoint

data class RouteFileImport(
    val fileName: String?,
    val points: List<ExerciseRoutePoint>,
    val distanceMeters: Double,
    val elevationGainedMeters: Double,
    val activeCaloriesKcal: Double? = null,
    val totalCaloriesKcal: Double? = null,
    val startTime: Instant,
    val endTime: Instant,
    val durationSeconds: Long? = null,
    val name: String? = null,
    val description: String? = null,
    val type: String? = null,
    /** The per-second series the file recorded. Named for the BLE recorder: same series, same write path. */
    val bleSamples: BleRecordingSampleBuffer = BleRecordingSampleBuffer(),
    val hasRecordedTimestamps: Boolean = true,
    val hasImportedTimeRange: Boolean = true,
    /** A hash of the file's bytes. The same file always gives the same key, whatever its name. */
    val contentKey: String? = null,
)

/** The one file parser OpenWatch needs: a Garmin watch's activity FIT files. */
internal object RouteFileParser {
    /** 96 bits of the digest: far past any collision among one person's files, and short in a client id. */
    private const val ContentKeyBytes = 12

    fun parseFile(fileBytes: ByteArray, fileName: String? = null): RouteFileImport =
        parseFileContent(fileBytes, fileName).copy(contentKey = fileBytes.contentKey())

    private fun ByteArray.contentKey(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(this)
            .take(ContentKeyBytes)
            .joinToString("") { "%02x".format(it) }

    private fun parseFileContent(fileBytes: ByteArray, fileName: String?): RouteFileImport {
        require(fileBytes.size <= MaxRouteFileBytes) {
            "Activity file is too large."
        }
        try {
            require(fileBytes.isFitFile() || fileName.hasExtension("fit")) { "Activity file is not a FIT file." }
            return FitRouteParser.parse(fileBytes, fileName = fileName)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalArgumentException("Activity file is not a valid FIT file.", error)
        }
    }
}
