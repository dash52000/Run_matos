package com.example.data.remote

import com.example.data.model.BestWeatherSlotSuggestion
import com.example.data.model.GeoLocationResult
import com.example.data.model.HourlyWeatherStep
import com.example.data.model.RunSessionType
import com.example.data.model.WeatherSlotAnalysis
import com.example.domain.SunTimes
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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

/**
 * Règle d'utilisation du cache météo hors-ligne : uniquement pour le même lieu (≤ 5 km)
 * et s'il a moins de 12 h. Au-delà, l'appli affiche « météo indisponible ».
 */
object WeatherCachePolicy {
    const val MAX_AGE_MILLIS = 12L * 3600L * 1000L
    const val MAX_DISTANCE_METERS = 5000.0

    fun isUsable(
        cachedLat: Double,
        cachedLon: Double,
        cachedAtMillis: Long,
        latitude: Double,
        longitude: Double,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!cachedLat.isFinite() || !cachedLon.isFinite() || cachedAtMillis <= 0L) return false
        val age = nowMillis - cachedAtMillis
        if (age < 0L || age > MAX_AGE_MILLIS) return false
        val dist = GpxAndOverpassAnalyzer.haversineMeters(cachedLat, cachedLon, latitude, longitude)
        return dist.isFinite() && dist <= MAX_DISTANCE_METERS
    }
}

object OpenMeteoClient {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val forecastAdapter by lazy {
        moshi.adapter(OpenMeteoForecastResponse::class.java)
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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
                    delay(600L)
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
     * Récupère la météo réelle à une date passée (et les 72 h précédentes pour le cumul de boue)
     * via Open-Meteo (Forecast API pour les ~80 derniers jours, Archive ERA5 au-delà).
     * Lève une exception si le réseau est indisponible ; renvoie null si les données manquent.
     */
    suspend fun fetchHistoricalSlotWeather(
        locationName: String,
        latitude: Double,
        longitude: Double,
        targetDateMillis: Long,
        startHourOfDay: Int,
        durationMinutes: Int,
        sessionType: RunSessionType
    ): WeatherSlotAnalysis? {
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
            targetDate = endCal,
            startHourOfDay = startHourOfDay,
            durationMinutes = durationMinutes,
            sessionType = sessionType
        )
    }

    fun analyzeHistoricalDateSlotFromResponse(
        response: OpenMeteoForecastResponse,
        locationName: String,
        targetDate: Calendar,
        startHourOfDay: Int,
        durationMinutes: Int,
        sessionType: RunSessionType
    ): WeatherSlotAnalysis? {
        val times = response.hourly?.time.orEmpty()
        if (times.isEmpty()) return null

        val hourPadded = String.format(Locale.US, "%02d", startHourOfDay.coerceIn(0, 23))
        val targetDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(targetDate.time)
        val targetPrefix = "${targetDateStr}T$hourPadded:"

        val startIndex = times.indexOfFirst { it.startsWith(targetPrefix) }
        if (startIndex < 0) return null

        return extractSlotAnalysis(
            response = response,
            locationName = locationName,
            startIndex = startIndex,
            startHourOfDay = startHourOfDay,
            startMinute = 0,
            durationMinutes = durationMinutes,
            sessionType = sessionType,
            targetDate = targetDate
        )
    }

