package tech.mmarca.openwatch.devices.garmin

/** Where a one-shot send is, for the screen's progress line. */
public enum class GarminSendStage { CONNECTING, SENDING }

/** How a one-shot send to the watch ended. The wording is the screen's. */
public sealed interface GarminSendFileResult {
    public data object Sent : GarminSendFileResult

    /** The watch answered and said no. */
    public data class Refused(val reason: GarminUploadRefusal) : GarminSendFileResult

    /** The watch went quiet part-way. */
    public data object NoAnswer : GarminSendFileResult

    /** Something else holds the radio. [holder] is its lease tag. */
    public data class Busy(val holder: String) : GarminSendFileResult
    public data object SyncRunning : GarminSendFileResult
    public data object RecordingActive : GarminSendFileResult

    /** No connection: out of range, Bluetooth off, or a missing permission. */
    public data object Unreachable : GarminSendFileResult
    public data object HandshakeTimeout : GarminSendFileResult
    public data object LinkLost : GarminSendFileResult
}
