package com.example.data.model

enum class TerrainType(val label: String) {
    ROUTE("Route"),
    TRAIL("Trail")
}

enum class ShoeType(val label: String, val shortLabel: String) {
    ROUTE("Route", "Route"),
    TRAIL_POLYVALENT("Trail polyvalent", "Polyvalent"),
    TRAIL_CRAMPONS("Trail crampons", "Crampons")
}

enum class MudLevel(val label: String, val description: String) {
    SEC("Sec", "< 5 mm sur 72 h — Sentiers secs et roulants"),
    HUMIDE("Humide", "5 à 15 mm sur 72 h — Sentiers souples / humides"),
    BOUEUX("Boueux", "> 15 mm sur 72 h — Chaussures à crampons & guêtres recommandées")
}

enum class RunSessionType(val label: String, val tempOffset: Double) {
    ENDURANCE("Endurance / Footing", 0.0),
    SORTIE_LONGUE("Sortie longue", -1.0),
    FRACTIONNE("Fractionné / Seuil", 3.0),
    COURSE("Course (Compétition)", 4.0)
}

enum class ThermalFeeling(val label: String, val emoji: String) {
    TROP_FROID("Trop froid", "🥶"),
    PARFAIT("Parfait", "👌"),
    TROP_CHAUD("Trop chaud", "🥵")
}

enum class GelFeedback(val label: String, val deltaCarbPerHour: Int) {
    PAS_ASSEZ("Pas assez", +5),
    PILE("Pile", 0),
    TROP("Trop", -5)
}

enum class GearCategory(val label: String) {
    HAUTS("Hauts (courts & longs)"),
    VESTES("Vestes & Protection pluie/vent"),
    BAS("Bas (shorts & cuissards)"),
    ACCESSOIRES_THERMIQUES("Gants, Bonnet & Chaussettes"),
    TRAIL_SPECIFIQUE("Spécifique Trail & Nuit"),
    SOLEIL("Protection Soleil (UV)"),
    HYDRATATION_NUTRITION("Hydratation & Gels")
}

enum class ChecklistType {
    BEFORE_START,
    POST_RACE_BAG
}

enum class SurfaceCategory(val label: String) {
    BITUME("Bitume"),
    CHEMIN_STABILISE("Chemin stabilisé"),
    TERRE_FORET("Terre / forêt"),
    INCONNU("Inconnu")
}

data class GpxPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null
)

data class GpxColoredSegment(
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val lengthMeters: Double,
    val category: SurfaceCategory
)

data class GpxSurfaceBreakdown(
    val bitumeMeters: Double,
    val cheminStabiliseMeters: Double,
    val terreForetMeters: Double,
    val inconnuMeters: Double,
    val totalMeters: Double,
    val bitumePercent: Double,
    val cheminStabilisePercent: Double,
    val terreForetPercent: Double,
    val inconnuPercent: Double,
    val isUnreliable: Boolean, // true si inconnuPercent > 30%
    val coloredSegments: List<GpxColoredSegment>,
    val overpassError: String? = null,
    val isManualOrOfflineEstimate: Boolean = false
)

enum class ThermalProfile(val label: String, val tempDeltaC: Double) {
    FRILEUX("Frileux (-1.5°C)", -1.5),
    NEUTRE("Neutre (0°C)", 0.0),
    CHAUD("Chaud (+1.5°C)", 1.5)
}

data class HourlyWeatherStep(
    val hourLabel: String,
    val tempC: Double,
    val rainMm: Double,
    val rainProbPercent: Int,
    val windKmh: Double,
    val windGustsKmh: Double,
    val weatherCodeLabel: String
)

data class BestWeatherSlotSuggestion(
    val dayOffset: Int,
    val dayLabel: String,
    val startHour: Int,
    val endHour: Int,
    val avgTempC: Double,
    val maxWindKmh: Double,
    val slotRainMm: Double,
    val maxRainProbPercent: Int,
    val weatherConditionLabel: String,
    val score: Int
)

