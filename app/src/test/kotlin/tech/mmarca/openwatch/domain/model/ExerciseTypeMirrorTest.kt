package tech.mmarca.openwatch.domain.model

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * health-calculations is plain Kotlin and carries Health Connect's exercise and segment
 * types as its own constants. They must be Health Connect's, one for one, and all of them:
 * OpenWatch passes a record's `exerciseType` and `segmentType` straight in.
 */
class ExerciseTypeMirrorTest {

    @Test
    fun `every exercise type equals Health Connect's, and none is missing`() {
        assertMirrors(
            mirror = tech.mmarca.healthcalculations.model.ExerciseSessionRecord::class.java,
            healthConnect = androidx.health.connect.client.records.ExerciseSessionRecord::class.java,
            prefix = "EXERCISE_TYPE_",
        )
    }

    @Test
    fun `every segment type equals Health Connect's, and none is missing`() {
        assertMirrors(
            mirror = tech.mmarca.healthcalculations.model.ExerciseSegment::class.java,
            healthConnect = androidx.health.connect.client.records.ExerciseSegment::class.java,
            prefix = "EXERCISE_SEGMENT_TYPE_",
        )
    }

    private fun assertMirrors(mirror: Class<*>, healthConnect: Class<*>, prefix: String) {
        assertEquals(constants(healthConnect, prefix), constants(mirror, prefix))
    }

    private fun constants(type: Class<*>, prefix: String): Map<String, Int> =
        type.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith(prefix) }
            .associate { it.name to it.getInt(null) }
}
