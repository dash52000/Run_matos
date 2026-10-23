package com.example.domain

import com.example.data.local.ChecklistItemEntity
import com.example.data.local.GearItemEntity
import com.example.data.local.RunHistoryEntity
import com.example.data.local.ShoeEntity
import com.example.data.model.ChecklistType
import com.example.data.model.MudAlertInfo
import com.example.data.model.MudLevel
import com.example.data.model.NutritionRecommendation
import com.example.data.model.RetroplanningConfig
import com.example.data.model.RetroplanningStep
import com.example.data.model.RunSessionType
import com.example.data.model.ShoeType
import com.example.data.model.TerrainType
import com.example.data.model.UvAlertInfo
import com.example.data.model.WeatherSlotAnalysis
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

import com.example.data.model.GpxTrackAnalysis
import com.example.data.model.NutritionPhaseType
import com.example.data.model.NutritionTimelineStep
import com.example.data.model.ThermalFeeling
import com.example.data.model.ThermalProfile

data class FullRunRecommendation(
    val weather: WeatherSlotAnalysis,
    val sessionType: RunSessionType,
    val terrain: TerrainType,
    val plannedDistanceKm: Double,
    val elevationGainDPlusMeters: Int = 0,
    val effortDistanceKm: Double = ((plannedDistanceKm + elevationGainDPlusMeters / 100.0) * 10.0).roundToInt() / 10.0,
    val effectiveReferenceTempC: Double = weather.referenceTempC,
    val thermalAdjustmentExplanation: String = "",
    val gpxTrack: GpxTrackAnalysis? = null,
    val mudAlert: MudAlertInfo,
    val uvAlert: UvAlertInfo,
    val selectedShoe: ShoeEntity?,
    val shoeSelectionReason: String,
    val outfitItems: List<String>,
    val accessoriesItems: List<String>,
    val nutrition: NutritionRecommendation,
    val nutritionTimeline: List<NutritionTimelineStep> = emptyList(),
    val similarRuns: List<RunHistoryEntity>,
    val postRaceBagItems: List<ChecklistItemEntity>,
    val hasAutoWarmJacketInBag: Boolean,
    val retroplanningSteps: List<RetroplanningStep>,
    val widgetAlertSummary: String
)

object RecommendationEngine {

    /**
     * Détermine le niveau de boue selon le cumul de pluie des 72 h précédentes :
     * < 5 mm : sec
     * 5 à 15 mm : humide
     * > 15 mm : boueux -> recommander chaussures à crampons et guêtres si possédées
     */
    fun computeMudAlert(
        terrain: TerrainType,
        rain72hMm: Double,
        ownedGearCodes: Set<String>
    ): MudAlertInfo {
        val level = when {
            rain72hMm < 5.0 -> MudLevel.SEC
            rain72hMm <= 15.0 -> MudLevel.HUMIDE
            else -> MudLevel.BOUEUX
        }
        val isTrail = terrain == TerrainType.TRAIL
        val hasGaiters = ownedGearCodes.contains("guetres")
        val recommendCrampons = isTrail && level == MudLevel.BOUEUX
        val recommendGaiters = isTrail && level == MudLevel.BOUEUX && hasGaiters

        val msg = if (!isTrail) {
            "Sortie Route (${rain72hMm} mm sur 72 h) — Pas d'alerte boue."
        } else {
            when (level) {
                MudLevel.SEC -> "Terrain SEC (${rain72hMm} mm / 72 h < 5 mm) : sentiers rapides, trail polyvalent idéal."
                MudLevel.HUMIDE -> "Terrain HUMIDE (${rain72hMm} mm / 72 h entre 5 et 15 mm) : accroche polyvalente conseillée."
                MudLevel.BOUEUX -> buildString {
                    append("ALERTE BOUE (${rain72hMm} mm / 72 h > 15 mm) : chaussures à crampons recommandées")
                    if (hasGaiters) append(" + guêtres (disponibles dans votre matos).")
                    else append(" (guêtres non possédées dans Mon matos).")
                }
            }
        }

        return MudAlertInfo(
            isApplicable = isTrail,
            rain72hMm = rain72hMm,
            mudLevel = level,
            recommendCrampons = recommendCrampons,
            recommendGaiters = recommendGaiters,
            hasGaitersInGear = hasGaiters,
            message = msg
        )
    }

    /**
     * Choix automatique de la paire de chaussures :
     * - Sans trace GPX : type adapté au terrain et à l'état de boue, puis la paire la moins usée.
     * - Avec trace GPX :
     *   - Répartition saisie à la main : utilisée telle quelle.
     *   - Si OSM n'a rien donné ou si la part « inconnu » > 30 % : règles actuelles.
     *   - Si terrain sec (alerte boue = sec) ET bitume >= seuil (60 % par défaut) : chaussures de route.
     *   - Dans tous les autres cas : règles actuelles (trail polyvalent ou crampons selon l'état de boue).
     */
    fun selectBestShoe(
        shoes: List<ShoeEntity>,
        terrain: TerrainType,
        mudLevel: MudLevel,
        gpxTrack: GpxTrackAnalysis? = null,
        asphaltThresholdPercent: Int = 60
    ): Pair<ShoeEntity?, String> {
        if (shoes.isEmpty()) {
            return null to "Aucune paire enregistrée dans Mon matos."
        }

        val breakdown = gpxTrack?.surfaceBreakdown
        var gpxPrefixNote = ""
        var forceRouteShoesFromGpx = false

        if (breakdown != null) {
            when {
                breakdown.isManualOrOfflineEstimate -> {
                    if (mudLevel == MudLevel.SEC && breakdown.bitumePercent >= asphaltThresholdPercent.toDouble()) {
                        forceRouteShoesFromGpx = true
                        gpxPrefixNote = "Trace GPX (revêtement saisi : ${breakdown.bitumePercent}% bitume ≥ $asphaltThresholdPercent%) → Route privilégiée • "
                    } else {
                        gpxPrefixNote = "Trace GPX (revêtement saisi : ${breakdown.bitumePercent}% bitume, terrain ${mudLevel.label}) • "
                    }
                }
                breakdown.overpassError != null -> {
                    gpxPrefixNote = "Trace GPX (${breakdown.overpassError}) • "
                }
                breakdown.isUnreliable -> {
                    gpxPrefixNote = "Analyse GPX peu fiable (${breakdown.inconnuPercent}% inconnu > 30 %) → Règles actuelles • "
                }
                mudLevel == MudLevel.SEC && breakdown.bitumePercent >= asphaltThresholdPercent.toDouble() -> {
                    forceRouteShoesFromGpx = true
                    gpxPrefixNote = "Trace GPX sur terrain sec avec ${breakdown.bitumePercent}% bitume (≥ $asphaltThresholdPercent%) → Route privilégiée • "
                }
                else -> {
                    gpxPrefixNote = "Trace GPX (${breakdown.bitumePercent}% bitume, terrain ${mudLevel.label}) • "
                }
            }
        }

        val preferredTypes: List<ShoeType> = if (forceRouteShoesFromGpx) {
            listOf(ShoeType.ROUTE, ShoeType.TRAIL_POLYVALENT, ShoeType.TRAIL_CRAMPONS)
        } else {
            when (terrain) {
                TerrainType.ROUTE -> listOf(ShoeType.ROUTE, ShoeType.TRAIL_POLYVALENT, ShoeType.TRAIL_CRAMPONS)
                TerrainType.TRAIL -> when (mudLevel) {
                    MudLevel.BOUEUX -> listOf(ShoeType.TRAIL_CRAMPONS, ShoeType.TRAIL_POLYVALENT, ShoeType.ROUTE)
                    MudLevel.HUMIDE -> listOf(ShoeType.TRAIL_POLYVALENT, ShoeType.TRAIL_CRAMPONS, ShoeType.ROUTE)
                    MudLevel.SEC -> listOf(ShoeType.TRAIL_POLYVALENT, ShoeType.TRAIL_CRAMPONS, ShoeType.ROUTE)
                }
            }
        }

        for (targetType in preferredTypes) {
            val matching = shoes.filter { it.type == targetType }.sortedBy { it.currentKm }
            if (matching.isNotEmpty()) {
                val chosen = matching.first()
                val reason = buildString {
                    append(gpxPrefixNote)
                    append("Type ${chosen.type.label} adapté")
                    if (!forceRouteShoesFromGpx) {
                        append(" (${terrain.label}")
                        if (terrain == TerrainType.TRAIL) append(" • ${mudLevel.label}")
                        append(")")
                    }
                    append(" • Paire la moins usée (${chosen.currentKm.roundToInt()}/${chosen.maxKm.roundToInt()} km)")
                }
                return chosen to reason
            }
        }

        val fallback = shoes.minByOrNull { it.currentKm }
        return fallback to "${gpxPrefixNote}Sélection par défaut (paire la moins usée)"
    }

