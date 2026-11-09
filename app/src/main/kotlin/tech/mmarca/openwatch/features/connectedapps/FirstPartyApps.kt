package tech.mmarca.openwatch.features.connectedapps

import tech.mmarca.openlink.host.OpenLinkHostCore

/**
 * The first-party OpenLink clients OpenWatch knows: their package names and the signers it
 * trusts without asking (`res/values/openlink.xml`).
 */
class FirstPartyApps(packageNames: Set<String>, knownSigners: Set<String>) {
    private val packageNames = packageNames.toSet()
    private val knownSigners = knownSigners.map(OpenLinkHostCore::normalizeSha256).toSet()

    /**
     * True when an app uses a first-party name but is not signed by a first-party key: an
     * F-Droid or self-built copy, or another app that took the name.
     */
    fun usesNameWithoutKey(packageName: String, signerSha256: String): Boolean =
        packageName in packageNames && OpenLinkHostCore.normalizeSha256(signerSha256) !in knownSigners
}
