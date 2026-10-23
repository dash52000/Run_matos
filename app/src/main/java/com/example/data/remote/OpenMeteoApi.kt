package com.example.data.remote

import com.example.data.model.BestWeatherSlotSuggestion
import com.example.data.model.GeoLocationResult
import com.example.data.model.HourlyWeatherStep
import com.example.data.model.RunSessionType
import com.example.data.model.WeatherSlotAnalysis
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import okhttp3.Dns
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class OpenMeteoForecastResponse(
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double,
    @Json(name = "elevation") val elevation: Double? = null,
    @Json(name = "hourly") val hourly: OpenMeteoHourly?
)

data class OpenMeteoHourly(
    @Json(name = "time") val time: List<String> = emptyList(),
    @Json(name = "temperature_2m") val temperature2m: List<Double?> = emptyList(),
    @Json(name = "apparent_temperature") val apparentTemperature: List<Double?> = emptyList(),
    @Json(name = "precipitation") val precipitation: List<Double?> = emptyList(),
    @Json(name = "precipitation_probability") val precipitationProbability: List<Int?> = emptyList(),
    @Json(name = "wind_speed_10m") val windSpeed10m: List<Double?> = emptyList(),
    @Json(name = "wind_gusts_10m") val windGusts10m: List<Double?> = emptyList(),
    @Json(name = "wind_direction_10m") val windDirection10m: List<Int?> = emptyList(),
    @Json(name = "relative_humidity_2m") val relativeHumidity2m: List<Int?> = emptyList(),
    @Json(name = "uv_index") val uvIndex: List<Double?> = emptyList(),
    @Json(name = "weather_code") val weatherCode: List<Int?> = emptyList(),
    @Json(name = "shortwave_radiation") val shortwaveRadiation: List<Double?> = emptyList()
)

data class OpenMeteoGeocodingResponse(
    @Json(name = "results") val results: List<OpenMeteoGeoItem>?
)

data class OpenMeteoGeoItem(
    @Json(name = "name") val name: String,
    @Json(name = "country") val country: String? = "",
    @Json(name = "admin1") val admin1: String? = null,
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double
)

interface OpenMeteoForecastService {
    @GET("v1/forecast")
    suspend fun getForecastAndPastRain(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "temperature_2m,apparent_temperature,precipitation,precipitation_probability,wind_speed_10m,wind_gusts_10m,wind_direction_10m,relative_humidity_2m,uv_index,weather_code",
        @Query("past_days") pastDays: Int = 3,
        @Query("forecast_days") forecastDays: Int = 7,
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoForecastResponse

    @GET("v1/forecast")
    suspend fun getHistoricalDateRangeFromForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("hourly") hourly: String = "temperature_2m,apparent_temperature,precipitation,precipitation_probability,wind_speed_10m,wind_gusts_10m,wind_direction_10m,relative_humidity_2m,uv_index,weather_code",
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoForecastResponse
}

interface OpenMeteoArchiveService {
    @GET("v1/archive")
    suspend fun getHistoricalArchiveWeather(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("hourly") hourly: String = "temperature_2m,apparent_temperature,precipitation,wind_speed_10m,wind_gusts_10m,wind_direction_10m,relative_humidity_2m,weather_code,shortwave_radiation",
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoForecastResponse
}

interface OpenMeteoGeocodingService {
    @GET("v1/search")
    suspend fun searchCity(
        @Query("name") query: String,
        @Query("count") count: Int = 6,
        @Query("language") language: String = "fr",
        @Query("format") format: String = "json"
    ): OpenMeteoGeocodingResponse
}

object OpenMeteoClient {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val forecastAdapter by lazy {
        moshi.adapter(OpenMeteoForecastResponse::class.java)
    }

