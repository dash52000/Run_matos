package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ShoeEntity
import com.example.data.model.GpxSurfaceBreakdown
import com.example.data.model.GpxTrackAnalysis
import com.example.data.model.MudLevel
import com.example.data.model.ShoeType
import com.example.data.model.SurfaceCategory
import com.example.data.model.TerrainType
import com.example.data.remote.GpxAndOverpassAnalyzer
import com.example.domain.RecommendationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("RunCoach Matos", appName)
    }

    @Test
    fun `parse sample GPX extracts distance, elevation gain D+ and start point`() {
        val rawXml = GpxAndOverpassAnalyzer.getSampleTrailGpxXml()
        val parsed = GpxAndOverpassAnalyzer.parseGpxContent(rawXml, "Test.gpx")
        assertNotNull(parsed)
        assertTrue(parsed!!.totalDistanceKm > 0.5)
        assertTrue(parsed.elevationGainDPlusMeters > 100)
        assertEquals(45.9237, parsed.startLat, 0.001)
        assertEquals(6.8694, parsed.startLon, 0.001)
    }

    @Test
    fun `classify OSM tags follows exact surface rules`() {
        // Bitume : surface = asphalt / highway = residential without surface / tracktype = grade1
        assertEquals(
            SurfaceCategory.BITUME,
            GpxAndOverpassAnalyzer.classifyWayTags(mapOf("highway" to "path", "surface" to "asphalt"))
        )
        assertEquals(
            SurfaceCategory.BITUME,
            GpxAndOverpassAnalyzer.classifyWayTags(mapOf("highway" to "residential"))
        )
        assertEquals(
            SurfaceCategory.BITUME,
            GpxAndOverpassAnalyzer.classifyWayTags(mapOf("highway" to "track", "tracktype" to "grade1"))
        )

        // Chemin stabilisé : surface = gravel / tracktype = grade2 / highway = track sans autre info
        assertEquals(
            SurfaceCategory.CHEMIN_STABILISE,
            GpxAndOverpassAnalyzer.classifyWayTags(mapOf("highway" to "footway", "surface" to "fine_gravel"))
        )
        assertEquals(
            SurfaceCategory.CHEMIN_STABILISE,
            GpxAndOverpassAnalyzer.classifyWayTags(mapOf("highway" to "track", "tracktype" to "grade2"))
        )
        assertEquals(
            SurfaceCategory.CHEMIN_STABILISE,
            GpxAndOverpassAnalyzer.classifyWayTags(mapOf("highway" to "track"))
        )

        // Terre / forêt : surface = dirt / highway = path / tracktype = grade3..grade5
        assertEquals(
            SurfaceCategory.TERRE_FORET,
            GpxAndOverpassAnalyzer.classifyWayTags(mapOf("highway" to "track", "surface" to "mud"))
        )
        assertEquals(
            SurfaceCategory.TERRE_FORET,
            GpxAndOverpassAnalyzer.classifyWayTags(mapOf("highway" to "path"))
        )
        assertEquals(
            SurfaceCategory.TERRE_FORET,
            GpxAndOverpassAnalyzer.classifyWayTags(mapOf("highway" to "track", "tracktype" to "grade4"))
        )
    }

    @Test
    fun `shoe selection with GPX trace selects road shoes on dry terrain when asphalt is at least threshold`() {
        val shoes = listOf(
            ShoeEntity(id = 1, name = "Trail Poly", type = ShoeType.TRAIL_POLYVALENT, currentKm = 100.0, maxKm = 700.0),
            ShoeEntity(id = 2, name = "Road Shoe", type = ShoeType.ROUTE, currentKm = 200.0, maxKm = 700.0),
            ShoeEntity(id = 3, name = "Mud Crampons", type = ShoeType.TRAIL_CRAMPONS, currentKm = 50.0, maxKm = 700.0)
        )

        val roadDominantTrack = GpxTrackAnalysis(
            trackIdHash = "h1",
            trackName = "Mixte 68% bitume",
            points = emptyList(),
            totalDistanceKm = 15.0,
            elevationGainDPlusMeters = 220,
            startLat = 45.9,
            startLon = 6.1,
            surfaceBreakdown = GpxSurfaceBreakdown(
                bitumeMeters = 6800.0,
                cheminStabiliseMeters = 2000.0,
                terreForetMeters = 1000.0,
                inconnuMeters = 200.0,
                totalMeters = 10000.0,
                bitumePercent = 68.0,
                cheminStabilisePercent = 20.0,
                terreForetPercent = 10.0,
                inconnuPercent = 2.0,
                isUnreliable = false,
                coloredSegments = emptyList()
            )
        )

        // Terrain sec + bitume >= 60% -> chaussures de route même si terrain sélectionné est TRAIL
        val (selectedDry, _) = RecommendationEngine.selectBestShoe(
            shoes = shoes,
            terrain = TerrainType.TRAIL,
            mudLevel = MudLevel.SEC,
            gpxTrack = roadDominantTrack,
            asphaltThresholdPercent = 60
        )
        assertEquals(ShoeType.ROUTE, selectedDry?.type)

        // Terrain boueux + bitume >= 60% -> règles actuelles (crampons)
        val (selectedMuddy, _) = RecommendationEngine.selectBestShoe(
            shoes = shoes,
            terrain = TerrainType.TRAIL,
            mudLevel = MudLevel.BOUEUX,
            gpxTrack = roadDominantTrack,
            asphaltThresholdPercent = 60
        )
        assertEquals(ShoeType.TRAIL_CRAMPONS, selectedMuddy?.type)

        // Inconnu > 30% -> analyse peu fiable -> règles actuelles (trail polyvalent sur terrain sec)
        val unreliableTrack = roadDominantTrack.copy(
            surfaceBreakdown = roadDominantTrack.surfaceBreakdown!!.copy(
                bitumePercent = 62.0,
                inconnuPercent = 35.0,
                isUnreliable = true
            )
        )
        val (selectedUnreliable, reasonUnreliable) = RecommendationEngine.selectBestShoe(
            shoes = shoes,
            terrain = TerrainType.TRAIL,
            mudLevel = MudLevel.SEC,
            gpxTrack = unreliableTrack,
            asphaltThresholdPercent = 60
        )
        assertEquals(ShoeType.TRAIL_POLYVALENT, selectedUnreliable?.type)
        assertTrue(reasonUnreliable.contains("peu fiable"))
    }
}
