package tech.mmarca.openwatch.devices.weather

/**
 * One weather report in the generic-weather JSON schema that Breezy
 * Weather speaks. Units follow the schema: Kelvin integers,
 * km/h, unix seconds. Stored verbatim; the FIT encoder converts.
 */
data class WeatherSnapshot(
    /** When the provider says this report was observed, unix seconds. */
    val timestamp: Long,
    val location: String,
    val currentTempKelvin: Int,
    val todayMinTempKelvin: Int,
    val todayMaxTempKelvin: Int,
    /** OpenWeatherMap condition code — the schema's condition vocabulary. */
    val currentConditionCode: Int,
    val currentHumidity: Int,
    val windSpeedKmh: Double,
    val windDirectionDegrees: Int,
    val uvIndex: Double,
    val precipProbability: Int,
    val dewPointKelvin: Int,
    val feelsLikeTempKelvin: Int,
    val latitude: Double,
    val longitude: Double,
    val pressureMbar: Double = 0.0,
    val cloudCover: Int = 0,
    val visibilityMeters: Double = 0.0,
    val hourly: List<HourlyForecast> = emptyList(),
    val daily: List<DailyForecast> = emptyList(),
) {
    data class HourlyForecast(
        val timestamp: Long,
        val tempKelvin: Int,
        val conditionCode: Int,
        val humidity: Int,
        val windSpeedKmh: Double,
        val windDirectionDegrees: Int,
        val uvIndex: Double,
        val precipProbability: Int,
    )

    data class DailyForecast(
        val minTempKelvin: Int,
        val maxTempKelvin: Int,
        val conditionCode: Int,
        val precipProbability: Int,
        val sunRise: Long = 0,
        val sunSet: Long = 0,
    )

    /** The JSON form, `toJson` and `fromJson`, is the app's (`WeatherSnapshotJson.kt`), beside Android's org.json. */
    companion object
}
