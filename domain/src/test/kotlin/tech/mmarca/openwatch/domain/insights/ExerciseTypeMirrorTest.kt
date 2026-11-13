package tech.mmarca.openwatch.domain.insights

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plain-Kotlin copy of the exercise types must hold Health Connect's values, one for one. */
class ExerciseTypeMirrorTest {

    @Test
    fun `every mirrored exercise type equals Health Connect's`() {
        val mirrored = ExerciseSessionRecord::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("EXERCISE_TYPE_") }
        assertTrue(mirrored.size >= 50)
        val healthConnect = androidx.health.connect.client.records.ExerciseSessionRecord::class.java
        for (field in mirrored) {
            assertEquals(field.name, healthConnect.getField(field.name).getInt(null), field.getInt(null))
        }
    }
}
