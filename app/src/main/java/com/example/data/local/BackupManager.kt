package com.example.data.local

import com.example.data.model.ChecklistType
import com.example.data.model.GearCategory
import com.example.data.model.GelFeedback
import com.example.data.model.MudLevel
import com.example.data.model.RunSessionType
import com.example.data.model.ShoeType
import com.example.data.model.TerrainType
import com.example.data.model.ThermalFeeling
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sauvegarde complète des données de l'appli au format JSON (chaussures, matos, check-lists,
 * historique et réglages), pour pouvoir les restaurer après une réinstallation ou un changement de téléphone.
 */
object BackupManager {

    const val FORMAT_NAME = "run-matos-backup"
    const val FORMAT_VERSION = 1

    data class BackupContent(
        val shoes: List<ShoeEntity>,
        val gearItems: List<GearItemEntity>,
        val checklistItems: List<ChecklistItemEntity>,
        val runHistory: List<RunHistoryEntity>,
        /** Réglages : valeurs Int ou String uniquement. */
        val settings: Map<String, Any>
    )

    fun toJson(content: BackupContent, exportedAtMillis: Long = System.currentTimeMillis()): String {
        val root = JSONObject()
        root.put("format", FORMAT_NAME)
        root.put("version", FORMAT_VERSION)
        root.put("exportedAtMillis", exportedAtMillis)

        root.put("shoes", JSONArray().apply {
            content.shoes.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("type", s.type.name)
                    put("currentKm", s.currentKm)
                    put("maxKm", s.maxKm)
                })
            }
        })
        root.put("gearItems", JSONArray().apply {
            content.gearItems.forEach { g ->
                put(JSONObject().apply {
                    put("code", g.code)
                    put("name", g.name)
                    put("category", g.category.name)
                    put("owned", g.owned)
                })
            }
        })
        root.put("checklistItems", JSONArray().apply {
            content.checklistItems.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("listType", c.listType.name)
                    put("label", c.label)
                    put("checked", c.checked)
                })
            }
        })
        root.put("runHistory", JSONArray().apply {
            content.runHistory.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("dateEpochMillis", r.dateEpochMillis)
                    put("dateFormatted", r.dateFormatted)
                    put("locationName", r.locationName)
                    put("sessionType", r.sessionType.name)
                    put("terrain", r.terrain.name)
                    put("plannedDistanceKm", r.plannedDistanceKm)
                    put("actualDistanceKm", r.actualDistanceKm)
                    put("elevationGainDPlusMeters", r.elevationGainDPlusMeters)
                    put("gpxSurfaceSummary", r.gpxSurfaceSummary)
                    put("durationMinutes", r.durationMinutes)
                    put("realTempC", r.realTempC)
                    put("referenceTempC", r.referenceTempC)
                    put("windKmh", r.windKmh)
                    put("rainMm", r.rainMm)
                    put("rain72hMm", r.rain72hMm)
                    put("mudLevel", r.mudLevel.name)
                    put("maxUvIndex", r.maxUvIndex)
                    put("outfitWorn", r.outfitWorn)
                    if (r.shoeId != null) put("shoeId", r.shoeId) else put("shoeId", JSONObject.NULL)
                    put("shoeName", r.shoeName)
                    put("shoeType", r.shoeType.name)
                    put("gelsCarried", r.gelsCarried)
                    put("carbRateUsed", r.carbRateUsed)
                    put("thermalFeeling", r.thermalFeeling.name)
                    put("gelFeedback", r.gelFeedback.name)
                    put("notes", r.notes)
                })
            }
        })
        root.put("settings", JSONObject().apply {
            content.settings.forEach { (key, value) ->
                when (value) {
                    is Int -> put(key, value)
                    is String -> put(key, value)
                }
            }
        })
        return root.toString(2)
    }

    /** Lève IllegalArgumentException si le fichier n'est pas une sauvegarde Run Matos valide. */
    fun fromJson(json: String): BackupContent {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("Fichier illisible (JSON invalide).", e)
        }
        require(root.optString("format") == FORMAT_NAME) { "Ce fichier n'est pas une sauvegarde Run Matos." }
        val version = root.optInt("version", -1)
        require(version in 1..FORMAT_VERSION) { "Version de sauvegarde non prise en charge ($version)." }

        try {
            val shoes = root.getJSONArray("shoes").objects().map { o ->
                ShoeEntity(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    type = ShoeType.valueOf(o.getString("type")),
                    currentKm = o.getDouble("currentKm"),
                    maxKm = o.getDouble("maxKm")
                )
            }
            val gear = root.getJSONArray("gearItems").objects().map { o ->
                GearItemEntity(
                    code = o.getString("code"),
                    name = o.getString("name"),
                    category = GearCategory.valueOf(o.getString("category")),
                    owned = o.getBoolean("owned")
                )
            }
            val checklists = root.getJSONArray("checklistItems").objects().map { o ->
                ChecklistItemEntity(
                    id = o.getLong("id"),
                    listType = ChecklistType.valueOf(o.getString("listType")),
                    label = o.getString("label"),
                    checked = o.getBoolean("checked")
                )
            }
            val history = root.getJSONArray("runHistory").objects().map { o ->
                RunHistoryEntity(
                    id = o.getLong("id"),
                    dateEpochMillis = o.getLong("dateEpochMillis"),
                    dateFormatted = o.getString("dateFormatted"),
                    locationName = o.getString("locationName"),
                    sessionType = RunSessionType.valueOf(o.getString("sessionType")),
                    terrain = TerrainType.valueOf(o.getString("terrain")),
                    plannedDistanceKm = o.getDouble("plannedDistanceKm"),
                    actualDistanceKm = o.getDouble("actualDistanceKm"),
                    elevationGainDPlusMeters = o.optInt("elevationGainDPlusMeters", 0),
                    gpxSurfaceSummary = o.optString("gpxSurfaceSummary", ""),
                    durationMinutes = o.getInt("durationMinutes"),
                    realTempC = o.getDouble("realTempC"),
                    referenceTempC = o.getDouble("referenceTempC"),
                    windKmh = o.getDouble("windKmh"),
                    rainMm = o.getDouble("rainMm"),
                    rain72hMm = o.getDouble("rain72hMm"),
                    mudLevel = MudLevel.valueOf(o.getString("mudLevel")),
                    maxUvIndex = o.getDouble("maxUvIndex"),
                    outfitWorn = o.getString("outfitWorn"),
                    shoeId = if (o.isNull("shoeId")) null else o.getLong("shoeId"),
                    shoeName = o.getString("shoeName"),
                    shoeType = ShoeType.valueOf(o.getString("shoeType")),
                    gelsCarried = o.getInt("gelsCarried"),
                    carbRateUsed = o.getInt("carbRateUsed"),
                    thermalFeeling = ThermalFeeling.valueOf(o.getString("thermalFeeling")),
                    gelFeedback = GelFeedback.valueOf(o.getString("gelFeedback")),
                    notes = o.optString("notes", "")
                )
            }
            val settingsJson = root.optJSONObject("settings") ?: JSONObject()
            val settings = mutableMapOf<String, Any>()
            settingsJson.keys().forEach { key ->
                when (val v = settingsJson.get(key)) {
                    is Int -> settings[key] = v
                    is Long -> settings[key] = v.toInt()
                    is String -> settings[key] = v
                }
            }
            return BackupContent(shoes, gear, checklists, history, settings)
        } catch (e: Exception) {
            throw IllegalArgumentException("Sauvegarde incomplète ou corrompue : ${e.message}", e)
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