    /**
     * Analyse le créneau choisi et calcule :
     * - Cumul de pluie des 72 h précédant le départ (pluie prévue incluse)
     * - Température réelle, ressentie et amplitude sur le créneau
     * - Température de référence (ajustée selon vent, rafales, pluie, humidité, UV et type de séance)
     * - Probabilité de pluie (%), direction dominante du vent, rafales et code météo WMO le plus défavorable
     * - Évolution heure par heure et température estimée au point culminant GPX
     * - Nuit / jour d'après le lever et le coucher du soleil calculés pour le lieu et la date
     * Renvoie null si la réponse ne couvre pas le créneau demandé (aucune valeur inventée).
     */
    fun analyzeSlotFromResponse(
        response: OpenMeteoForecastResponse,
        locationName: String,
        startHourOfDay: Int,
        dayOffsetFromToday: Int,
        durationMinutes: Int,
        sessionType: RunSessionType,
        startMinute: Int = 0,
        minTrackEleMeters: Int? = null,
        maxTrackEleMeters: Int? = null,
        forestShare: Double = 0.0,
        fetchedAtTimeLabel: String = "",
        isFromOfflineCache: Boolean = false,
        now: Calendar = Calendar.getInstance()
    ): WeatherSlotAnalysis? {
        val times = response.hourly?.time.orEmpty()
        if (times.isEmpty()) return null

        val targetCal = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffsetFromToday.coerceIn(0, 6))
            set(Calendar.HOUR_OF_DAY, startHourOfDay.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val isoPrefixFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:", Locale.US)
        val targetPrefix = isoPrefixFormat.format(targetCal.time)

        val startIndex = times.indexOfFirst { it.startsWith(targetPrefix) }
        if (startIndex < 0) return null

        return extractSlotAnalysis(
            response = response,
            locationName = locationName,
            startIndex = startIndex,
            startHourOfDay = startHourOfDay,
            startMinute = startMinute,
            durationMinutes = durationMinutes,
            sessionType = sessionType,
            targetDate = targetCal,
            minTrackEleMeters = minTrackEleMeters,
            maxTrackEleMeters = maxTrackEleMeters,
            forestShare = forestShare,
            fetchedAtTimeLabel = fetchedAtTimeLabel,
            isFromOfflineCache = isFromOfflineCache
        )
    }