    /**
     * Indice UV max sur le créneau (Open-Meteo), selon les seuils de l'OMS :
     * >= 3 -> protection nécessaire : lunettes, casquette et crème solaire
     * >= 6 -> protection renforcée (crème à large spectre, renouveler sur les sorties longues)
     */
    fun computeUvAlert(maxUv: Double): UvAlertInfo {
        val glassesAndCap = maxUv >= 3.0
        val sunscreen = maxUv >= 3.0
        val msg = when {
            maxUv >= 6.0 -> "UV élevé ($maxUv ≥ 6) : lunettes, casquette et crème solaire, à renouveler sur les sorties longues."
            maxUv >= 3.0 -> "UV modéré ($maxUv ≥ 3) : lunettes, casquette et crème solaire (seuil de protection de l'OMS)."
            else -> "UV faible ($maxUv < 3) : pas de protection solaire spécifique requise."
        }
        return UvAlertInfo(
            maxUv = maxUv,
            needsGlassesAndCap = glassesAndCap,
            needsSunscreen = sunscreen,
            message = msg
        )
    }

    /**
     * Glucides pendant l'effort (repères ACSM / Jeukendrup : rien sous ~1 h, 30-60 g/h de 1 h à 2 h 30,
     * jusqu'à 90 g/h au-delà si l'intestin y est entraîné) :
     * - < 1 h : eau seule, 0 gel
     * - Les 30 premières minutes ne sont pas comptées (premier gel vers 30-45 min, l'usage courant)
     * - Taux « moyen » (45 g/h par défaut) jusqu'à 2 h 30 d'effort, taux « long » (75 g/h par défaut)
     *   seulement pour le temps au-delà de 2 h 30 : pas de saut brutal du nombre de gels.
     * Chaque retour ajuste de ±5 g/h, borné entre 30 et 90 g/h.
     */
    fun computeNutrition(
        durationMinutes: Int,
        carbRateMedium: Int, // 1h à 2h30 (défaut 45 g/h, borné 30..90)
        carbRateLong: Int,   // > 2h30 (défaut 75 g/h, borné 30..90)
        gelCarbsGrams: Int = 25,
        realTempC: Double,
        humidityPercent: Int = 65,
        elevationGainDPlusMeters: Int = 0,
        waterRefillCount: Int = 0
    ): NutritionRecommendation {
        val hours = durationMinutes / 60.0
        val waterPerHour = if (realTempC >= 20.0) 0.65 else if (realTempC >= 12.0) 0.5 else 0.4
        val waterLiters = ((hours * waterPerHour) * 10.0).roundToInt() / 10.0
        val safeRefills = waterRefillCount.coerceIn(0, 5)
        val carriedWaterLiters = if (safeRefills > 0 && waterLiters > 0.0) {
            (((waterLiters / (safeRefills + 1)) * 10.0).roundToInt() / 10.0).coerceAtLeast(0.4)
        } else {
            waterLiters
        }

        val sweatyConditions = realTempC >= 18.0 || (realTempC >= 14.0 && humidityPercent >= 75)
        val needsElectrolytes = sweatyConditions || durationMinutes >= 90
        val electrolytesAdvice = if (needsElectrolytes) {
            when {
                realTempC >= 22.0 -> "Électrolytes indispensables (chaleur ${realTempC.roundToInt()}°C) : boisson à 500–700 mg de sodium par litre."
                durationMinutes >= 120 -> "Électrolytes conseillés (sortie ≥ 2 h) : boisson à 500–700 mg de sodium par litre dans la flasque principale."
                sweatyConditions -> "Électrolytes conseillés (sudation accrue à ${realTempC.roundToInt()}°C • humidité $humidityPercent %)."
                else -> "Électrolytes utiles au-delà de 1 h 30 : boisson avec du sodium ou aliment salé en cours de route."
            }
        } else ""

        val solidOrSaltyAdvice = if (durationMinutes >= 120 || elevationGainDPlusMeters >= 600) {
            "Au-delà de 2 h (ou D+ ≥ 600 m) : alterner gels et petits aliments solides ou salés est souvent mieux toléré. À tester à l'entraînement avant une course."
        } else ""

        if (durationMinutes < 60) {
            return NutritionRecommendation(
                durationMinutes = durationMinutes,
                targetCarbPerHour = 0,
                totalCarbsGrams = 0,
                gelWeightGrams = gelCarbsGrams,
                recommendedGelsCount = 0,
                waterLiters = waterLiters,
                ruleExplanation = "Sortie < 1 h ($durationMinutes min) : hydratation seule ($waterLiters L), 0 gel requis.",
                gelIntakeSchedule = "Aucune prise de gel nécessaire (< 1 h).",
                electrolytesAdvice = electrolytesAdvice,
                solidOrSaltyAdvice = solidOrSaltyAdvice,
                waterRefillCount = safeRefills,
                carriedWaterPerSegmentLiters = carriedWaterLiters
            )
        }

        val mediumRate = carbRateMedium.coerceIn(30, 90)
        val longRate = carbRateLong.coerceIn(30, 90)
        val activeRate = if (durationMinutes <= LONG_EFFORT_THRESHOLD_MINUTES) mediumRate else longRate

        val safeGelGrams = gelCarbsGrams.coerceAtLeast(15)
        val totalCarbs = computeTotalCarbsGrams(durationMinutes, mediumRate, longRate).roundToInt()
        val gelsCount = (totalCarbs.toDouble() / safeGelGrams.toDouble()).roundToInt().coerceAtLeast(1)
        val bracketLabel = if (durationMinutes <= LONG_EFFORT_THRESHOLD_MINUTES) {
            "$mediumRate g/h, durée ${durationMinutes} min"
        } else {
            "$mediumRate g/h jusqu'à 2 h 30 puis $longRate g/h, durée ${durationMinutes} min"
        }

        // Calcul du minutage précis des prises de gels (ex. +35 min, +1h05, +1h35)
        val scheduleText = buildGelIntakeSchedule(durationMinutes, gelsCount)

        return NutritionRecommendation(
            durationMinutes = durationMinutes,
            targetCarbPerHour = activeRate,
            totalCarbsGrams = gelsCount * safeGelGrams,
            gelWeightGrams = safeGelGrams,
            recommendedGelsCount = gelsCount,
            waterLiters = waterLiters,
            ruleExplanation = "Cible $bracketLabel, 30 premières min non comptées → $gelsCount gel(s) de ${safeGelGrams}g (${gelsCount * safeGelGrams} g glucides) + $waterLiters L d'eau (à ajuster à ta soif).",
            gelIntakeSchedule = scheduleText,
            electrolytesAdvice = electrolytesAdvice,
            solidOrSaltyAdvice = solidOrSaltyAdvice,
            waterRefillCount = safeRefills,
            carriedWaterPerSegmentLiters = carriedWaterLiters
        )
    }