data class GpxTrackAnalysis(
    val trackIdHash: String,
    val trackName: String,
    val points: List<GpxPoint>,
    val totalDistanceKm: Double,
    val elevationGainDPlusMeters: Int,
    val startLat: Double,
    val startLon: Double,
    val minElevationMeters: Int? = null,
    val maxElevationMeters: Int? = null,
    val summitLat: Double? = null,
    val summitLon: Double? = null,
    val isAnalyzingSurface: Boolean = false,
    val surfaceBreakdown: GpxSurfaceBreakdown? = null
)

data class GeoLocationResult(
    val name: String,
    val country: String,
    val admin1: String?,
    val latitude: Double,
    val longitude: Double
) {
    val displayLabel: String
        get() = buildString {
            append(name)
            if (!admin1.isNullOrBlank()) append(", $admin1")
            if (country.isNotBlank()) append(" ($country)")
        }
}

data class WeatherSlotAnalysis(
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val startDateTimeIso: String,
    val durationMinutes: Int,
    val realTempC: Double,
    val apparentTempC: Double,
    val referenceTempC: Double,
    val maxWindKmh: Double,
    val maxWindGustsKmh: Double = maxWindKmh,
    val windDirectionDegrees: Int = 225,
    val windDirectionCardinal: String = "SO",
    val slotRainMm: Double,
    val maxRainProbabilityPercent: Int = 15,
    val past72hRainMm: Double,
    val maxUvIndex: Double,
    val avgHumidityPercent: Int = 65,
    val weatherCode: Int = 1,
    val weatherConditionLabel: String = "Éclaircies",
    val tempDeltaAcrossSlotC: Double = 0.0,
    val hourlySteps: List<HourlyWeatherStep> = emptyList(),
    val summitTempC: Double? = null,
    val summitElevationMeters: Int? = null,
    val fetchedAtTimeLabel: String = "",
    val isFromOfflineCache: Boolean = false,
    val isNightOrDawn: Boolean
) {
    val needsJacketJustInCase: Boolean
        get() = slotRainMm < 1.0 && (maxRainProbabilityPercent >= 40 || maxWindGustsKmh >= 35.0)
}

data class RetroplanningConfig(
    val raceStartHour: Int = 9,
    val raceStartMinute: Int = 0,
    val travelMinutes: Int = 45,
    val onSiteMarginMinutes: Int = 45,
    val breakfastBeforeStartMinutes: Int = 180,
    val wakeUpBeforeBreakfastMinutes: Int = 30
)

data class RetroplanningStep(
    val id: String,
    val title: String,
    val subtitle: String,
    val timeFormatted: String,
    val minutesFromMidnight: Int
)

data class NutritionRecommendation(
    val durationMinutes: Int,
    val targetCarbPerHour: Int,
    val totalCarbsGrams: Int,
    val gelWeightGrams: Int,
    val recommendedGelsCount: Int,
    val waterLiters: Double,
    val ruleExplanation: String,
    val gelIntakeSchedule: String = "",
    val electrolytesAdvice: String = "",
    val solidOrSaltyAdvice: String = "",
    val waterRefillCount: Int = 0,
    val carriedWaterPerSegmentLiters: Double = waterLiters
)

data class UvAlertInfo(
    val maxUv: Double,
    val needsGlassesAndCap: Boolean,
    val needsSunscreen: Boolean,
    val message: String
)

data class MudAlertInfo(
    val isApplicable: Boolean,
    val rain72hMm: Double,
    val mudLevel: MudLevel,
    val recommendCrampons: Boolean,
    val recommendGaiters: Boolean,
    val hasGaitersInGear: Boolean,
    val message: String
)

enum class NutritionPhaseType(val badgeLabel: String) {
    VEILLE_SOIR("Veille au soir"),
    AVANT_DEPART("Avant-départ"),
    PENDANT_SORTIE("Pendant"),
    RECUP_IMMEDIATE("Récup & Réhydratation"),
    REPAS_APRES("Après-sortie")
}

data class NutritionTimelineStep(
    val id: String,
    val phase: NutritionPhaseType,
    val timingLabel: String,
    val title: String,
    val foodTypesAdvice: String,
    val hydrationAdvice: String,
    val contextTag: String
)



