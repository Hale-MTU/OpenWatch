package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Every BLE link goes through the radio lease. A `connectGatt(` outside the files that take
 * one opens a second link the lease knows nothing about, beside a sync or the notification
 * forwarder on the same watch. A new one belongs in a file that holds the lease, added here
 * with the reason.
 */
class GattConnectionRatchetTest {

    @Test
    fun `connectGatt is called only where the radio lease is held`() {
        val callers = (listOf("src/main/kotlin/tech/mmarca/openwatch") + LibraryModuleRoots.values).asSequence()
            .flatMap { File(it).walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readLines().any { ConnectGatt.containsMatchIn(it.substringBefore("//")) } }
            .map { it.name }
            .toSet()

        assertWithMessage("connectGatt outside the leased files; take the lease, then add the file here")
            .that(callers)
            .isEqualTo(Allowed)
    }

    private companion object {
        val ConnectGatt = Regex("""\.connectGatt\(""")

        val Allowed = setOf(
            // The shared GATT link in :devices:core. Its owners hold the lease for its whole life:
            // the Garmin client, opened by GarminWatchSyncService, the forwarder and the settings
            // link, each under its own.
            "GattLink.kt",
            // The capability probe, under RadioLeaseOwner.PROBE.
            "BleSensorCoordinator.kt",
        )
    }
}