    /** Au-delà de cette durée d'effort, le taux « long » s'applique (au temps excédentaire seulement). */
    const val LONG_EFFORT_THRESHOLD_MINUTES = 150

    /** Minutes de début d'effort non comptées dans l'apport en glucides. */
    const val UNFUELED_START_MINUTES = 30

    /**
     * Glucides totaux (g) : taux moyen sur l'effort jusqu'à 2 h 30, taux long au-delà,
     * hors 30 premières minutes. Fonction continue de la durée.
     */
    fun computeTotalCarbsGrams(durationMinutes: Int, mediumRate: Int, longRate: Int): Double {
        if (durationMinutes < 60) return 0.0
        val mediumMinutes = (minOf(durationMinutes, LONG_EFFORT_THRESHOLD_MINUTES) - UNFUELED_START_MINUTES).coerceAtLeast(0)
        val longMinutes = (durationMinutes - LONG_EFFORT_THRESHOLD_MINUTES).coerceAtLeast(0)
        return mediumMinutes / 60.0 * mediumRate + longMinutes / 60.0 * longRate
    }

    private fun buildGelIntakeSchedule(durationMinutes: Int, gelsCount: Int): String {
        if (gelsCount <= 0) return ""
        if (gelsCount == 1) {
            val intakeMin = (durationMinutes / 2).coerceIn(30, 45)
            return "Prise unique : Gel #1 à +${formatElapsedMinutes(intakeMin)} avec 2–3 gorgées d'eau"
        }
        val usableWindow = (durationMinutes - 15).coerceAtLeast(45)
        val step = ((usableWindow - 30).toDouble() / (gelsCount - 1).coerceAtLeast(1)).roundToInt().coerceIn(20, 45)
        val parts = (0 until gelsCount).map { idx ->
            val minAt = (30 + idx * step).coerceAtMost(durationMinutes - 10)
            "Gel #${idx + 1} à +${formatElapsedMinutes(minAt)}"
        }
        return parts.joinToString(" • ")
    }

    private fun formatElapsedMinutes(minutes: Int): String {
        return if (minutes < 60) "${minutes} min"
        else "${minutes / 60}h${String.format("%02d", minutes % 60)}"
    }

    /**
     * Calcule l'ajustement thermique issu du profil personnel et de l'historique des sorties similaires.
     */
    fun computeThermalAdjustment(
        weather: WeatherSlotAnalysis,
        history: List<RunHistoryEntity>,
        thermalProfile: ThermalProfile = ThermalProfile.NEUTRE
    ): Pair<Double, String> {
        val similar = findSimilarRuns(weather, history)
        val tooWarmCount = similar.count { it.thermalFeeling == ThermalFeeling.TROP_CHAUD }
        val tooColdCount = similar.count { it.thermalFeeling == ThermalFeeling.TROP_FROID }

        val historyDelta = when {
            similar.size >= 2 && tooWarmCount > tooColdCount && tooWarmCount >= 2 -> +1.0
            similar.size >= 2 && tooColdCount > tooWarmCount && tooColdCount >= 2 -> -1.0
            else -> 0.0
        }

        val totalDelta = thermalProfile.tempDeltaC + historyDelta
        val effectiveRef = ((weather.referenceTempC + totalDelta) * 10.0).roundToInt() / 10.0

        val explanation = buildString {
            append("T° réf météo ${weather.referenceTempC}°C")
            if (thermalProfile != ThermalProfile.NEUTRE) {
                val sign = if (thermalProfile.tempDeltaC > 0) "+" else ""
                append(" • Profil ${thermalProfile.label.substringBefore(" ")} ($sign${thermalProfile.tempDeltaC}°C)")
            }
            if (historyDelta != 0.0) {
                val sign = if (historyDelta > 0) "+" else ""
                val reason = if (historyDelta > 0) "$tooWarmCount retour(s) « Trop chaud »" else "$tooColdCount retour(s) « Trop froid »"
                append(" • Auto-ajustement historique $sign${historyDelta}°C ($reason)")
            }
            if (totalDelta != 0.0) {
                append(" → Réf effective ${effectiveRef}°C")
            }
        }
        return effectiveRef to explanation
    }

