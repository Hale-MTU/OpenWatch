package tech.mmarca.openwatch.data.repository.contract

import tech.mmarca.healthcalculations.preferences.BodyProfile

/** A body profile as declared, and as Health Connect would complete it; the same by default. */
class FakeBodyProfileRepository(
    var declared: BodyProfile = BodyProfile(),
    var resolved: BodyProfile = declared,
) : BodyProfileRepository {

    /** How many times [resolve] was asked, as many Health Connect reads. */
    var resolves = 0
        private set

    override fun declared(): BodyProfile = declared

    override suspend fun resolve(): BodyProfile {
        resolves += 1
        return resolved
    }
}
