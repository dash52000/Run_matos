package com.example

import com.example.data.local.ShoeEntity
import com.example.data.model.MudLevel
import com.example.data.model.RetroplanningConfig
import com.example.data.model.ShoeType
import com.example.data.model.TerrainType
import com.example.domain.RecommendationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun mudAlert_thresholds_are_accurate() {
        val dry = RecommendationEngine.computeMudAlert(TerrainType.TRAIL, 3.2, setOf("guetres"))
        assertEquals(MudLevel.SEC, dry.mudLevel)

        val wet = RecommendationEngine.computeMudAlert(TerrainType.TRAIL, 10.0, setOf("guetres"))
        assertEquals(MudLevel.HUMIDE, wet.mudLevel)

        val muddy = RecommendationEngine.computeMudAlert(TerrainType.TRAIL, 18.5, setOf("guetres"))
        assertEquals(MudLevel.BOUEUX, muddy.mudLevel)
        assertTrue(muddy.recommendCrampons)
        assertTrue(muddy.recommendGaiters)
    }

    @Test
    fun shoeSelection_picks_matching_type_and_least_worn() {
        val shoes = listOf(
            ShoeEntity(id = 1, name = "Trail A", type = ShoeType.TRAIL_POLYVALENT, currentKm = 400.0, maxKm = 700.0),
            ShoeEntity(id = 2, name = "Trail B", type = ShoeType.TRAIL_POLYVALENT, currentKm = 150.0, maxKm = 700.0),
            ShoeEntity(id = 3, name = "Mud C", type = ShoeType.TRAIL_CRAMPONS, currentKm = 650.0, maxKm = 700.0)
        )

        val (dryShoe, _) = RecommendationEngine.selectBestShoe(shoes, TerrainType.TRAIL, MudLevel.SEC)
        assertEquals("Trail B", dryShoe?.name)

        val (mudShoe, _) = RecommendationEngine.selectBestShoe(shoes, TerrainType.TRAIL, MudLevel.BOUEUX)
        assertEquals("Mud C", mudShoe?.name)
        assertTrue(mudShoe?.isOver90Percent == true)
    }

    @Test
    fun uvAlert_thresholds_work_correctly() {
        val low = RecommendationEngine.computeUvAlert(2.0)
        assertEquals(false, low.needsGlassesAndCap)

        val med = RecommendationEngine.computeUvAlert(3.5)
        assertEquals(true, med.needsGlassesAndCap)
        assertEquals(false, med.needsSunscreen)

        val high = RecommendationEngine.computeUvAlert(6.2)
        assertEquals(true, high.needsGlassesAndCap)
        assertEquals(true, high.needsSunscreen)
    }

    @Test
    fun nutrition_brackets_and_retroplanning_are_accurate() {
        // 10 km trail (~60 min) -> 1 gel de 25g (et non 3 gels)
        val tenKmTrail = RecommendationEngine.computeNutrition(
            durationMinutes = 60,
            carbRateMedium = 45,
            carbRateLong = 75,
            gelCarbsGrams = 25,
            realTempC = 14.0
        )
        assertEquals(45, tenKmTrail.targetCarbPerHour)
        assertEquals(1, tenKmTrail.recommendedGelsCount)

        val medRun = RecommendationEngine.computeNutrition(
            durationMinutes = 120,
            carbRateMedium = 45,
            carbRateLong = 75,
            gelCarbsGrams = 25,
            realTempC = 14.0
        )
        assertEquals(45, medRun.targetCarbPerHour)
        assertEquals(3, medRun.recommendedGelsCount)

        val longRun = RecommendationEngine.computeNutrition(
            durationMinutes = 180,
            carbRateMedium = 45,
            carbRateLong = 75,
            gelCarbsGrams = 25,
            realTempC = 14.0
        )
        assertEquals(75, longRun.targetCarbPerHour)

        val steps = RecommendationEngine.buildRetroplanning(
            RetroplanningConfig(
                raceStartHour = 9,
                raceStartMinute = 0,
                travelMinutes = 45,
                onSiteMarginMinutes = 45,
                breakfastBeforeStartMinutes = 180,
                wakeUpBeforeBreakfastMinutes = 30
            )
        )
        assertEquals(5, steps.size)
        assertEquals("05h30", steps.first().timeFormatted) // Réveil 3h30 avant 9h00
        assertEquals("09h00", steps.last().timeFormatted)  // Départ
    }
}
