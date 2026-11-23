package tech.mmarca.openwatch.devices.weather

import org.json.JSONObject

/** A [WeatherSnapshot] as the generic-weather JSON schema writes it: what [WeatherStore] keeps. */
fun WeatherSnapshot.toJson(): JSONObject = JSONObject().apply {
    put("timestamp", timestamp)
    put("location", location)
    put("currentTemp", currentTempKelvin)
    put("todayMinTemp", todayMinTempKelvin)
    put("todayMaxTemp", todayMaxTempKelvin)
    put("currentConditionCode", currentConditionCode)
    put("currentHumidity", currentHumidity)
    put("windSpeed", windSpeedKmh)
    put("windDirection", windDirectionDegrees)
    put("uvIndex", uvIndex)
    put("precipProbability", precipProbability)
    put("dewPoint", dewPointKelvin)
    put("feelsLikeTemp", feelsLikeTempKelvin)
    put("latitude", latitude)
    put("longitude", longitude)
    put("pressure", pressureMbar)
    put("cloudCover", cloudCover)
    put("visibility", visibilityMeters)
    put(
        "hourly",
        org.json.JSONArray().also { array ->
            hourly.forEach { hour ->
                array.put(
                    JSONObject().apply {
                        put("timestamp", hour.timestamp)
                        put("temp", hour.tempKelvin)
                        put("conditionCode", hour.conditionCode)
                        put("humidity", hour.humidity)
                        put("windSpeed", hour.windSpeedKmh)
                        put("windDirection", hour.windDirectionDegrees)
                        put("uvIndex", hour.uvIndex)
                        put("precipProbability", hour.precipProbability)
                    },
                )
            }
        },
    )
    put(
        "forecasts",
        org.json.JSONArray().also { array ->
            daily.forEach { day ->
                array.put(
                    JSONObject().apply {
                        put("minTemp", day.minTempKelvin)
                        put("maxTemp", day.maxTempKelvin)
                        put("conditionCode", day.conditionCode)
                        put("precipProbability", day.precipProbability)
                        put("sunRise", day.sunRise)
                        put("sunSet", day.sunSet)
                    },
                )
            }
        },
    )
}

/** Parses the broadcast schema. Absent fields default: senders fill what they have. */
fun WeatherSnapshot.Companion.fromJson(json: JSONObject): WeatherSnapshot = WeatherSnapshot(
    timestamp = json.optLong("timestamp", System.currentTimeMillis() / 1000),
    location = json.optString("location", ""),
    currentTempKelvin = json.optInt("currentTemp", 0),
    todayMinTempKelvin = json.optInt("todayMinTemp", 0),
    todayMaxTempKelvin = json.optInt("todayMaxTemp", 0),
    currentConditionCode = json.optInt("currentConditionCode", 0),
    currentHumidity = json.optInt("currentHumidity", 0),
    windSpeedKmh = json.optDouble("windSpeed", 0.0),
    windDirectionDegrees = json.optInt("windDirection", 0),
    uvIndex = json.optDouble("uvIndex", 0.0),
    precipProbability = json.optInt("precipProbability", 0),
    dewPointKelvin = json.optInt("dewPoint", 0),
    feelsLikeTempKelvin = json.optInt("feelsLikeTemp", 0),
    latitude = json.optDouble("latitude", 0.0),
    longitude = json.optDouble("longitude", 0.0),
    pressureMbar = json.optDouble("pressure", 0.0),
    cloudCover = json.optInt("cloudCover", 0),
    visibilityMeters = json.optDouble("visibility", 0.0),
    hourly = json.optJSONArray("hourly")?.let { array ->
        (0 until array.length()).map { i ->
            val hour = array.getJSONObject(i)
            WeatherSnapshot.HourlyForecast(
                timestamp = hour.optLong("timestamp", 0),
                tempKelvin = hour.optInt("temp", 0),
                conditionCode = hour.optInt("conditionCode", 0),
                humidity = hour.optInt("humidity", 0),
                windSpeedKmh = hour.optDouble("windSpeed", 0.0),
                windDirectionDegrees = hour.optInt("windDirection", 0),
                uvIndex = hour.optDouble("uvIndex", 0.0),
                precipProbability = hour.optInt("precipProbability", 0),
            )
        }
    }.orEmpty(),
    daily = json.optJSONArray("forecasts")?.let { array ->
        (0 until array.length()).map { i ->
            val day = array.getJSONObject(i)
            WeatherSnapshot.DailyForecast(
                minTempKelvin = day.optInt("minTemp", 0),
                maxTempKelvin = day.optInt("maxTemp", 0),
                conditionCode = day.optInt("conditionCode", 0),
                precipProbability = day.optInt("precipProbability", 0),
                sunRise = day.optLong("sunRise", 0),
                sunSet = day.optLong("sunSet", 0),
            )
        }
    }.orEmpty(),
)
