package tech.mmarca.openwatch.link

import android.content.Context
import tech.mmarca.openwatch.R

/**
 * The first-party OpenLink clients OpenWatch knows: their package names and the signers it
 * trusts them under without asking (`res/values/openlink.xml`). The name and the key count
 * together; any other app asks on the Connected apps screen.
 */
class FirstPartyApps(
    private val packageNames: Set<String>,
    private val knownSigners: Set<String>,
    private val names: Set<String> = emptySet(),
) {

    /** Each first-party package with the signers it may come under, for the OpenLink host. */
    val knownClients: Map<String, Set<String>> get() = packageNames.associateWith { knownSigners }

    /**
     * True when an app uses a first-party name but did not come under a first-party key
     * ([knownSigner], as the host recorded it at its last hello): an F-Droid or self-built
     * copy, or another app that took the name.
     */
    fun usesNameWithoutKey(packageName: String, knownSigner: Boolean): Boolean =
        packageName in packageNames && !knownSigner

    /**
     * The first-party name another app's [label] carries, or null: an app may call itself
     * anything, so a label with "OpenVitals" in it under a foreign package is a look-alike.
     * Case, spaces and punctuation do not hide it.
     */
    fun lookalikeOf(label: String, packageName: String): String? {
        if (packageName in packageNames) return null
        val plain = label.lettersAndDigits()
        return names.firstOrNull { it.lettersAndDigits() in plain }
    }

    companion object {
        fun from(context: Context): FirstPartyApps = FirstPartyApps(
            packageNames = context.resources.getStringArray(R.array.openlink_first_party_packages).toSet(),
            knownSigners = context.resources.getStringArray(R.array.openlink_known_signers).toSet(),
            names = context.resources.getStringArray(R.array.openlink_first_party_names).toSet(),
        )
    }
}

/**
 * The first 64 bits of a signer's hex SHA-256, in groups of four, for a person to compare with
 * what the app's maker publishes: `0416 C065 1543 E951`.
 */
fun signerFingerprint(sha256Hex: String): String =
    sha256Hex.take(16).uppercase().chunked(4).joinToString(" ")

private fun String.lettersAndDigits(): String = filter(Char::isLetterOrDigit).lowercase()