    /**
     * Construction complète de la tenue et des accessoires selon les 5 paliers thermiques,
     * la pluie, le vent/rafales, l'indice UV, la boue, le D+ et le matériel possédé.
     */
    fun buildOutfitAndAccessories(
        weather: WeatherSlotAnalysis,
        terrain: TerrainType,
        mudAlert: MudAlertInfo,
        uvAlert: UvAlertInfo,
        nutrition: NutritionRecommendation,
        gearItems: List<GearItemEntity>,
        effectiveRefTempC: Double = weather.referenceTempC,
        plannedDistanceKm: Double = 10.0,
        elevationGainDPlusMeters: Int = 0,
        sessionType: RunSessionType = RunSessionType.ENDURANCE,
        forestShare: Double = 0.0
    ): Pair<List<String>, List<String>> {
        val ownedCodes = gearItems.filter { it.owned }.map { it.code }.toSet()
        val outfit = mutableListOf<String>()
        val accessories = mutableListOf<String>()
        val ref = effectiveRefTempC

        // Haut selon 5 paliers thermiques précis (< 0°C, 0..6°C, 6..12°C, 12..20°C, > 20°C)
        when {
            ref < 0.0 -> {
                if (ownedCodes.contains("haut_long")) {
                    outfit.add("Sous-couche thermique + Haut manches longues chaud (Grand froid < 0°C)")
                } else {
                    outfit.add("Double couche thermique manches longues (Grand froid < 0°C)")
                }
            }
            ref < 6.0 -> {
                if (ownedCodes.contains("haut_long")) outfit.add("Haut manches longues thermique (0 à 6°C)")
                else outfit.add("T-shirt + sous-couche chaude")
            }
            ref < 12.0 -> {
                if (weather.tempDeltaAcrossSlotC >= 3.0 || ownedCodes.contains("manchettes")) {
                    outfit.add("T-shirt manches courtes + Manchettes amovibles (tenue modulable 6–12°C)")
                } else if (ownedCodes.contains("haut_long")) {
                    outfit.add("T-shirt manches longues respirant (ou manches courtes + manchettes)")
                } else {
                    outfit.add("T-shirt manches courtes + manchettes")
                }
            }
            ref <= 20.0 -> {
                if (ownedCodes.contains("haut_court")) outfit.add("T-shirt technique manches courtes (12 à 20°C)")
                else outfit.add("Haut léger respirant")
            }
            else -> {
                if (ownedCodes.contains("debardeur")) outfit.add("Débardeur ultra-respirant (Chaleur > 20°C)")
                else outfit.add("Débardeur / T-shirt très léger (> 20°C)")
            }
        }

        // Veste pluie / coupe-vent (portée dès le départ ou emportée « au cas où » selon la probabilité de pluie et les rafales)
        var jacketWornFromStart = false
        if (weather.slotRainMm >= 1.0 || (weather.maxRainProbabilityPercent >= 70 && weather.slotRainMm >= 0.3)) {
            jacketWornFromStart = true
            if (ownedCodes.contains("veste_pluie")) {
                outfit.add("Veste imperméable (${weather.slotRainMm} mm • proba pluie ${weather.maxRainProbabilityPercent} %)")
            } else if (ownedCodes.contains("coupe_vent")) {
                outfit.add("Coupe-vent déperlant (${weather.slotRainMm} mm • proba pluie ${weather.maxRainProbabilityPercent} %)")
            } else {
                outfit.add("Veste de pluie recommandée (${weather.slotRainMm} mm • proba ${weather.maxRainProbabilityPercent} %)")
            }
        } else if (windFeltFactor(forestShare).let { f -> weather.maxWindKmh * f >= 22.0 || weather.maxWindGustsKmh * f >= 38.0 } && ref < 14.0) {
            jacketWornFromStart = true
            if (ownedCodes.contains("coupe_vent")) {
                outfit.add("Coupe-vent respirant (vent ${weather.maxWindKmh.roundToInt()} km/h, rafales ${weather.maxWindGustsKmh.roundToInt()} km/h ${weather.windDirectionCardinal})")
            } else {
                outfit.add("Coupe-vent conseillé (rafales ${weather.maxWindGustsKmh.roundToInt()} km/h ${weather.windDirectionCardinal})")
            }
        }

        val gustsJustInCase = weather.maxWindGustsKmh * windFeltFactor(forestShare) >= 35.0
        val needsJacketJustInCase = weather.slotRainMm < 1.0 && (weather.maxRainProbabilityPercent >= 40 || gustsJustInCase)
        if (!jacketWornFromStart && needsJacketJustInCase) {
            val reason = if (weather.maxRainProbabilityPercent >= 40) {
                "proba pluie ${weather.maxRainProbabilityPercent} %"
            } else {
                "rafales ${weather.maxWindGustsKmh.roundToInt()} km/h"
            }
            when {
                ownedCodes.contains("veste_pluie") ->
                    accessories.add("Veste imperméable à emporter au cas où ($reason)")
                ownedCodes.contains("coupe_vent") ->
                    accessories.add("Coupe-vent déperlant à emporter au cas où ($reason)")
                else ->
                    accessories.add("Veste de pluie / coupe-vent à prendre au cas où ($reason)")
            }
        }

        // Alerte fraîcheur au point culminant GPX
        if (weather.summitTempC != null && weather.summitElevationMeters != null && (weather.realTempC - weather.summitTempC) >= 2.5) {
            accessories.add("Protection sommet GPX (${weather.summitElevationMeters} m : ~${weather.summitTempC}°C) : coupe-vent ou manchettes pour les crêtes")
        }

        // Bas selon paliers (toujours une proposition, même sans matos coché)
        when {
            ref < 4.0 -> {
                if (ownedCodes.contains("cuissard_long")) outfit.add("Collant / Cuissard long thermique")
                else outfit.add("Collant long conseillé (< 4°C)")
            }
            ref < 10.0 -> {
                if (ownedCodes.contains("cuissard_court")) outfit.add("Cuissard court (ou 3/4)")
                else if (ownedCodes.contains("short")) outfit.add("Short de course")
                else if (ownedCodes.contains("cuissard_long")) outfit.add("Collant long (ou cuissard court si tu as chaud)")
                else outfit.add("Cuissard court ou short (4 à 10°C)")
            }
            else -> {
                if (ownedCodes.contains("short")) outfit.add("Short léger respirant")
                else outfit.add("Cuissard court")
            }
        }

        // Chaussettes
        if (ownedCodes.contains("chaussettes")) {
            outfit.add(if (terrain == TerrainType.TRAIL) "Chaussettes Trail renforcées" else "Chaussettes Route respirantes")
        }

        // Accessoires thermiques (Gants, Bonnet, Tour de cou / Buff)
        if (ref < 6.0) {
            if (ownedCodes.contains("gants")) accessories.add("Gants fins de course (< 6°C)")
            else accessories.add("Gants fins conseillés (< 6°C)")
            accessories.add("Tour de cou / Buff multifonction (protection gorge & oreilles)")
        }
        if (ref < 3.0) {
            if (ownedCodes.contains("bonnet")) accessories.add("Bonnet / Bandeau thermique (< 3°C)")
            else accessories.add("Bonnet ou bandeau chaud conseillé (< 3°C)")
        }

        // Guêtres si Trail boueux et possédées
        if (mudAlert.recommendGaiters) {
            accessories.add("Guêtres de trail (boue > 15 mm/72h)")
        }

        // Bâtons de trail : seulement sur les parcours vraiment pentus (≥ 40 m D+/km) ou très longs en D+ (≥ 1000 m)
        val dPlusPerKm = if (plannedDistanceKm > 0.5) elevationGainDPlusMeters / plannedDistanceKm else 0.0
        if (terrain == TerrainType.TRAIL && (elevationGainDPlusMeters >= 1000 || dPlusPerKm >= 40.0)) {
            accessories.add("Bâtons de trail recommandés (${elevationGainDPlusMeters} m D+ • ${dPlusPerKm.roundToInt()} m/km)")
        }

        // Crème anti-frottements (sortie longue >= 90 min, pluie, forte humidité ou chaleur)
        if (weather.durationMinutes >= 90 || weather.slotRainMm >= 0.5 || weather.avgHumidityPercent >= 80 || weather.realTempC >= 20.0) {
            accessories.add("Crème anti-frottements (pieds / cuisses / aisselles — durée ${weather.durationMinutes} min, humidité ${weather.avgHumidityPercent} %)")
        }

        // Sécurité Trail (Couverture de survie & sifflet)
        if (terrain == TerrainType.TRAIL && (weather.durationMinutes >= 90 || sessionType == RunSessionType.COURSE || elevationGainDPlusMeters >= 400)) {
            accessories.add("Couverture de survie + sifflet dans le gilet (sécurité Trail)")
        }

        // Soleil (UV)
        if (uvAlert.needsGlassesAndCap) {
            if (ownedCodes.contains("lunettes_soleil")) accessories.add("Lunettes de soleil (UV ${uvAlert.maxUv})")
            else accessories.add("Lunettes de soleil (recommandé UV ≥ 3)")

            if (ownedCodes.contains("casquette")) {
                val capNote = if (weather.realTempC >= 22.0) " — à humidifier régulièrement" else ""
                accessories.add("Casquette respirante (UV ${uvAlert.maxUv}$capNote)")
            } else {
                accessories.add("Casquette (recommandé UV ≥ 3)")
            }
        }
        if (uvAlert.needsSunscreen) {
            if (ownedCodes.contains("creme_solaire")) accessories.add("Crème solaire SPF 50 (UV ${uvAlert.maxUv} ≥ 6)")
            else accessories.add("Crème solaire (recommandé UV ≥ 6)")
        }

        // Nuit / Frontale
        if (weather.isNightOrDawn) {
            if (ownedCodes.contains("frontale")) accessories.add("Lampe frontale chargée (départ/retour nocturne)")
            else accessories.add("Lampe frontale indispensable (créneau nocturne)")
        }

        // Hydratation & Gels
        if (weather.durationMinutes >= 60 || terrain == TerrainType.TRAIL) {
            val waterText = if (nutrition.waterRefillCount > 0) {
                "${nutrition.carriedWaterPerSegmentLiters} L au départ (${nutrition.waterLiters} L au total avec ${nutrition.waterRefillCount} ravito(s))"
            } else {
                "${nutrition.waterLiters} L en autonomie"
            }
            if (ownedCodes.contains("gilet_hydratation")) {
                accessories.add("Gilet / Ceinture d'hydratation ($waterText)")
            } else {
                accessories.add("Flasques / Portage d'eau ($waterText)")
            }
        }
        if (nutrition.recommendedGelsCount > 0) {
            accessories.add("${nutrition.recommendedGelsCount} gel(s) énergétique(s) (${nutrition.targetCarbPerHour} g/h — ${nutrition.gelIntakeSchedule})")
        }

        return outfit to accessories
    }

