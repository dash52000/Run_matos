package com.example.data.local

import com.example.data.model.GpxColoredSegment
import com.example.data.model.GpxSurfaceBreakdown
import com.example.data.model.SurfaceCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cache disque des analyses de revêtement réussies (une par trace GPX, clé = hash du fichier).
 * Une trace analysée une fois n'a plus besoin des serveurs OpenStreetMap, même après redémarrage.
 */
class SurfaceCacheStore(private val dir: File, private val maxEntries: Int = 40) {

    private fun fileFor(hash: String): File? {
        if (!hash.matches(Regex("[0-9a-f]{4,64}"))) return null
        return File(dir, "$hash.json")
    }

    fun load(hash: String): GpxSurfaceBreakdown? {
        val file = fileFor(hash) ?: return null
        if (!file.isFile) return null
        return try {
            fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    /** Ne garde que les vraies analyses OSM (ni échec, ni saisie manuelle). */
    fun save(hash: String, breakdown: GpxSurfaceBreakdown) {
        if (breakdown.overpassError != null || breakdown.isManualOrOfflineEstimate) return
        val file = fileFor(hash) ?: return
        try {
            dir.mkdirs()
            file.writeText(toJson(breakdown).toString(), Charsets.UTF_8)
            prune()
        } catch (_: Exception) {
            // Cache best-effort : un échec d'écriture ne doit pas casser l'analyse.
        }
    }

    fun remove(hash: String) {
        fileFor(hash)?.delete()
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }.take(files.size - maxEntries).forEach { it.delete() }
    }

    companion object {
        fun toJson(b: GpxSurfaceBreakdown): JSONObject = JSONObject().apply {
            put("bitumeMeters", b.bitumeMeters)
            put("cheminStabiliseMeters", b.cheminStabiliseMeters)
            put("terreForetMeters", b.terreForetMeters)
            put("inconnuMeters", b.inconnuMeters)
            put("totalMeters", b.totalMeters)
            put("bitumePercent", b.bitumePercent)
            put("cheminStabilisePercent", b.cheminStabilisePercent)
            put("terreForetPercent", b.terreForetPercent)
            put("inconnuPercent", b.inconnuPercent)
            put("isUnreliable", b.isUnreliable)
            put("segments", JSONArray().apply {
                b.coloredSegments.forEach { s ->
                    put(JSONArray().apply {
                        put(s.startLat); put(s.startLon); put(s.endLat); put(s.endLon)
                        put(s.lengthMeters); put(s.category.name)
                    })
                }
            })
        }

        fun fromJson(o: JSONObject): GpxSurfaceBreakdown {
            val segs = o.getJSONArray("segments")
            val segments = (0 until segs.length()).map { i ->
                val a = segs.getJSONArray(i)
                GpxColoredSegment(
                    startLat = a.getDouble(0),
                    startLon = a.getDouble(1),
                    endLat = a.getDouble(2),
                    endLon = a.getDouble(3),
                    lengthMeters = a.getDouble(4),
                    category = SurfaceCategory.valueOf(a.getString(5))
                )
            }
            return GpxSurfaceBreakdown(
                bitumeMeters = o.getDouble("bitumeMeters"),
                cheminStabiliseMeters = o.getDouble("cheminStabiliseMeters"),
                terreForetMeters = o.getDouble("terreForetMeters"),
                inconnuMeters = o.getDouble("inconnuMeters"),
                totalMeters = o.getDouble("totalMeters"),
                bitumePercent = o.getDouble("bitumePercent"),
                cheminStabilisePercent = o.getDouble("cheminStabilisePercent"),
                terreForetPercent = o.getDouble("terreForetPercent"),
                inconnuPercent = o.getDouble("inconnuPercent"),
                isUnreliable = o.getBoolean("isUnreliable"),
                coloredSegments = segments,
                overpassError = null,
                isManualOrOfflineEstimate = false
            )
        }
    }
}