    private val openMeteoResilientDns = object : Dns {
        private val staticIps = mapOf(
            "api.open-meteo.com" to listOf(
                InetAddress.getByAddress("api.open-meteo.com", byteArrayOf(157.toByte(), 90.toByte(), 83.toByte(), 168.toByte()))
            ),
            "geocoding-api.open-meteo.com" to listOf(
                InetAddress.getByAddress("geocoding-api.open-meteo.com", byteArrayOf(202.toByte(), 61.toByte(), 206.toByte(), 6.toByte()))
            ),
            "archive-api.open-meteo.com" to listOf(
                InetAddress.getByAddress("archive-api.open-meteo.com", byteArrayOf(157.toByte(), 90.toByte(), 83.toByte(), 168.toByte()))
            )
        )

        override fun lookup(hostname: String): List<InetAddress> {
            val lower = hostname.lowercase(Locale.ROOT)
            return try {
                val resolved = Dns.SYSTEM.lookup(hostname)
                if (resolved.isNotEmpty()) resolved else staticIps[lower] ?: emptyList()
            } catch (_: UnknownHostException) {
                staticIps[lower] ?: throw UnknownHostException("Impossible de résoudre l'hôte météo $hostname")
            } catch (_: Exception) {
                staticIps[lower] ?: throw UnknownHostException("Erreur DNS sur l'hôte météo $hostname")
            }
        }
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(openMeteoResilientDns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val forecastService: OpenMeteoForecastService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenMeteoForecastService::class.java)
    }

    val archiveService: OpenMeteoArchiveService by lazy {
        Retrofit.Builder()
            .baseUrl("https://archive-api.open-meteo.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenMeteoArchiveService::class.java)
    }

    val geocodingService: OpenMeteoGeocodingService by lazy {
        Retrofit.Builder()
            .baseUrl("https://geocoding-api.open-meteo.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenMeteoGeocodingService::class.java)
    }

    /**
     * Récupère les prévisions Open-Meteo avec réessai automatique (retry) en cas d'erreur réseau transitoire.
     */
    suspend fun fetchForecastWithRetry(
        latitude: Double,
        longitude: Double,
        maxAttempts: Int = 2
    ): OpenMeteoForecastResponse {
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return forecastService.getForecastAndPastRain(
                    latitude = latitude,
                    longitude = longitude
                )
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts) {
                    delay(450L)
                }
            }
        }
        throw (lastException ?: IllegalStateException("Impossible de récupérer la météo Open-Meteo"))
    }

    fun serializeForecastToJson(response: OpenMeteoForecastResponse): String? {
        return try {
            forecastAdapter.toJson(response)
        } catch (_: Exception) {
            null
        }
    }

