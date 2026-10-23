package com.example

import com.example.data.local.DemoDataCleanup
import com.example.data.local.GearItemEntity
import com.example.data.local.RunHistoryEntity
import com.example.data.local.ShoeEntity
import com.example.data.model.GearCategory
import com.example.data.model.GelFeedback
import com.example.data.model.MudLevel
import com.example.data.model.RunSessionType
import com.example.data.model.ShoeType
import com.example.data.model.TerrainType
import com.example.data.model.ThermalFeeling
import com.example.data.model.WeatherSlotAnalysis
import com.example.data.remote.OpenMeteoClient
import com.example.data.remote.OpenMeteoForecastResponse
import com.example.data.remote.OpenMeteoHourly
import com.example.data.remote.WeatherCachePolicy
import com.example.domain.RecommendationEngine
import com.example.domain.SunTimes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/** Tests des corrections de fiabilité (aucune donnée inventée, règles corrigées). */
class ReliabilityRulesTest {

    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private lateinit var previousTz: TimeZone

    @Before
    fun setUp() {
        previousTz = TimeZone.getDefault()
        TimeZone.setDefault(paris)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(previousTz)
    }

    private fun weather(
        realTempC: Double = 10.0,
        referenceTempC: Double = realTempC,
        durationMinutes: Int = 60,
        windKmh: Double = 5.0,
        gustsKmh: Double = 8.0,
        isNight: Boolean = false
    ) = WeatherSlotAnalysis(
        locationName = "Saulnes",
        latitude = 49.53,
        longitude = 5.83,
        startDateTimeIso = "2026-11-15T10:00",
        durationMinutes = durationMinutes,
        realTempC = realTempC,
        apparentTempC = realTempC,
        referenceTempC = referenceTempC,
        maxWindKmh = windKmh,
        maxWindGustsKmh = gustsKmh,
        slotRainMm = 0.0,
        maxRainProbabilityPercent = 0,
        past72hRainMm = 0.0,
        maxUvIndex = 1.0,
        avgHumidityPercent = 70,
        isNightOrDawn = isNight
    )

    private fun gels(durationMinutes: Int) = RecommendationEngine.computeNutrition(
        durationMinutes = durationMinutes,
        carbRateMedium = 45,
        carbRateLong = 75,
        gelCarbsGrams = 25,
        realTempC = 12.0
    ).recommendedGelsCount

    // --- Gels ---

    @Test
    fun gel_count_has_no_jump_at_two_hours_thirty() {
        assertEquals(4, gels(150))
        assertEquals(4, gels(155))
        assertEquals(5, gels(180))
        assertEquals(96.25, RecommendationEngine.computeTotalCarbsGrams(155, 45, 75), 1e-9)
        var previous = gels(60)
        for (d in 65..360 step 5) {
            val current = gels(d)
            assertTrue("Saut de gels entre ${d - 5} et $d min", current - previous <= 1)
            assertTrue(current >= previous)
            previous = current
        }
        assertEquals(0, gels(55))
    }

    @Test
    fun electrolytes_message_does_not_claim_sweating_in_the_cold() {
        val nutrition = RecommendationEngine.computeNutrition(
            durationMinutes = 95,
            carbRateMedium = 45,
            carbRateLong = 75,
            realTempC = 5.0,
            humidityPercent = 60
        )
        assertTrue(nutrition.electrolytesAdvice.isNotBlank())
        assertFalse(nutrition.electrolytesAdvice.contains("sudation"))
    }

    // --- Tenue ---

