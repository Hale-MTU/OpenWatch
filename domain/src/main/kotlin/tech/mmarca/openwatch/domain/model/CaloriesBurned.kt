package tech.mmarca.openwatch.domain.model

/** Where a day's calories-burned figure came from; an activity write carries one per record. */
enum class CaloriesBurnedSource {
    NO_DATA,
    RECORDED_TOTAL,
    ESTIMATED_ACTIVE_AND_BMR,
}

data class CaloriesBurnedValue(
    val kcal: Double,
    val source: CaloriesBurnedSource,
)