    fun deserializeForecastFromJson(json: String?): OpenMeteoForecastResponse? {
        if (json.isNullOrBlank()) return null
        return try {
            forecastAdapter.fromJson(json)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun searchLocations(query: String): List<GeoLocationResult> {
        if (query.trim().length < 2) return emptyList()
        val resp = geocodingService.searchCity(query.trim())
        return resp.results.orEmpty().map {
            GeoLocationResult(
                name = it.name,
                country = it.country ?: "",
                admin1 = it.admin1,
                latitude = it.latitude,
                longitude = it.longitude
            )
        }
    }

    /**
     * Récupère la météo historique réelle à une date passée (et sur les 72 h précédentes pour le cumul de boue)
     * via Open-Meteo (Forecast API pour les 85 derniers jours, ou Archive ERA5 API au-delà).
     */
    suspend fun fetchHistoricalSlotWeather(
        locationName: String,
        latitude: Double,
        longitude: Double,
        targetDateMillis: Long,
        startHourOfDay: Int,
        durationMinutes: Int,
        sessionType: RunSessionType
    ): WeatherSlotAnalysis {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val endCal = Calendar.getInstance().apply {
            timeInMillis = targetDateMillis
            set(Calendar.HOUR_OF_DAY, 12)
        }
        val startCal = (endCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -3) // 72 h avant la date choisie pour calculer la boue
        }

        val startDateStr = dateFormat.format(startCal.time)
        val endDateStr = dateFormat.format(endCal.time)
        val daysAgo = ((System.currentTimeMillis() - targetDateMillis) / (24L * 3600L * 1000L)).toInt()

        val response = if (daysAgo <= 80) {
            try {
                forecastService.getHistoricalDateRangeFromForecast(
                    latitude = latitude,
                    longitude = longitude,
                    startDate = startDateStr,
                    endDate = endDateStr
                )
            } catch (_: Exception) {
                archiveService.getHistoricalArchiveWeather(
                    latitude = latitude,
                    longitude = longitude,
                    startDate = startDateStr,
                    endDate = endDateStr
                )
            }
        } else {
            try {
                archiveService.getHistoricalArchiveWeather(
                    latitude = latitude,
                    longitude = longitude,
                    startDate = startDateStr,
                    endDate = endDateStr
                )
            } catch (_: Exception) {
                forecastService.getHistoricalDateRangeFromForecast(
                    latitude = latitude,
                    longitude = longitude,
                    startDate = startDateStr,
                    endDate = endDateStr
                )
            }
        }

        return analyzeHistoricalDateSlotFromResponse(
            response = response,
            locationName = locationName,
            targetDateStr = endDateStr,
            startHourOfDay = startHourOfDay,
            durationMinutes = durationMinutes,
            sessionType = sessionType
        )
    }

    fun analyzeHistoricalDateSlotFromResponse(
        response: OpenMeteoForecastResponse,
        locationName: String,
        targetDateStr: String,
        startHourOfDay: Int,
        durationMinutes: Int,
        sessionType: RunSessionType
    ): WeatherSlotAnalysis {
        val hourly = response.hourly
        val times = hourly?.time.orEmpty()
        if (times.isEmpty()) {
            return fallbackAnalysis(locationName, response.latitude, response.longitude, startHourOfDay, durationMinutes, sessionType)
        }

        val hourPadded = String.format(Locale.US, "%02d", startHourOfDay.coerceIn(0, 23))
        val targetPrefix = "${targetDateStr}T$hourPadded:"

        var startIndex = times.indexOfFirst { it.startsWith(targetPrefix) }
        if (startIndex < 0) {
            startIndex = (times.size - 24 + startHourOfDay.coerceIn(0, 23)).coerceIn(0, times.lastIndex)
        }

        return extractSlotAnalysis(
            response = response,
            locationName = locationName,
            startIndex = startIndex,
            startHourOfDay = startHourOfDay,
            durationMinutes = durationMinutes,
            sessionType = sessionType,
            fallbackIsoPrefix = "${targetDateStr}T${hourPadded}:"
        )
    }

    /**
     * Analyse le créneau horaire choisi et calcule :
     * - Cumul de pluie des 72 h précédentes (past_days=3)
     * - Température réelle, ressentie et amplitude sur le créneau
     * - Température de référence (ajustée selon vent, rafales, pluie, humidité, UV et type de séance)
     * - Probabilité de pluie (%), sens du vent, rafales et code météo WMO sur le créneau
     * - Évolution heure par heure pendant la sortie et température estimée au point culminant GPX
     */
    fun analyzeSlotFromResponse(
        response: OpenMeteoForecastResponse,
        locationName: String,
        startHourOfDay: Int,
        dayOffsetFromToday: Int,
        durationMinutes: Int,
        sessionType: RunSessionType,
        minTrackEleMeters: Int? = null,
        maxTrackEleMeters: Int? = null,
        fetchedAtTimeLabel: String = "",
        isFromOfflineCache: Boolean = false
    ): WeatherSlotAnalysis {
        val hourly = response.hourly
        val times = hourly?.time.orEmpty()
        if (times.isEmpty()) {
            return fallbackAnalysis(locationName, response.latitude, response.longitude, startHourOfDay, durationMinutes, sessionType)
        }

        val targetCal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffsetFromToday.coerceIn(0, 6))
            set(Calendar.HOUR_OF_DAY, startHourOfDay.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val isoPrefixFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:", Locale.US)
        val targetPrefix = isoPrefixFormat.format(targetCal.time)

        var startIndex = times.indexOfFirst { it.startsWith(targetPrefix) }
        if (startIndex < 0) {
            // Fallback : past_days=3 => 72h avant aujourd'hui 00:00
            startIndex = (72 + dayOffsetFromToday * 24 + startHourOfDay).coerceIn(0, times.lastIndex)
        }

        return extractSlotAnalysis(
            response = response,
            locationName = locationName,
            startIndex = startIndex,
            startHourOfDay = startHourOfDay,
            durationMinutes = durationMinutes,
            sessionType = sessionType,
            fallbackIsoPrefix = targetPrefix,
            minTrackEleMeters = minTrackEleMeters,
            maxTrackEleMeters = maxTrackEleMeters,
            fetchedAtTimeLabel = fetchedAtTimeLabel,
            isFromOfflineCache = isFromOfflineCache
        )
    }

    private fun extractSlotAnalysis(
        response: OpenMeteoForecastResponse,
        locationName: String,
        startIndex: Int,
        startHourOfDay: Int,
        durationMinutes: Int,
        sessionType: RunSessionType,
        fallbackIsoPrefix: String,
        minTrackEleMeters: Int? = null,
        maxTrackEleMeters: Int? = null,
        fetchedAtTimeLabel: String = "",
        isFromOfflineCache: Boolean = false
    ): WeatherSlotAnalysis {
        val hourly = response.hourly
        val times = hourly?.time.orEmpty()
        val slotHoursCount = max(1, (durationMinutes + 59) / 60)
        val endIndex = (startIndex + slotHoursCount - 1).coerceAtMost(times.lastIndex)
        val slotIndices = startIndex..endIndex

        val temps = slotIndices.mapNotNull { hourly?.temperature2m?.getOrNull(it) }
        val appTemps = slotIndices.mapNotNull { hourly?.apparentTemperature?.getOrNull(it) }
        val winds = slotIndices.mapNotNull { hourly?.windSpeed10m?.getOrNull(it) }
        val gusts = slotIndices.mapNotNull { hourly?.windGusts10m?.getOrNull(it) }
        val windDirs = slotIndices.mapNotNull { hourly?.windDirection10m?.getOrNull(it) }
        val rains = slotIndices.mapNotNull { hourly?.precipitation?.getOrNull(it) }
        val rainProbs = slotIndices.mapNotNull { hourly?.precipitationProbability?.getOrNull(it) }
        val humidities = slotIndices.mapNotNull { hourly?.relativeHumidity2m?.getOrNull(it) }
        val uvs = slotIndices.mapNotNull { hourly?.uvIndex?.getOrNull(it) }
        val codes = slotIndices.mapNotNull { hourly?.weatherCode?.getOrNull(it) }
        val radiations = slotIndices.mapNotNull { hourly?.shortwaveRadiation?.getOrNull(it) }

        val avgTemp = if (temps.isNotEmpty()) temps.average() else 12.0
        val avgAppTemp = if (appTemps.isNotEmpty()) appTemps.average() else avgTemp
        val tempDelta = if (temps.size >= 2) (temps.maxOrNull()!! - temps.minOrNull()!!) else 0.0
        val maxWind = winds.maxOrNull() ?: 10.0
        val maxGust = gusts.maxOrNull() ?: (maxWind * 1.35)
        val slotRain = rains.sum()
        val maxRainProb = rainProbs.maxOrNull() ?: when {
            slotRain >= 2.0 -> 85
            slotRain >= 0.5 -> 60
            slotRain > 0.0 -> 45
            else -> 15
        }
        val avgHumidity = if (humidities.isNotEmpty()) humidities.average().roundToInt().coerceIn(10, 100) else 65
        val dominantWindDeg = if (windDirs.isNotEmpty()) windDirs.first() else 225
        val maxUv = when {
            uvs.isNotEmpty() -> uvs.maxOrNull() ?: 2.0
            radiations.isNotEmpty() -> ((radiations.maxOrNull() ?: 0.0) / 110.0).coerceIn(0.0, 11.0)
            else -> if (startHourOfDay in 10..16) 4.0 else 1.0
        }
        val worstWeatherCode = codes.maxOrNull() ?: when {
            slotRain >= 3.0 -> 63
            slotRain >= 0.3 -> 61
            maxUv >= 5.0 -> 0
            else -> 2
        }

        // Cumul de pluie des 72 heures précédant l'heure de départ
        val rain72hStartIndex = (startIndex - 72).coerceAtLeast(0)
        val rain72hIndices = rain72hStartIndex until startIndex
        val past72hRain = rain72hIndices.mapNotNull { hourly?.precipitation?.getOrNull(it) }.sum()

        val refTemp = computeReferenceTemp(
            realTemp = avgTemp,
            windKmh = maxWind,
            windGustsKmh = maxGust,
            slotRainMm = slotRain,
            humidityPercent = avgHumidity,
            maxUv = maxUv,
            sessionType = sessionType
        )

        // Détail heure par heure sur le créneau
        val hourlySteps = slotIndices.map { idx ->
            val iso = times.getOrNull(idx) ?: ""
            val hourStr = iso.substringAfter('T', "").take(5).ifBlank {
                String.format(Locale.US, "%02d:00", (startHourOfDay + (idx - startIndex)) % 24)
            }
            val t = hourly?.temperature2m?.getOrNull(idx) ?: avgTemp
            val r = hourly?.precipitation?.getOrNull(idx) ?: 0.0
            val rp = hourly?.precipitationProbability?.getOrNull(idx) ?: maxRainProb
            val w = hourly?.windSpeed10m?.getOrNull(idx) ?: maxWind
            val wg = hourly?.windGusts10m?.getOrNull(idx) ?: maxGust
            val wc = hourly?.weatherCode?.getOrNull(idx) ?: worstWeatherCode
            HourlyWeatherStep(
                hourLabel = hourStr.replace(":", "h"),
                tempC = (t * 10.0).roundToInt() / 10.0,
                rainMm = (r * 10.0).roundToInt() / 10.0,
                rainProbPercent = rp.coerceIn(0, 100),
                windKmh = (w * 10.0).roundToInt() / 10.0,
                windGustsKmh = (wg * 10.0).roundToInt() / 10.0,
                weatherCodeLabel = weatherCodeToLabelFr(wc)
            )
        }

        // Estimation de la température au point culminant si une trace GPX avec altitude est chargée
        val baseEle = minTrackEleMeters?.toDouble() ?: response.elevation
        val summitTemp = if (maxTrackEleMeters != null && baseEle != null && maxTrackEleMeters - baseEle >= 150.0) {
            val deltaEle = maxTrackEleMeters - baseEle
            val est = avgTemp - (deltaEle / 100.0) * 0.65
            (est * 10.0).roundToInt() / 10.0
        } else null

        val isNight = startHourOfDay < 7 || (startHourOfDay + slotHoursCount) >= 20

        return WeatherSlotAnalysis(
            locationName = locationName,
            latitude = response.latitude,
            longitude = response.longitude,
            startDateTimeIso = times.getOrNull(startIndex) ?: (fallbackIsoPrefix + "00"),
            durationMinutes = durationMinutes,
            realTempC = (avgTemp * 10.0).roundToInt() / 10.0,
            apparentTempC = (avgAppTemp * 10.0).roundToInt() / 10.0,
            referenceTempC = (refTemp * 10.0).roundToInt() / 10.0,
            maxWindKmh = (maxWind * 10.0).roundToInt() / 10.0,
            maxWindGustsKmh = (maxGust * 10.0).roundToInt() / 10.0,
            windDirectionDegrees = dominantWindDeg,
            windDirectionCardinal = degreesToCardinalFr(dominantWindDeg),
            slotRainMm = (slotRain * 10.0).roundToInt() / 10.0,
            maxRainProbabilityPercent = maxRainProb.coerceIn(0, 100),
            past72hRainMm = (past72hRain * 10.0).roundToInt() / 10.0,
            maxUvIndex = (maxUv * 10.0).roundToInt() / 10.0,
            avgHumidityPercent = avgHumidity,
            weatherCode = worstWeatherCode,
            weatherConditionLabel = weatherCodeToLabelFr(worstWeatherCode),
            tempDeltaAcrossSlotC = (tempDelta * 10.0).roundToInt() / 10.0,
            hourlySteps = hourlySteps,
            summitTempC = summitTemp,
            summitElevationMeters = maxTrackEleMeters,
            fetchedAtTimeLabel = fetchedAtTimeLabel,
            isFromOfflineCache = isFromOfflineCache,
            isNightOrDawn = isNight
        )
    }

    /**
     * Analyse les prévisions d'Aujourd'hui (J0) et Demain (J1) entre 06h00 et 19h00
     * pour identifier les 3 meilleurs créneaux météo pour courir.
     */
    fun findBestWeatherSlots48h(
        response: OpenMeteoForecastResponse,
        durationMinutes: Int,
        sessionType: RunSessionType = RunSessionType.ENDURANCE
    ): List<BestWeatherSlotSuggestion> {
        val hourly = response.hourly ?: return emptyList()
        if (hourly.time.isEmpty()) return emptyList()

        val candidateHours = listOf(7, 9, 11, 14, 16, 18)
        val slotHoursCount = max(1, (durationMinutes + 59) / 60)
        val candidates = mutableListOf<BestWeatherSlotSuggestion>()

        for (dayOffset in 0..1) {
            val dayLabel = if (dayOffset == 0) "Aujourd'hui" else "Demain"
            for (hour in candidateHours) {
                val analysis = analyzeSlotFromResponse(
                    response = response,
                    locationName = "",
                    startHourOfDay = hour,
                    dayOffsetFromToday = dayOffset,
                    durationMinutes = durationMinutes,
                    sessionType = sessionType
                )
                // Score sur 100 : pénalise la pluie, la proba de pluie, le vent fort et l'écart à 13°C
                var score = 100.0
                score -= analysis.slotRainMm * 18.0
                score -= analysis.maxRainProbabilityPercent * 0.35
                if (analysis.maxWindKmh > 15.0) {
                    score -= (analysis.maxWindKmh - 15.0) * 1.2
                }
                score -= abs(analysis.realTempC - 13.5) * 1.8
                if (analysis.maxUvIndex >= 7.0) {
                    score -= 8.0
                }

                candidates.add(
                    BestWeatherSlotSuggestion(
                        dayOffset = dayOffset,
                        dayLabel = dayLabel,
                        startHour = hour,
                        endHour = (hour + slotHoursCount).coerceAtMost(23),
                        avgTempC = analysis.realTempC,
                        maxWindKmh = analysis.maxWindKmh,
                        slotRainMm = analysis.slotRainMm,
                        maxRainProbPercent = analysis.maxRainProbabilityPercent,
                        weatherConditionLabel = analysis.weatherConditionLabel,
                        score = score.roundToInt().coerceIn(5, 100)
                    )
                )
            }
        }

        return candidates
            .sortedByDescending { it.score }
            .take(3)
    }

    fun weatherCodeToLabelFr(code: Int): String = when (code) {
        0 -> "Ciel dégagé"
        1 -> " Peu nuageux".trim()
        2 -> "Éclaircies"
        3 -> "Couvert"
        45, 48 -> "Brouillard"
        51, 53, 55 -> "Bruine"
        56, 57 -> "Bruine verglaçante"
        61 -> "Pluie faible"
        63 -> "Pluie modérée"
        65 -> "Pluie forte"
        66, 67 -> "Pluie verglaçante"
        71, 73, 75, 77 -> "Chutes de neige"
        80 -> "Averses faibles"
        81 -> "Averses modérées"
        82 -> "Averses violentes"
        85, 86 -> "Averses de neige"
        95, 96, 99 -> "Orage"
        else -> "Variable"
    }

    fun degreesToCardinalFr(degrees: Int): String {
        val norm = ((degrees % 360) + 360) % 360
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val idx = ((norm + 22.5) / 45.0).toInt() % 8
        return directions[idx]
    }

    fun computeReferenceTemp(
        realTemp: Double,
        windKmh: Double,
        windGustsKmh: Double = windKmh,
        slotRainMm: Double,
        humidityPercent: Int = 65,
        maxUv: Double,
        sessionType: RunSessionType
    ): Double {
        val safeTemp = if (realTemp.isFinite()) realTemp.coerceIn(-40.0, 55.0) else 12.0
        val safeWind = if (windKmh.isFinite()) windKmh.coerceIn(0.0, 180.0) else 10.0
        val safeGusts = if (windGustsKmh.isFinite()) windGustsKmh.coerceIn(0.0, 220.0) else safeWind
        val safeRain = if (slotRainMm.isFinite()) slotRainMm.coerceIn(0.0, 200.0) else 0.0
        val safeUv = if (maxUv.isFinite()) maxUv.coerceIn(0.0, 16.0) else 1.0

        var ref = safeTemp
        // Effet du vent moyen et des rafales
        val effectiveWind = max(safeWind, safeGusts * 0.75)
        if (effectiveWind >= 35.0) ref -= 3.5
        else if (effectiveWind >= 20.0) ref -= 2.0
        else if (effectiveWind >= 12.0) ref -= 1.0

        // Effet de la pluie sur le créneau
        if (safeRain >= 3.0) ref -= 2.5
        else if (safeRain >= 0.5) ref -= 1.5

        // Effet de l'humidité (froid humide < 6°C refroidit, chaleur humide > 18°C alourdit)
        if (humidityPercent >= 82 && safeTemp <= 6.0) {
            ref -= 1.0
        } else if (humidityPercent >= 78 && safeTemp >= 18.0) {
            ref += 1.0
        }

        // Effet du soleil direct
        if (safeUv >= 6.0 && safeRain < 0.2) ref += 2.0
        else if (safeUv >= 3.0 && safeRain < 0.2) ref += 1.0

        // Intensité de la séance (Course / Fractionné réchauffe davantage)
        ref += sessionType.tempOffset
        return ref
    }

    fun fallbackAnalysis(
        locationName: String,
        latitude: Double,
        longitude: Double,
        startHourOfDay: Int,
        durationMinutes: Int,
        sessionType: RunSessionType
    ): WeatherSlotAnalysis {
        val realTemp = 11.0
        val wind = 15.0
        val rain = 0.0
        val uv = if (startHourOfDay in 10..16) 4.0 else 1.0
        val refTemp = computeReferenceTemp(
            realTemp = realTemp,
            windKmh = wind,
            slotRainMm = rain,
            maxUv = uv,
            sessionType = sessionType
        )
        return WeatherSlotAnalysis(
            locationName = locationName,
            latitude = latitude,
            longitude = longitude,
            startDateTimeIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + "T${String.format(Locale.US, "%02d", startHourOfDay)}:00",
            durationMinutes = durationMinutes,
            realTempC = realTemp,
            apparentTempC = 10.0,
            referenceTempC = (refTemp * 10.0).roundToInt() / 10.0,
            maxWindKmh = wind,
            maxWindGustsKmh = 22.0,
            slotRainMm = rain,
            past72hRainMm = 8.5,
            maxUvIndex = uv,
            weatherConditionLabel = "Variable (hors-ligne)",
            isFromOfflineCache = true,
            isNightOrDawn = startHourOfDay < 7 || startHourOfDay >= 20
        )
    }
}