    private fun outfit(
        w: WeatherSlotAnalysis,
        terrain: TerrainType = TerrainType.TRAIL,
        distanceKm: Double = 10.0,
        dPlus: Int = 0,
        gear: List<GearItemEntity> = emptyList(),
        forestShare: Double = 0.0
    ): Pair<List<String>, List<String>> {
        val mud = RecommendationEngine.computeMudAlert(terrain, w.past72hRainMm, emptySet())
        val uv = RecommendationEngine.computeUvAlert(w.maxUvIndex)
        val nutrition = RecommendationEngine.computeNutrition(w.durationMinutes, 45, 75, realTempC = w.realTempC)
        return RecommendationEngine.buildOutfitAndAccessories(
            weather = w,
            terrain = terrain,
            mudAlert = mud,
            uvAlert = uv,
            nutrition = nutrition,
            gearItems = gear,
            effectiveRefTempC = w.referenceTempC,
            plannedDistanceKm = distanceKm,
            elevationGainDPlusMeters = dPlus,
            forestShare = forestShare
        )
    }

    @Test
    fun poles_are_not_recommended_for_a_runnable_21km_500m_trail() {
        val (_, acc21) = outfit(weather(durationMinutes = 150), distanceKm = 21.0, dPlus = 500)
        assertFalse(acc21.any { it.contains("Bâtons") })

        val (_, accSteep) = outfit(weather(durationMinutes = 90), distanceKm = 10.0, dPlus = 450)
        assertTrue(accSteep.any { it.contains("Bâtons") })

        val (_, accBig) = outfit(weather(durationMinutes = 300), distanceKm = 40.0, dPlus = 1200)
        assertTrue(accBig.any { it.contains("Bâtons") })
    }

    @Test
    fun a_bottom_is_always_proposed_between_4_and_10_degrees() {
        val (outfit7, _) = outfit(weather(realTempC = 7.0))
        assertTrue(outfit7.any { it.contains("Cuissard") || it.contains("Short") || it.contains("Collant") })

        val (outfit2, _) = outfit(weather(realTempC = 2.0))
        assertTrue(outfit2.any { it.contains("Collant") })
    }

    @Test
    fun wind_counts_half_in_forest() {
        val windy = weather(realTempC = 9.0, windKmh = 30.0, gustsKmh = 40.0)
        val (openOutfit, _) = outfit(windy)
        assertTrue(openOutfit.any { it.contains("Coupe-vent") })
        val (forestOutfit, _) = outfit(windy, forestShare = 1.0)
        assertFalse(forestOutfit.any { it.contains("Coupe-vent") })

        val refOpen = OpenMeteoClient.computeReferenceTemp(9.0, 30.0, 40.0, 0.0, 70, 1.0, RunSessionType.ENDURANCE, 0.0)
        val refForest = OpenMeteoClient.computeReferenceTemp(9.0, 30.0, 40.0, 0.0, 70, 1.0, RunSessionType.ENDURANCE, 1.0)
        assertTrue(refForest > refOpen)
    }

    @Test
    fun headlamp_follows_real_daylight() {
        val (_, dark) = outfit(weather(isNight = true))
        assertTrue(dark.any { it.contains("frontale", ignoreCase = true) })
        val (_, day) = outfit(weather(isNight = false))
        assertFalse(day.any { it.contains("frontale", ignoreCase = true) })
    }

    // --- Soleil ---

    @Test
    fun sun_times_match_reference_values_for_saulnes() {
        val winter = SunTimes.compute(49.53, 5.83, 2026, 12, 21, paris)!!
        assertTrue(abs(winter.sunriseMinutes - (8 * 60 + 30)) <= 4)
        assertTrue(abs(winter.sunsetMinutes - (16 * 60 + 39)) <= 4)

        val summer = SunTimes.compute(49.53, 5.83, 2026, 6, 21, paris)!!
        assertTrue(abs(summer.sunriseMinutes - (5 * 60 + 29)) <= 4)
        assertTrue(abs(summer.sunsetMinutes - (21 * 60 + 47)) <= 4)
    }

    @Test
    fun darkness_detection_covers_winter_mornings_and_evenings() {
        val nov15 = SunTimes.compute(49.53, 5.83, 2026, 11, 15, paris)
        assertTrue(SunTimes.isSlotInDarkness(nov15, 17 * 60 + 30, 60))
        assertFalse(SunTimes.isSlotInDarkness(nov15, 12 * 60, 60))

        val dec21 = SunTimes.compute(49.53, 5.83, 2026, 12, 21, paris)
        assertTrue(SunTimes.isSlotInDarkness(dec21, 7 * 60 + 30, 60))

        val jun21 = SunTimes.compute(49.53, 5.83, 2026, 6, 21, paris)
        assertFalse(SunTimes.isSlotInDarkness(jun21, 20 * 60, 60))
    }

