package tech.mmarca.openwatch.data.repository

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.WeightRecord
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.data.repository.contract.BodyProfilePreferences
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

/**
 * The body the estimates use: the declared profile, with the weight and height
 * Health Connect holds taking precedence when their read is granted. A read
 * that fails throws: a null here would pass for "no weight" and skip an estimate
 * that should have been made.
 */
@Singleton
class BodyProfileProvider @Inject constructor(
    private val preferences: BodyProfilePreferences,
    private val hc: HealthConnectGateway,
) {
    /** What the user typed, with no Health Connect call. The whole answer in the background. */
    fun declared(): BodyProfile = preferences.bodyProfile()

    suspend fun resolve(): BodyProfile {
        val declared = declared()
        val granted = hc.grantedPermissions()
        val measuredWeight = if (READ_WEIGHT in granted) hc.readLatestWeightKg() else null
        val measuredHeight = if (READ_HEIGHT in granted) hc.readLatestHeightCm() else null
        return declared.copy(
            weightKg = measuredWeight ?: declared.weightKg,
            heightCm = measuredHeight ?: declared.heightCm,
        ).normalized()
    }

    private companion object {
        val READ_WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)
        val READ_HEIGHT = HealthPermission.getReadPermission(HeightRecord::class)
    }
}
