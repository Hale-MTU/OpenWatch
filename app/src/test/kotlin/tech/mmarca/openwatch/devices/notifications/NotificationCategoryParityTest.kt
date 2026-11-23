package tech.mmarca.openwatch.devices.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.mmarca.openwatch.devices.garmin.GarminNotificationCategory

/**
 * The notification filter (`:devices:core`) numbers its categories as Garmin's enum does, without
 * naming the family. Here, where both are visible: the filter's own tests are the shared core's.
 */
class NotificationCategoryParityTest {

    @Test
    fun `the category duplicates track the real Garmin enum's ordinals`() {
        // The duplication policy stayed after the Pigeon boundary went; this pin keeps the two from drifting.
        val garmin = GarminNotificationCategory.entries
        assertThat(NotificationFilter.Category.INCOMING_CALL)
            .isEqualTo(garmin.indexOfFirst { it.name == "INCOMING_CALL" })
        assertThat(NotificationFilter.Category.SMS)
            .isEqualTo(garmin.indexOfFirst { it.name == "SMS" })
        assertThat(NotificationFilter.Category.HEALTH_AND_FITNESS)
            .isEqualTo(garmin.indexOfFirst { it.name == "HEALTH_AND_FITNESS" })
    }
}
