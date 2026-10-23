package com.example

import com.example.data.local.BackupManager
import com.example.data.local.ChecklistItemEntity
import com.example.data.local.GearItemEntity
import com.example.data.local.RunHistoryEntity
import com.example.data.local.ShoeEntity
import com.example.data.model.ChecklistType
import com.example.data.model.GearCategory
import com.example.data.model.GelFeedback
import com.example.data.model.MudLevel
import com.example.data.model.RunSessionType
import com.example.data.model.ShoeType
import com.example.data.model.SurfaceCategory
import com.example.data.model.TerrainType
import com.example.data.model.ThermalFeeling
import com.example.data.remote.GpxAndOverpassAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GpxAndBackupTest {

    private fun trkpts(points: List<Triple<Double, Double, Double>>): String =
        points.joinToString("\n") { (lat, lon, ele) -> """<trkpt lat="$lat" lon="$lon"><ele>$ele</ele></trkpt>""" }

    @Test
    fun waypoints_are_ignored_for_distance_and_start_point() {
        val track = trkpts(
            listOf(
                Triple(49.5300, 5.8300, 300.0),
                Triple(49.5350, 5.8300, 305.0),
                Triple(49.5400, 5.8300, 310.0)
            )
        )
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test">
              <wpt lat="49.9000" lon="6.2000"><ele>500</ele><name>Ravito 1</name></wpt>
              <wpt lat="49.1000" lon="5.4000"><ele>200</ele><name>Ravito 2</name></wpt>
              <trk><name>Trail de test</name><trkseg>
              $track
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val parsed = GpxAndOverpassAnalyzer.parseGpxContent(xml, "test.gpx")
        assertNotNull(parsed)
        assertEquals("Trail de test", parsed!!.trackName)
        assertEquals(49.5300, parsed.startLat, 1e-6)
        assertEquals(5.8300, parsed.startLon, 1e-6)
        assertEquals(1.1, parsed.totalDistanceKm, 0.05) // ~1,1 km, sans les sauts vers les waypoints
        assertEquals(3, parsed.points.size)
    }

    @Test
    fun route_points_are_used_when_there_is_no_track() {
        val xml = """
            <gpx version="1.1"><rte><name>Itinéraire</name>
              <rtept lat="49.53" lon="5.83"></rtept>
              <rtept lat="49.54" lon="5.83"></rtept>
            </rte></gpx>
        """.trimIndent()
        val parsed = GpxAndOverpassAnalyzer.parseGpxContent(xml)
        assertNotNull(parsed)
        assertEquals(1.1, parsed!!.totalDistanceKm, 0.05)
    }

    @Test
    fun elevation_noise_below_three_meters_is_not_counted_as_climb() {
        // Terrain plat avec bruit de ±2 m : D+ ~ 0 (l'ancien seuil de 1 m comptait ~2 m à chaque oscillation)
        val noisy = (0 until 200).map { i ->
            Triple(49.53 + i * 0.0001, 5.83, 300.0 + if (i % 2 == 0) 0.0 else 2.0)
        }
        val flat = GpxAndOverpassAnalyzer.parseGpxContent("<gpx><trk><trkseg>${trkpts(noisy)}</trkseg></trk></gpx>")
        assertNotNull(flat)
        assertTrue("D+ = ${flat!!.elevationGainDPlusMeters}", flat.elevationGainDPlusMeters <= 3)

        // Montée régulière de 50 m par pas de 1 m : comptée en entier
        val climb = (0..50).map { i -> Triple(49.53 + i * 0.0002, 5.83, 300.0 + i) }
        val climbing = GpxAndOverpassAnalyzer.parseGpxContent("<gpx><trk><trkseg>${trkpts(climb)}</trkseg></trk></gpx>")
        assertTrue("D+ = ${climbing!!.elevationGainDPlusMeters}", climbing.elevationGainDPlusMeters in 48..50)
    }

    @Test
    fun a_track_without_length_is_rejected_instead_of_getting_a_fake_distance() {
        val xml = "<gpx><trk><trkseg>${trkpts(listOf(Triple(49.53, 5.83, 300.0), Triple(49.53, 5.83, 300.0)))}</trkseg></trk></gpx>"
        assertNull(GpxAndOverpassAnalyzer.parseGpxContent(xml))
    }

    @Test
    fun manual_surface_does_not_paint_fake_geography() {
        val climb = (0..50).map { i -> Triple(49.53 + i * 0.0002, 5.83, 300.0 + i) }
        val track = GpxAndOverpassAnalyzer.parseGpxContent("<gpx><trk><trkseg>${trkpts(climb)}</trkseg></trk></gpx>")!!
        val manual = GpxAndOverpassAnalyzer.buildCustomSurfaceBreakdown(track, 60.0, 30.0, 10.0)
        assertEquals(60.0, manual.bitumePercent, 0.1)
        assertTrue(manual.coloredSegments.all { it.category == SurfaceCategory.INCONNU })

        val empty = GpxAndOverpassAnalyzer.buildCustomSurfaceBreakdown(track, 0.0, 0.0, 0.0)
        assertEquals(100.0, empty.inconnuPercent, 0.1)
    }

    @Test
    fun backup_round_trip_keeps_all_data() {
        val content = BackupManager.BackupContent(
            shoes = listOf(ShoeEntity(id = 7, name = "Hoka Speedgoat 6", type = ShoeType.TRAIL_POLYVALENT, currentKm = 123.4, maxKm = 800.0)),
            gearItems = listOf(GearItemEntity("frontale", "Lampe frontale", GearCategory.TRAIL_SPECIFIQUE, true)),
            checklistItems = listOf(ChecklistItemEntity(id = 3, listType = ChecklistType.BEFORE_START, label = "Clés", checked = true)),
            runHistory = listOf(
                RunHistoryEntity(
                    id = 11,
                    dateEpochMillis = 1_790_000_000_000L,
                    dateFormatted = "15 nov. 2026",
                    locationName = "Virton",
                    sessionType = RunSessionType.COURSE,
                    terrain = TerrainType.TRAIL,
                    plannedDistanceKm = 21.0,
                    actualDistanceKm = 21.3,
                    elevationGainDPlusMeters = 510,
                    gpxSurfaceSummary = "GPX test",
                    durationMinutes = 140,
                    realTempC = 6.5,
                    referenceTempC = 9.5,
                    windKmh = 14.0,
                    rainMm = 0.4,
                    rain72hMm = 12.0,
                    mudLevel = MudLevel.HUMIDE,
                    maxUvIndex = 1.2,
                    outfitWorn = "Maillot manches longues",
                    shoeId = 7,
                    shoeName = "Hoka Speedgoat 6",
                    shoeType = ShoeType.TRAIL_POLYVALENT,
                    gelsCarried = 4,
                    carbRateUsed = 45,
                    thermalFeeling = ThermalFeeling.PARFAIT,
                    gelFeedback = GelFeedback.PILE,
                    notes = "Allure libre"
                )
            ),
            settings = mapOf("carb_rate_medium" to 50, "thermal_profile" to "FRILEUX")
        )
        val restored = BackupManager.fromJson(BackupManager.toJson(content))
        assertEquals(content.shoes, restored.shoes)
        assertEquals(content.gearItems, restored.gearItems)
        assertEquals(content.checklistItems, restored.checklistItems)
        assertEquals(content.runHistory, restored.runHistory)
        assertEquals(content.settings, restored.settings)
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_random_json_file_is_refused() {
        BackupManager.fromJson("""{"hello": "world"}""")
    }
}