    /** Le vent est compté moitié moins sur la part de la trace en forêt. */
    fun windFeltFactor(forestShare: Double): Double {
        val safe = if (forestShare.isFinite()) forestShare.coerceIn(0.0, 1.0) else 0.0
        return 1.0 - 0.5 * safe
    }

    /**
     * Sorties similaires :
     * - Température de référence à ±2 °C
     * - Vent et pluie comparables
     */
    fun findSimilarRuns(
        currentWeather: WeatherSlotAnalysis,
        history: List<RunHistoryEntity>
    ): List<RunHistoryEntity> {
        val isRainyNow = currentWeather.slotRainMm >= 1.0
        val isWindyNow = currentWeather.maxWindKmh >= 20.0

        return history.filter { past ->
            val tempDiff = abs(past.referenceTempC - currentWeather.referenceTempC)
            val pastRainy = past.rainMm >= 1.0
            val pastWindy = past.windKmh >= 20.0
            val rainComparable = (isRainyNow == pastRainy) || abs(past.rainMm - currentWeather.slotRainMm) <= 2.0
            val windComparable = (isWindyNow == pastWindy) || abs(past.windKmh - currentWeather.maxWindKmh) <= 12.0
            tempDiff <= 2.0 && rainComparable && windComparable
        }.sortedBy { abs(it.referenceTempC - currentWeather.referenceTempC) }
            .take(5)
    }

    /**
     * Rétroplanning (type « Course ») :
     * - À partir de l'heure de départ :
     *   - Temps de trajet (saisi)
     *   - Marge sur place (45 min par défaut)
     *   - Petit-déjeuner 3 h (180 min) avant le départ
     *   - Réveil 30 min avant le petit-déjeuner
     * Toutes les valeurs sont modifiables.
     */
    fun buildRetroplanning(config: RetroplanningConfig): List<RetroplanningStep> {
        val startTotalMin = config.raceStartHour * 60 + config.raceStartMinute
        val arrivalOnSiteMin = startTotalMin - config.onSiteMarginMinutes
        val leaveHomeMin = arrivalOnSiteMin - config.travelMinutes
        val breakfastMin = startTotalMin - config.breakfastBeforeStartMinutes
        val wakeUpMin = breakfastMin - config.wakeUpBeforeBreakfastMinutes

        val rawSteps = listOf(
            RetroplanningStep(
                id = "wakeup",
                title = "Réveil & Hydratation",
                subtitle = "${config.wakeUpBeforeBreakfastMinutes} min avant le petit-déjeuner",
                timeFormatted = formatMinutesOfDay(wakeUpMin),
                minutesFromMidnight = normalizeMinutes(wakeUpMin)
            ),
            RetroplanningStep(
                id = "breakfast",
                title = "Petit-déjeuner d'avant-course",
                subtitle = "${config.breakfastBeforeStartMinutes} min (${formatDuration(config.breakfastBeforeStartMinutes)}) avant le départ",
                timeFormatted = formatMinutesOfDay(breakfastMin),
                minutesFromMidnight = normalizeMinutes(breakfastMin)
            ),
            RetroplanningStep(
                id = "departure_travel",
                title = "Départ trajet vers la course",
                subtitle = "Trajet estimé : ${config.travelMinutes} min",
                timeFormatted = formatMinutesOfDay(leaveHomeMin),
                minutesFromMidnight = normalizeMinutes(leaveHomeMin)
            ),
            RetroplanningStep(
                id = "arrival_onsite",
                title = "Arrivée sur place, dossard & échauffement",
                subtitle = "Marge sur place : ${config.onSiteMarginMinutes} min avant départ",
                timeFormatted = formatMinutesOfDay(arrivalOnSiteMin),
                minutesFromMidnight = normalizeMinutes(arrivalOnSiteMin)
            ),
            RetroplanningStep(
                id = "race_start",
                title = "Départ de la course",
                subtitle = "Top départ officiel",
                timeFormatted = formatMinutesOfDay(startTotalMin),
                minutesFromMidnight = normalizeMinutes(startTotalMin)
            )
        )
        return rawSteps.sortedBy { it.minutesFromMidnight }
    }

    private fun normalizeMinutes(totalMin: Int): Int {
        var m = totalMin % (24 * 60)
        if (m < 0) m += 24 * 60
        return m
    }

