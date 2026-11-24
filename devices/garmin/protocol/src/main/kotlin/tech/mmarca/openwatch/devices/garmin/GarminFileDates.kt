package tech.mmarca.openwatch.devices.garmin

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.openwatch.core.fit.FitDecoder
import tech.mmarca.openwatch.core.fit.fitInstant

/**
 * The earliest local day the downloaded files carry data for, or null when
 * the watch dated none of them. Pure, so it is testable without the service.
 */
public fun garminEarliestAffectedDay(
    downloaded: List<GarminDownloadedFile>,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate? = downloaded
    .mapNotNull(::garminFileDate)
    .minOrNull()
    ?.atZone(zone)
    ?.toLocalDate()

/** True when a wanted file cannot be dated at all, so no day is safe to keep. */
public fun garminNeedsFullBodyEnergyInvalidation(downloaded: List<GarminDownloadedFile>): Boolean =
    downloaded.any { it.entry.type.wanted && garminFileDate(it) == null }

/** The listing's date, or the earliest FIT timestamp when the listing has none. */
public fun garminFileDate(file: GarminDownloadedFile): Instant? =
    file.entry.fileDate ?: runCatching { garminFitFileDate(file.bytes) }.getOrNull()

/** The earliest timestamp across the chained FIT files in [bytes], or null when none carries one. */
public fun garminFitFileDate(bytes: ByteArray): Instant? {
    var offset = 0
    var earliest: Long? = null
    while (offset < bytes.size && FitDecoder.isFitFileAt(bytes, offset)) {
        val file = FitDecoder.readFile(bytes, offset)
        file.messages.mapNotNull { it.timestamp }.minOrNull()?.let { timestamp ->
            earliest = earliest?.coerceAtMost(timestamp) ?: timestamp
        }
        if (file.nextOffset <= offset) break
        offset = file.nextOffset
    }
    return earliest?.let(::fitInstant)
}