    // --- Météo : rien d'inventé ---

    private fun forecastAround(day: Calendar, rainPerHour: Double = 1.0): OpenMeteoForecastResponse {
        val start = (day.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -3)
            set(Calendar.HOUR_OF_DAY, 0)
        }
        val times = mutableListOf<String>()
        val cal = start.clone() as Calendar
        repeat(24 * 7) {
            times.add(
                String.format(
                    "%04d-%02d-%02dT%02d:00",
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY)
                )
            )
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }
        val n = times.size
        return OpenMeteoForecastResponse(
            latitude = 49.53,
            longitude = 5.83,
            elevation = 300.0,
            hourly = OpenMeteoHourly(
                time = times,
                temperature2m = List(n) { 8.0 },
                apparentTemperature = List(n) { 6.0 },
                precipitation = List(n) { rainPerHour },
                precipitationProbability = List(n) { 50 },
                windSpeed10m = List(n) { 10.0 },
                windGusts10m = List(n) { 20.0 },
                windDirection10m = List(n) { 250 },
                relativeHumidity2m = List(n) { 80 },
                uvIndex = List(n) { 1.0 },
                weatherCode = List(n) { if (it % 2 == 0) 65 else 80 }
            )
        )
    }

    private fun now(): Calendar = Calendar.getInstance(paris).apply {
        clear()
        set(2026, Calendar.NOVEMBER, 15, 8, 0, 0)
    }

    @Test
    fun slot_analysis_uses_real_sun_times_and_worst_weather() {
        val resp = forecastAround(now())
        val evening = OpenMeteoClient.analyzeSlotFromResponse(
            response = resp, locationName = "Saulnes", startHourOfDay = 17, startMinute = 30,
            dayOffsetFromToday = 0, durationMinutes = 60, sessionType = RunSessionType.ENDURANCE, now = now()
        )
        assertNotNull(evening)
        assertTrue(evening!!.isNightOrDawn)
        assertEquals(72.0, evening.past72hRainMm, 0.01)
        assertEquals("Pluie forte", evening.weatherConditionLabel)
        assertEquals("O", evening.windDirectionCardinal)

        val noon = OpenMeteoClient.analyzeSlotFromResponse(
            response = resp, locationName = "Saulnes", startHourOfDay = 12,
            dayOffsetFromToday = 0, durationMinutes = 60, sessionType = RunSessionType.ENDURANCE, now = now()
        )
        assertFalse(noon!!.isNightOrDawn)
    }

    @Test
    fun slot_outside_forecast_returns_null_instead_of_a_guess() {
        val oldForecast = forecastAround(Calendar.getInstance(paris).apply {
            clear()
            set(2026, Calendar.OCTOBER, 1, 8, 0, 0)
        })
        val analysis = OpenMeteoClient.analyzeSlotFromResponse(
            response = oldForecast, locationName = "Saulnes", startHourOfDay = 10,
            dayOffsetFromToday = 0, durationMinutes = 60, sessionType = RunSessionType.ENDURANCE, now = now()
        )
        assertNull(analysis)
    }

    @Test
    fun best_slots_skip_past_hours_and_darkness() {
        val afternoon = Calendar.getInstance(paris).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 15, 15, 0, 0)
        }
        val slots = OpenMeteoClient.findBestWeatherSlots48h(forecastAround(afternoon), 60, now = afternoon)
        assertTrue(slots.isNotEmpty())
        assertTrue(slots.none { it.dayOffset == 0 && it.startHour < 15 })
        assertTrue(slots.none { it.startHour == 18 || it.startHour == 7 })
    }

    @Test
    fun weather_cache_is_used_only_for_same_place_and_recent() {
        val now = 1_800_000_000_000L
        assertTrue(WeatherCachePolicy.isUsable(49.53, 5.83, now - 3_600_000L, 49.531, 5.832, now))
        assertFalse(WeatherCachePolicy.isUsable(49.53, 5.83, now - 3_600_000L, 49.62, 5.83, now))
        assertFalse(WeatherCachePolicy.isUsable(49.53, 5.83, now - 13 * 3_600_000L, 49.53, 5.83, now))
        assertFalse(WeatherCachePolicy.isUsable(Double.NaN, 5.83, now, 49.53, 5.83, now))
    }

    @Test
    fun dominant_wind_is_a_vector_mean() {
        assertEquals(0, OpenMeteoClient.dominantWindDirection(listOf(350, 10), listOf(10.0, 10.0)))
        assertEquals(270, OpenMeteoClient.dominantWindDirection(listOf(260, 280), listOf(5.0, 5.0)))
        assertNull(OpenMeteoClient.dominantWindDirection(listOf(null, null), listOf(1.0, 1.0)))
    }

    // --- Données démo de la v1.0.1 ---

    private fun run(location: String, notes: String, shoeId: Long?) = RunHistoryEntity(
        dateEpochMillis = 0L,
        dateFormatted = "",
        locationName = location,
        sessionType = RunSessionType.ENDURANCE,
        terrain = TerrainType.TRAIL,
        plannedDistanceKm = 10.0,
        actualDistanceKm = 10.0,
        durationMinutes = 60,
        realTempC = 10.0,
        referenceTempC = 10.0,
        windKmh = 5.0,
        rainMm = 0.0,
        rain72hMm = 0.0,
        mudLevel = MudLevel.SEC,
        maxUvIndex = 1.0,
        outfitWorn = "",
        shoeId = shoeId,
        shoeName = "",
        shoeType = ShoeType.TRAIL_POLYVALENT,
        gelsCarried = 0,
        carbRateUsed = 45,
        thermalFeeling = ThermalFeeling.PARFAIT,
        gelFeedback = GelFeedback.PILE,
        notes = notes
    )

    @Test
    fun demo_cleanup_removes_only_untouched_demo_data() {
        val demoRun = run("Annecy", "Tour du lac fluide.", shoeId = 4)
        val realRun = run("Saulnes", "AL de Musson", shoeId = 2)
        assertTrue(DemoDataCleanup.isDemoRun(demoRun))
        assertFalse(DemoDataCleanup.isDemoRun(realRun))

        val shoes = listOf(
            ShoeEntity(id = 1, name = "Salomon S/Lab Genesis", type = ShoeType.TRAIL_POLYVALENT, currentKm = 240.0),
            ShoeEntity(id = 2, name = "Salomon Speedcross 6 Mud", type = ShoeType.TRAIL_CRAMPONS, currentKm = 185.0),
            ShoeEntity(id = 3, name = "Hoka Speedgoat 5", type = ShoeType.TRAIL_POLYVALENT, currentKm = 660.0),
            ShoeEntity(id = 4, name = "Nike Pegasus 41 Road", type = ShoeType.ROUTE, currentKm = 310.0),
            ShoeEntity(id = 5, name = "Mes Hoka", type = ShoeType.TRAIL_POLYVALENT, currentKm = 240.0)
        )
        val removed = DemoDataCleanup.unusedDemoShoes(shoes, listOf(demoRun, realRun)).map { it.id }.toSet()
        // 1 et 4 : démo intactes ; 2 : utilisée par une vraie sortie ; 3 : km modifiés ; 5 : vraie paire
        assertEquals(setOf(1L, 4L), removed)

        val allChecked = DemoDataCleanup.defaultGearCodes.map { GearItemEntity(it, it, GearCategory.HAUTS, true) }
        assertTrue(DemoDataCleanup.shouldUncheckDefaultGear(allChecked))
        val touched = allChecked.mapIndexed { i, g -> if (i == 0) g.copy(owned = false) else g }
        assertFalse(DemoDataCleanup.shouldUncheckDefaultGear(touched))
    }
}