    fun formatMinutesOfDay(totalMin: Int): String {
        val norm = normalizeMinutes(totalMin)
        val h = norm / 60
        val m = norm % 60
        return String.format("%02dh%02d", h, m)
    }

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "${h}h" else "${h}h${String.format("%02d", m)}"
    }

    /**
     * Frise nutrition Avant / Pendant / Après, en règles fixes (pas d'IA), fondée sur les recommandations
     * établies :
     * - ACSM / Academy of Nutrition and Dietetics / Dietitians of Canada, « Nutrition and Athletic Performance » (2016)
     * - ACSM, « Exercise and Fluid Replacement » (2007) pour l'hydratation avant et après
     * - Jeukendrup (2014) pour les glucides pendant l'effort
     * - Aragon & Schoenfeld (2013) : l'apport total de la journée compte plus qu'une « fenêtre » de 30 min
     * Couvre : la veille (sorties longues / courses), l'avant-départ, le pendant (rappel), la récupération
     * et le repas d'après-sortie.
     */
    fun buildNutritionTimeline(
        startHour: Int,
        startMinute: Int,
        durationMinutes: Int,
        sessionType: RunSessionType,
        realTempC: Double,
        duringNutrition: NutritionRecommendation
    ): List<NutritionTimelineStep> {
        val steps = mutableListOf<NutritionTimelineStep>()
        val startTotalMin = startHour.coerceIn(0, 23) * 60 + startMinute.coerceIn(0, 59)
        val endTotalMin = startTotalMin + durationMinutes
        val isLongOrRace = sessionType == RunSessionType.SORTIE_LONGUE ||
            sessionType == RunSessionType.COURSE ||
            durationMinutes >= 90
        val isHighIntensity = sessionType == RunSessionType.FRACTIONNE ||
            sessionType == RunSessionType.COURSE
        val isEarlyMorning = startTotalMin < (8 * 60 + 30) // Départ avant 08h30
        val isHot = realTempC >= 20.0
        val isCold = realTempC < 8.0

        // 1. VEILLE AU SOIR (Sorties longues, courses ou durées >= 90 min)
        if (isLongOrRace) {
            val eveFood = if (sessionType == RunSessionType.COURSE && durationMinutes >= 90) {
                "Dîner riche en glucides (pâtes, riz, pommes de terre, pain) et pauvre en fibres et en graisses pour limiter les troubles digestifs. Pour un effort de plus de 1 h 30, la recharge se joue surtout sur les 36–48 h avant (repas riches en glucides), pas sur ce seul dîner. Rien de nouveau la veille."
            } else {
                "Dîner habituel avec une bonne part de féculents (pâtes, riz, pommes de terre, pain), sans excès de graisses ni d'aliments très riches en fibres si tu as l'estomac sensible."
            }
            val eveHydration = "Boire normalement selon la soif : l'objectif est d'arriver au départ bien hydraté, pas de surcharger en eau."
            steps.add(
                NutritionTimelineStep(
                    id = "eve_dinner",
                    phase = NutritionPhaseType.VEILLE_SOIR,
                    timingLabel = "Veille • 19h30 – 20h30",
                    title = "Dîner de la veille",
                    foodTypesAdvice = eveFood,
                    hydrationAdvice = eveHydration,
                    contextTag = "${sessionType.label} • ${durationMinutes} min"
                )
            )
        }

        val preHydrationHot = "Environ 5 à 7 mL d'eau par kg de poids (≈ 400–500 mL pour 70 kg) au moins 2 à 4 h avant ; un peu de sel ou une boisson avec du sodium aide à retenir l'eau par temps chaud."
        val preHydrationNormal = "Environ 5 à 7 mL d'eau par kg de poids (≈ 400–500 mL pour 70 kg) dans les 2 à 4 h avant, puis selon la soif. Urines claires = bien hydraté."

        // 2. AVANT-DÉPART (adapté à l'heure matinale, l'intensité et la durée)
        if (isEarlyMorning) {
            val snackMin = startTotalMin - 60
            val snackEndMin = startTotalMin - 30
            val timingStr = "${formatMinutesOfDay(snackMin)} – ${formatMinutesOfDay(snackEndMin)} (H-1h à H-30 min)"
            val earlyFood = when {
                sessionType == RunSessionType.COURSE || durationMinutes >= 90 ->
                    "Départ matinal : petite collation riche en glucides et facile à digérer (banane, pain blanc + miel ou confiture, compote), pauvre en graisses et en fibres. Si possible, lève-toi plus tôt pour un vrai petit-déjeuner 2 à 3 h avant."
                isHighIntensity ->
                    "Avant une séance intense : quelques glucides faciles à digérer (banane, compote, pain blanc + miel) pour ne pas partir le ventre vide, en petite quantité."
                else ->
                    "Footing de moins de 1 h 30 : la collation est facultative (banane ou compote si tu as faim). Courir en endurance à jeun le matin est en général bien toléré."
            }
            val earlyHydration = when {
                isHot -> preHydrationHot
                isCold -> "Un verre d'eau ou une boisson chaude au réveil, puis selon la soif. Le chaud n'hydrate ni plus ni moins, c'est une question de confort."
                else -> "Un verre d'eau au réveil, puis selon la soif jusqu'au départ."
            }
            steps.add(
                NutritionTimelineStep(
                    id = "pre_run_early",
                    phase = NutritionPhaseType.AVANT_DEPART,
                    timingLabel = timingStr,
                    title = "Collation d'avant-départ (matin tôt)",
                    foodTypesAdvice = earlyFood,
                    hydrationAdvice = earlyHydration,
                    contextTag = "Départ matinal (${formatMinutesOfDay(startTotalMin)}) • ${realTempC.roundToInt()}°C"
                )
            )
        } else {
            val mealMin = startTotalMin - 180
            val snackMin = startTotalMin - 60
            val timingStr = if (isLongOrRace || isHighIntensity) {
                "Repas ${formatMinutesOfDay(mealMin)} (H-3h) • Collation ${formatMinutesOfDay(snackMin)} (H-1h)"
            } else {
                "Repas ${formatMinutesOfDay(mealMin)} à ${formatMinutesOfDay(startTotalMin - 120)} (H-3h à H-2h)"
            }
            val normalFood = when {
                sessionType == RunSessionType.COURSE ->
                    "À H-3h : repas riche en glucides (pain, flocons d'avoine, riz, pâtes + miel, confiture ou fruit), pauvre en graisses et en fibres, avec un peu de protéines. À H-1h : petite collation glucidique si besoin. Uniquement des aliments déjà testés à l'entraînement."
                isHighIntensity ->
                    "À H-3h : repas riche en glucides et peu gras (pain, riz, pâtes, avoine). À H-1h : un fruit mûr ou une compote si tu as faim."
                isLongOrRace ->
                    "À H-3h : repas riche en glucides (pain, avoine, riz, pâtes), modéré en protéines, pauvre en graisses et en fibres pour le confort digestif."
                else ->
                    "Repas habituel pris 2 à 3 h avant de courir, en évitant les plats très gras ou très riches en fibres juste avant."
            }
            steps.add(
                NutritionTimelineStep(
                    id = "pre_run_normal",
                    phase = NutritionPhaseType.AVANT_DEPART,
                    timingLabel = timingStr,
                    title = "Repas & hydratation d'avant-départ",
                    foodTypesAdvice = normalFood,
                    hydrationAdvice = if (isHot) preHydrationHot else preHydrationNormal,
                    contextTag = "Départ ${formatMinutesOfDay(startTotalMin)} • ${sessionType.label}"
                )
            )
        }

        // 3. PENDANT LA SORTIE (rappel des règles actives : gels et eau)
        steps.add(
            NutritionTimelineStep(
                id = "during_run",
                phase = NutritionPhaseType.PENDANT_SORTIE,
                timingLabel = "${formatMinutesOfDay(startTotalMin)} – ${formatMinutesOfDay(endTotalMin)} (${durationMinutes} min)",
                title = "Pendant la sortie (gels & eau)",
                foodTypesAdvice = if (duringNutrition.recommendedGelsCount > 0) {
                    "${duringNutrition.recommendedGelsCount} gel(s) de ${duringNutrition.gelWeightGrams}g (cible ${duringNutrition.targetCarbPerHour} g/h) répartis régulièrement."
                } else {
                    "Sortie de moins de 1 h : aucun apport nécessaire."
                },
                hydrationAdvice = "Eau : environ ${duringNutrition.waterLiters} L sur le créneau, par petites gorgées, à ajuster à la soif.",
                contextTag = "Pendant l'effort"
            )
        )

        // 4. RÉCUPÉRATION (dans les 2 h après l'arrivée)
        val needsFastRecovery = sessionType == RunSessionType.COURSE || durationMinutes >= 90 || isHighIntensity
        val recupEndMin = endTotalMin + 120
        val recupTiming = "${formatMinutesOfDay(endTotalMin)} – ${formatMinutesOfDay(recupEndMin)} (dans les 2 h)"
        val recupFood = if (needsFastRecovery) {
            "Glucides + 20 à 25 g de protéines dans les 2 h (ex. laitage ou boisson lactée + fruit, sandwich jambon/œuf/fromage, riz + œufs). Utile surtout si tu recours dans les 24 h ; sinon tes repas normaux suffisent, l'apport total de la journée compte plus que l'horaire exact."
        } else {
            "Rien de spécial : ton prochain repas suffit. Une collation (fruit, yaourt) si tu as faim d'ici là."
        }
        val recupHydration = when {
            isHot || durationMinutes >= 90 ->
                "Remplacer les pertes : environ 1,25 à 1,5 L par kg perdu (pèse-toi avant et après si tu veux le chiffrer), avec du sodium (boisson salée, bouillon, repas salé) pour retenir l'eau."
            isCold ->
                "Boire selon la soif ; une boisson chaude est agréable mais n'hydrate ni plus ni moins que l'eau."
            else ->
                "Boire selon la soif dès l'arrivée."
        }
        steps.add(
            NutritionTimelineStep(
                id = "post_run_immediate",
                phase = NutritionPhaseType.RECUP_IMMEDIATE,
                timingLabel = recupTiming,
                title = "Récupération & réhydratation",
                foodTypesAdvice = recupFood,
                hydrationAdvice = recupHydration,
                contextTag = "Après l'arrivée • ${realTempC.roundToInt()}°C"
            )
        )

        // 5. REPAS D'APRÈS-SORTIE
        val mealPostStart = endTotalMin + 90
        val mealPostEnd = endTotalMin + 180
        val postMealTiming = "${formatMinutesOfDay(mealPostStart)} – ${formatMinutesOfDay(mealPostEnd)} (prochain repas)"
        val postMealFood = if (isLongOrRace || isHighIntensity) {
            "Repas complet : bonne part de féculents pour recharger le glycogène, une source de protéines (viande, poisson, œufs, laitages, légumineuses ou tofu) et des légumes."
        } else {
            "Repas équilibré habituel : féculents, protéines et légumes."
        }
        val postMealHydration = if (isHot || isLongOrRace) {
            "Continuer à boire pendant et après le repas ; saler normalement aide à récupérer le sodium perdu."
        } else {
            "Boire normalement selon la soif."
        }
        steps.add(
            NutritionTimelineStep(
                id = "post_run_meal",
                phase = NutritionPhaseType.REPAS_APRES,
                timingLabel = postMealTiming,
                title = "Repas d'après-sortie",
                foodTypesAdvice = postMealFood,
                hydrationAdvice = postMealHydration,
                contextTag = "Récupération"
            )
        )

        return steps
    }

    /** Part de la trace en terre / forêt (0..1), seulement si le revêtement est connu (OSM ou saisi). */
    fun forestShareOf(gpxTrack: GpxTrackAnalysis?): Double {
        val breakdown = gpxTrack?.surfaceBreakdown ?: return 0.0
        if (breakdown.overpassError != null) return 0.0
        return (breakdown.terreForetPercent / 100.0).coerceIn(0.0, 1.0)
    }

    /**
     * Génère la recommandation globale complète pour l'écran Recommandation et le Widget.
     */
    fun generateRecommendation(
        weather: WeatherSlotAnalysis,
        sessionType: RunSessionType,
        terrain: TerrainType,
        plannedDistanceKm: Double,
        elevationGainDPlusMeters: Int = 0,
        gpxTrack: GpxTrackAnalysis? = null,
        asphaltThresholdPercent: Int = 60,
        shoes: List<ShoeEntity>,
        gearItems: List<GearItemEntity>,
        postRaceChecklist: List<ChecklistItemEntity>,
        history: List<RunHistoryEntity>,
        carbRateMedium: Int,
        carbRateLong: Int,
        gelCarbsGrams: Int,
        retroConfig: RetroplanningConfig,
        startHour: Int = 9,
        startMinute: Int = 0,
        thermalProfile: ThermalProfile = ThermalProfile.NEUTRE,
        waterRefillCount: Int = 0
    ): FullRunRecommendation {
        val forestShare = forestShareOf(gpxTrack)
        val ownedCodes = gearItems.filter { it.owned }.map { it.code }.toSet()
        val mudAlert = computeMudAlert(terrain, weather.past72hRainMm, ownedCodes)
        val uvAlert = computeUvAlert(weather.maxUvIndex)
        val (selectedShoe, shoeReason) = selectBestShoe(
            shoes = shoes,
            terrain = terrain,
            mudLevel = mudAlert.mudLevel,
            gpxTrack = gpxTrack,
            asphaltThresholdPercent = asphaltThresholdPercent
        )
        val (effectiveRefTemp, thermalExplanation) = computeThermalAdjustment(
            weather = weather,
            history = history,
            thermalProfile = thermalProfile
        )
        val nutrition = computeNutrition(
            durationMinutes = weather.durationMinutes,
            carbRateMedium = carbRateMedium,
            carbRateLong = carbRateLong,
            gelCarbsGrams = gelCarbsGrams,
            realTempC = weather.realTempC,
            humidityPercent = weather.avgHumidityPercent,
            elevationGainDPlusMeters = elevationGainDPlusMeters,
            waterRefillCount = waterRefillCount
        )
        val nutritionTimeline = buildNutritionTimeline(
            startHour = startHour,
            startMinute = startMinute,
            durationMinutes = weather.durationMinutes,
            sessionType = sessionType,
            realTempC = weather.realTempC,
            duringNutrition = nutrition
        )
        val (outfit, accessories) = buildOutfitAndAccessories(
            weather = weather,
            terrain = terrain,
            mudAlert = mudAlert,
            uvAlert = uvAlert,
            nutrition = nutrition,
            gearItems = gearItems,
            effectiveRefTempC = effectiveRefTemp,
            plannedDistanceKm = plannedDistanceKm,
            elevationGainDPlusMeters = elevationGainDPlusMeters,
            sessionType = sessionType,
            forestShare = forestShare
        )
        val similarRuns = findSimilarRuns(weather, history)

        // Sac d'après-course : ajout automatique d'une veste chaude si température < 10 °C
        val needsWarmJacket = weather.realTempC < 10.0
        val effectivePostRaceBag = buildList {
            if (needsWarmJacket) {
                add(
                    ChecklistItemEntity(
                        id = -999L,
                        listType = ChecklistType.POST_RACE_BAG,
                        label = "Veste chaude d'après-course (ajout auto < 10 °C)",
                        checked = false
                    )
                )
            }
            addAll(postRaceChecklist)
        }

        val alertsList = mutableListOf<String>()
        if (terrain == TerrainType.TRAIL && mudAlert.mudLevel != MudLevel.SEC) {
            alertsList.add("Boue : ${mudAlert.mudLevel.label} (${weather.past72hRainMm} mm/72h)")
        }
        if (weather.slotRainMm >= 0.5) {
            alertsList.add("Pluie : ${weather.slotRainMm} mm")
        }
        if (uvAlert.needsGlassesAndCap) {
            alertsList.add(if (uvAlert.needsSunscreen) "UV ${weather.maxUvIndex} (Lunettes+Casquette+Crème)" else "UV ${weather.maxUvIndex} (Lunettes+Casquette)")
        }
        if (selectedShoe?.isOver90Percent == true) {
            alertsList.add("Usure chaussures > 90%")
        }
        val widgetAlerts = if (alertsList.isEmpty()) "Aucune alerte (conditions optimales)" else alertsList.joinToString(" • ")
        val effortKm = ((plannedDistanceKm + elevationGainDPlusMeters / 100.0) * 10.0).roundToInt() / 10.0

        return FullRunRecommendation(
            weather = weather,
            sessionType = sessionType,
            terrain = terrain,
            plannedDistanceKm = plannedDistanceKm,
            elevationGainDPlusMeters = elevationGainDPlusMeters,
            effortDistanceKm = effortKm,
            effectiveReferenceTempC = effectiveRefTemp,
            thermalAdjustmentExplanation = thermalExplanation,
            gpxTrack = gpxTrack,
            mudAlert = mudAlert,
            uvAlert = uvAlert,
            selectedShoe = selectedShoe,
            shoeSelectionReason = shoeReason,
            outfitItems = outfit,
            accessoriesItems = accessories,
            nutrition = nutrition,
            nutritionTimeline = nutritionTimeline,
            similarRuns = similarRuns,
            postRaceBagItems = effectivePostRaceBag,
            hasAutoWarmJacketInBag = needsWarmJacket,
            retroplanningSteps = buildRetroplanning(retroConfig),
            widgetAlertSummary = widgetAlerts
        )
    }

    /**
     * Génère la fiche récapitulative complète de la sortie (Météo, Chaussures, Tenue, Nutrition, Check-list).
     */
    fun buildFullRunSummarySheet(
        locationName: String,
        latitude: Double,
        longitude: Double,
        dayOffset: Int,
        startHour: Int,
        startMinute: Int,
        recommendation: FullRunRecommendation,
        beforeChecklist: List<ChecklistItemEntity>,
        includeGpsLink: Boolean = true
    ): String {
        val dayLabel = when (dayOffset) {
            0 -> "Aujourd'hui"
            1 -> "Demain"
            else -> "J+$dayOffset"
        }
        val startTotalMin = startHour * 60 + startMinute
        val returnTotalMin = startTotalMin + recommendation.weather.durationMinutes
        val w = recommendation.weather

        return buildString {
            appendLine("=== FICHE SORTIE RUNCOACH MATOS ===")
            appendLine("📅 Créneau : $dayLabel • Départ ${formatMinutesOfDay(startTotalMin)} → Retour estimé ${formatMinutesOfDay(returnTotalMin)} (${w.durationMinutes} min)")
            appendLine("📍 Lieu : $locationName")
            appendLine(
                "⛰️ Sortie : ${recommendation.terrain.label} • ${recommendation.sessionType.label} • ${recommendation.plannedDistanceKm} km" +
                    if (recommendation.elevationGainDPlusMeters > 0) " • ${recommendation.elevationGainDPlusMeters} m D+ (${recommendation.effortDistanceKm} km-effort)" else ""
            )
            if (recommendation.gpxTrack != null) {
                val gpx = recommendation.gpxTrack
                appendLine("🗺️ Trace GPX : ${gpx.trackName}")
                gpx.surfaceBreakdown?.let { sb ->
                    if (sb.overpassError == null) {
                        appendLine("   Revêtement : ${sb.bitumePercent.roundToInt()}% bitume • ${sb.cheminStabilisePercent.roundToInt()}% stabilisé • ${sb.terreForetPercent.roundToInt()}% terre/forêt")
                    }
                }
            }
            appendLine()
            appendLine("--- MÉTÉO SUR LE CRÉNEAU ---")
            appendLine("• Conditions : ${w.weatherConditionLabel} | ${w.realTempC}°C réelle (${w.effectiveReferenceTempLabel(recommendation.effectiveReferenceTempC)})")
            appendLine("• Vent : ${w.maxWindKmh.roundToInt()} km/h (rafales ${w.maxWindGustsKmh.roundToInt()} km/h • ${w.windDirectionCardinal}) | Humidité : ${w.avgHumidityPercent}%")
            appendLine("• Pluie créneau : ${w.slotRainMm} mm (Proba ${w.maxRainProbabilityPercent}%)")
            appendLine("• Pluie 72h : ${w.past72hRainMm} mm (${recommendation.mudAlert.mudLevel.label}) | UV max : ${w.maxUvIndex}")
            appendLine()
            appendLine("--- CHAUSSURES & TENUE ---")
            recommendation.selectedShoe?.let {
                appendLine("👟 Chaussures : ${it.name} (${it.type.label} • ${it.currentKm.roundToInt()}/${it.maxKm.roundToInt()} km)")
            }
            recommendation.outfitItems.forEach { item ->
                appendLine("👕 $item")
            }
            recommendation.accessoriesItems.forEach { acc ->
                appendLine("🎒 $acc")
            }
            appendLine()
            appendLine("--- NUTRITION & HYDRATATION ---")
            appendLine("⚡ Pendant : ${recommendation.nutrition.ruleExplanation}")
            if (recommendation.nutrition.gelIntakeSchedule.isNotBlank()) {
                appendLine("⏱️ Prises : ${recommendation.nutrition.gelIntakeSchedule}")
            }
            if (recommendation.nutrition.electrolytesAdvice.isNotBlank()) {
                appendLine("🧂 Électrolytes : ${recommendation.nutrition.electrolytesAdvice}")
            }
            recommendation.nutritionTimeline.forEach { step ->
                appendLine("• [${step.timingLabel}] ${step.phase.badgeLabel} : ${step.foodTypesAdvice}")
            }
            if (beforeChecklist.isNotEmpty()) {
                appendLine()
                appendLine("--- CHECK-LIST AVANT DÉPART ---")
                beforeChecklist.forEach { item ->
                    val mark = if (item.checked) "[x]" else "[ ]"
                    appendLine("$mark ${item.label}")
                }
            }
            if (includeGpsLink) {
                val latFormatted = String.format(java.util.Locale.US, "%.5f", latitude)
                val lonFormatted = String.format(java.util.Locale.US, "%.5f", longitude)
                appendLine()
                appendLine("📌 Point GPS départ : https://maps.google.com/?q=$latFormatted,$lonFormatted")
            }
        }.trim()
    }

    private fun WeatherSlotAnalysis.effectiveReferenceTempLabel(effectiveRef: Double): String {
        return "réf ajustée ${effectiveRef}°C"
    }
}