    private fun extractSlotAnalysis(
        response: OpenMeteoForecastResponse,
        locationName: String,
        startIndex: Int,
        startHourOfDay: Int,
        startMinute: Int,
        durationMinutes: Int,
        sessionType: RunSessionType,
        targetDate: Calendar,
        minTrackEleMeters: Int? = null,
        maxTrackEleMeters: Int? = null,
        forestShare: Double = 0.0,
        fetchedAtTimeLabel: String = "",
        isFromOfflineCache: Boolean = false
    ): WeatherSlotAnalysis? {
        val hourly = response.hourly ?: return null
        val times = hourly.time
        val safeStartMinute = startMinute.coerceIn(0, 59)
        val slotHoursCount = max(1, (safeStartMinute + durationMinutes + 59) / 60)
        val endIndex = (startIndex + slotHoursCount - 1).coerceAtMost(times.lastIndex)
        val slotIndices = startIndex..endIndex

        val temps = slotIndices.mapNotNull { hourly.temperature2m.getOrNull(it) }
        // Sans température sur le créneau, on ne recommande rien plutôt que d'inventer une valeur.
        if (temps.isEmpty()) return null

        val appTemps = slotIndices.mapNotNull { hourly.apparentTemperature.getOrNull(it) }
        val winds = slotIndices.mapNotNull { hourly.windSpeed10m.getOrNull(it) }
        val gusts = slotIndices.mapNotNull { hourly.windGusts10m.getOrNull(it) }
        val rains = slotIndices.mapNotNull { hourly.precipitation.getOrNull(it) }
        val rainProbs = slotIndices.mapNotNull { hourly.precipitationProbability.getOrNull(it) }
        val humidities = slotIndices.mapNotNull { hourly.relativeHumidity2m.getOrNull(it) }
        val uvs = slotIndices.mapNotNull { hourly.uvIndex.getOrNull(it) }
        val codes = slotIndices.mapNotNull { hourly.weatherCode.getOrNull(it) }
        val radiations = slotIndices.mapNotNull { hourly.shortwaveRadiation.getOrNull(it) }

        val avgTemp = temps.average()
        val avgAppTemp = if (appTemps.isNotEmpty()) appTemps.average() else avgTemp
        val tempDelta = if (temps.size >= 2) (temps.max() - temps.min()) else 0.0
        val maxWind = winds.maxOrNull() ?: 0.0
        val maxGust = gusts.maxOrNull() ?: maxWind
        val slotRain = rains.sum()
        // Sorties passées (archive) : pas de probabilité, la pluie observée fait foi.
        val maxRainProb = rainProbs.maxOrNull() ?: if (slotRain >= 0.1) 100 else 0
        val avgHumidity = if (humidities.isNotEmpty()) humidities.average().roundToInt().coerceIn(1, 100) else 65
        val dominantWindDeg = dominantWindDirection(
            directions = slotIndices.map { hourly.windDirection10m.getOrNull(it) },
            speeds = slotIndices.map { hourly.windSpeed10m.getOrNull(it) }
        )
        // Indice UV : direct en prévision ; pour l'archive ERA5 (sans UV), approximation par le rayonnement global.
        val maxUv = when {
            uvs.isNotEmpty() -> uvs.max()
            radiations.isNotEmpty() -> (radiations.max() / 110.0).coerceIn(0.0, 11.0)
            else -> 0.0
        }
        val worstWeatherCode = codes.maxByOrNull { weatherCodeSeverity(it) } ?: -1

        // Cumul de pluie des 72 heures précédant l'heure de départ
        val rain72hStartIndex = (startIndex - 72).coerceAtLeast(0)
        val past72hRain = (rain72hStartIndex until startIndex)
            .mapNotNull { hourly.precipitation.getOrNull(it) }
            .sum()

        val refTemp = computeReferenceTemp(
            realTemp = avgTemp,
            windKmh = maxWind,
            windGustsKmh = maxGust,
            slotRainMm = slotRain,
            humidityPercent = avgHumidity,
            maxUv = maxUv,
            sessionType = sessionType,
            forestShare = forestShare
        )

        // Détail heure par heure sur le créneau
        val hourlySteps = slotIndices.map { idx ->
            val iso = times.getOrNull(idx) ?: ""
            val hourStr = iso.substringAfter('T', "").take(5).ifBlank {
                String.format(Locale.US, "%02d:00", (startHourOfDay + (idx - startIndex)) % 24)
            }
            val t = hourly.temperature2m.getOrNull(idx) ?: avgTemp
            val r = hourly.precipitation.getOrNull(idx) ?: 0.0
            val rp = hourly.precipitationProbability.getOrNull(idx) ?: maxRainProb
            val w = hourly.windSpeed10m.getOrNull(idx) ?: maxWind
            val wg = hourly.windGusts10m.getOrNull(idx) ?: maxGust
            val wc = hourly.weatherCode.getOrNull(idx) ?: worstWeatherCode
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

        // Estimation de la température au point culminant (gradient standard 0,65 °C / 100 m)
        val baseEle = minTrackEleMeters?.toDouble() ?: response.elevation
        val summitTemp = if (maxTrackEleMeters != null && baseEle != null && maxTrackEleMeters - baseEle >= 150.0) {
            val deltaEle = maxTrackEleMeters - baseEle
            val est = avgTemp - (deltaEle / 100.0) * 0.65
            (est * 10.0).roundToInt() / 10.0
        } else null

        val sun = SunTimes.compute(
            latitude = response.latitude,
            longitude = response.longitude,
            year = targetDate.get(Calendar.YEAR),
            month = targetDate.get(Calendar.MONTH) + 1,
            dayOfMonth = targetDate.get(Calendar.DAY_OF_MONTH)
        )
        val isNight = SunTimes.isSlotInDarkness(
            times = sun,
            startMinuteOfDay = startHourOfDay.coerceIn(0, 23) * 60 + safeStartMinute,
            durationMinutes = durationMinutes
        )

        return WeatherSlotAnalysis(
            locationName = locationName,
            latitude = response.latitude,
            longitude = response.longitude,
            startDateTimeIso = times.getOrNull(startIndex) ?: "",
            durationMinutes = durationMinutes,
            realTempC = (avgTemp * 10.0).roundToInt() / 10.0,
            apparentTempC = (avgAppTemp * 10.0).roundToInt() / 10.0,
            referenceTempC = (refTemp * 10.0).roundToInt() / 10.0,
            maxWindKmh = (maxWind * 10.0).roundToInt() / 10.0,
            maxWindGustsKmh = (maxGust * 10.0).roundToInt() / 10.0,
            windDirectionDegrees = dominantWindDeg ?: -1,
            windDirectionCardinal = dominantWindDeg?.let { degreesToCardinalFr(it) } ?: "—",
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
            isNightOrDawn = isNight,
            sunriseLabel = sun?.let { SunTimes.formatMinutes(it.sunriseMinutes) },
            sunsetLabel = sun?.let { SunTimes.formatMinutes(it.sunsetMinutes) }
        )
    }

    /**
     * Direction dominante = moyenne vectorielle des directions horaires pondérées par la vitesse du vent.
     * Renvoie null si aucune direction n'est disponible.
     */
    fun dominantWindDirection(directions: List<Int?>, speeds: List<Double?>): Int? {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        directions.forEachIndexed { i, dir ->
            if (dir != null) {
                val weight = (speeds.getOrNull(i) ?: 1.0).coerceAtLeast(0.1)
                val rad = Math.toRadians(dir.toDouble())
                sumX += sin(rad) * weight
                sumY += cos(rad) * weight
                count++
            }
        }
        if (count == 0) return null
        if (abs(sumX) < 1e-9 && abs(sumY) < 1e-9) return directions.firstOrNull { it != null }
        val deg = Math.toDegrees(atan2(sumX, sumY))
        return ((deg.roundToInt() % 360) + 360) % 360
    }

    /** Gravité d'un code météo WMO, pour retenir la condition la plus défavorable du créneau. */
    fun weatherCodeSeverity(code: Int): Int = when (code) {
        95, 96, 99 -> 100
        56, 57, 66, 67 -> 90
        75, 86 -> 85
        73, 85 -> 80
        71, 77 -> 75
        65, 82 -> 70
        63, 81 -> 60
        61, 80 -> 50
        55 -> 45
        53 -> 42
        51 -> 40
        45, 48 -> 30
        3 -> 20
        2 -> 15
        1 -> 10
        0 -> 0
        else -> 5
    }

    /**
     * Les 3 meilleurs créneaux d'aujourd'hui et demain, en excluant les créneaux déjà passés
     * et ceux qui tombent de nuit (lever/coucher du soleil + marge de crépuscule).
     */
    fun findBestWeatherSlots48h(
        response: OpenMeteoForecastResponse,
        durationMinutes: Int,
        sessionType: RunSessionType = RunSessionType.ENDURANCE,
        forestShare: Double = 0.0,
        now: Calendar = Calendar.getInstance()
    ): List<BestWeatherSlotSuggestion> {
        val hourly = response.hourly ?: return emptyList()
        if (hourly.time.isEmpty()) return emptyList()

        val candidateHours = listOf(7, 9, 11, 14, 16, 18)
        val slotHoursCount = max(1, (durationMinutes + 59) / 60)
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val candidates = mutableListOf<BestWeatherSlotSuggestion>()

        for (dayOffset in 0..1) {
            val dayLabel = if (dayOffset == 0) "Aujourd'hui" else "Demain"
            for (hour in candidateHours) {
                if (dayOffset == 0 && hour * 60 < nowMinutes + 15) continue
                val analysis = analyzeSlotFromResponse(
                    response = response,
                    locationName = "",
                    startHourOfDay = hour,
                    dayOffsetFromToday = dayOffset,
                    durationMinutes = durationMinutes,
                    sessionType = sessionType,
                    forestShare = forestShare,
                    now = now
                ) ?: continue
                if (analysis.isNightOrDawn) continue

                // Score sur 100 : pénalise la pluie, la proba de pluie, le vent fort et l'écart à ~13 °C
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
        1 -> "Peu nuageux"
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
        -1 -> "Conditions inconnues"
        else -> "Variable"
    }

    fun degreesToCardinalFr(degrees: Int): String {
        val norm = ((degrees % 360) + 360) % 360
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val idx = ((norm + 22.5) / 45.0).toInt() % 8
        return directions[idx]
    }

    /**
     * Température de référence pour choisir la tenue.
     * [forestShare] (0..1) = part de la trace en forêt : le vent y est compté moitié moins.
     */
    fun computeReferenceTemp(
        realTemp: Double,
        windKmh: Double,
        windGustsKmh: Double = windKmh,
        slotRainMm: Double,
        humidityPercent: Int = 65,
        maxUv: Double,
        sessionType: RunSessionType,
        forestShare: Double = 0.0
    ): Double {
        val safeTemp = if (realTemp.isFinite()) realTemp.coerceIn(-40.0, 55.0) else 12.0
        val safeWind = if (windKmh.isFinite()) windKmh.coerceIn(0.0, 180.0) else 0.0
        val safeGusts = if (windGustsKmh.isFinite()) windGustsKmh.coerceIn(0.0, 220.0) else safeWind
        val safeRain = if (slotRainMm.isFinite()) slotRainMm.coerceIn(0.0, 200.0) else 0.0
        val safeUv = if (maxUv.isFinite()) maxUv.coerceIn(0.0, 16.0) else 0.0
        val safeForest = if (forestShare.isFinite()) forestShare.coerceIn(0.0, 1.0) else 0.0

        var ref = safeTemp
        // Effet du vent moyen et des rafales (réduit de moitié sur la part en forêt)
        val effectiveWind = max(safeWind, safeGusts * 0.75) * (1.0 - 0.5 * safeForest)
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
}
