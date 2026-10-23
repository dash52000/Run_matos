package com.example.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Geocoder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ChecklistItemEntity
import com.example.data.local.GearItemEntity
import com.example.data.local.RunCoachDatabase
import com.example.data.local.RunHistoryEntity
import com.example.data.local.ShoeEntity
import com.example.data.model.ChecklistType
import com.example.data.model.GearCategory
import com.example.data.model.GelFeedback
import com.example.data.model.GeoLocationResult
import com.example.data.model.MudLevel
import com.example.data.model.RetroplanningConfig
import com.example.data.model.RunSessionType
import com.example.data.model.ShoeType
import com.example.data.model.TerrainType
import com.example.data.model.ThermalFeeling
import com.example.data.model.WeatherSlotAnalysis
import com.example.data.model.GpxTrackAnalysis
import com.example.data.model.BestWeatherSlotSuggestion
import com.example.data.model.ThermalProfile
import com.example.data.remote.GpxAndOverpassAnalyzer
import com.example.data.remote.OpenMeteoClient
import com.example.domain.FullRunRecommendation
import com.example.domain.RecommendationEngine
import com.example.notifications.RetroplanningNotificationReceiver
import com.example.widget.NextRunWidgetProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class NewRunFormState(
    val locationName: String = "Chamonix-Mont-Blanc",
    val latitude: Double = 45.9237,
    val longitude: Double = 6.8694,
    val dayOffset: Int = 0, // 0 = Aujourd'hui, 1 = Demain, etc.
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val durationMinutes: Int = 100,
    val plannedDistanceKm: Double = 18.0,
    val elevationGainDPlusMeters: Int = 0,
    val paceSecPerKm: Int = 330, // 5:30 /km par défaut
    val gpxTrack: GpxTrackAnalysis? = null,
    val terrain: TerrainType = TerrainType.TRAIL,
    val sessionType: RunSessionType = RunSessionType.SORTIE_LONGUE,
    val thermalProfile: ThermalProfile = ThermalProfile.NEUTRE,
    val waterRefillCount: Int = 0,
    val recentLocations: List<GeoLocationResult> = listOf(
        GeoLocationResult("Chamonix-Mont-Blanc", "France", "Auvergne-Rhône-Alpes", 45.9237, 6.8694),
        GeoLocationResult("Annecy", "France", "Auvergne-Rhône-Alpes", 45.8992, 6.1294),
        GeoLocationResult("Grenoble", "France", "Auvergne-Rhône-Alpes", 45.1885, 5.7245),
        GeoLocationResult("Lyon", "France", "Auvergne-Rhône-Alpes", 45.7640, 4.8357)
    ),
    val bestSlots48h: List<BestWeatherSlotSuggestion> = emptyList(),
    val isLoadingWeather: Boolean = false,
    val weatherError: String? = null,
    val citySearchQuery: String = "",
    val citySearchResults: List<GeoLocationResult> = emptyList(),
    val isSearchingCity: Boolean = false,
    val manualShoeOverrideId: Long? = null,
    val statusBannerMessage: String? = null
)

data class NutritionSettingsState(
    val carbRateMedium: Int = 45, // 45 g/h par défaut entre 1h et 2h30
    val carbRateLong: Int = 75,   // 75 g/h par défaut au-delà de 2h30
    val gelCarbsGrams: Int = 25,
    val asphaltThresholdPercent: Int = 60 // 60 % de bitume par défaut pour chaussures route sur trace GPX
)

data class PastRunDraftState(
    val dateEpochMillis: Long = System.currentTimeMillis() - 24L * 3600L * 1000L,
    val dateFormatted: String = "",
    val startHour: Int = 9,
    val durationMinutes: Int = 75,
    val locationName: String = "Chamonix-Mont-Blanc",
    val latitude: Double = 45.9237,
    val longitude: Double = 6.8694,
    val citySearchQuery: String = "",
    val citySearchResults: List<GeoLocationResult> = emptyList(),
    val isSearchingCity: Boolean = false,
    val terrain: TerrainType = TerrainType.TRAIL,
    val sessionType: RunSessionType = RunSessionType.ENDURANCE,
    val isLoadingWeather: Boolean = false,
    val weatherStatusMessage: String? = null,
    val weather: WeatherSlotAnalysis = OpenMeteoClient.fallbackAnalysis(
        locationName = "Chamonix-Mont-Blanc",
        latitude = 45.9237,
        longitude = 6.8694,
        startHourOfDay = 9,
        durationMinutes = 75,
        sessionType = RunSessionType.ENDURANCE
    ),
    val suggestedOutfit: String = "T-shirt manches longues respirant, Short léger"
)

class RunCoachViewModel(application: Application) : AndroidViewModel(application) {

    private val db = RunCoachDatabase.getInstance(application)
    private val dao = db.dao()
    private val prefs = application.getSharedPreferences("runcoach_nutrition_prefs", Context.MODE_PRIVATE)

    private val _formState = MutableStateFlow(NewRunFormState())
    val formState: StateFlow<NewRunFormState> = _formState.asStateFlow()

    private val _weatherAnalysis = MutableStateFlow(
        OpenMeteoClient.fallbackAnalysis(
            locationName = "Chamonix-Mont-Blanc",
            latitude = 45.9237,
            longitude = 6.8694,
            startHourOfDay = 9,
            durationMinutes = 120,
            sessionType = RunSessionType.SORTIE_LONGUE
        )
    )
    val weatherAnalysis: StateFlow<WeatherSlotAnalysis> = _weatherAnalysis.asStateFlow()

    private val _nutritionSettings = MutableStateFlow(loadNutritionSettings())
    val nutritionSettings: StateFlow<NutritionSettingsState> = _nutritionSettings.asStateFlow()

    private val _retroConfig = MutableStateFlow(RetroplanningConfig(raceStartHour = 9, raceStartMinute = 0))
    val retroConfig: StateFlow<RetroplanningConfig> = _retroConfig.asStateFlow()

    private val _pastRunDraft = MutableStateFlow(
        PastRunDraftState(
            dateFormatted = SimpleDateFormat("dd MMM yyyy", Locale.FRENCH)
                .format(Date(System.currentTimeMillis() - 24L * 3600L * 1000L))
        )
    )
    val pastRunDraft: StateFlow<PastRunDraftState> = _pastRunDraft.asStateFlow()

    val shoes: StateFlow<List<ShoeEntity>> = dao.observeShoes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gearItems: StateFlow<List<GearItemEntity>> = dao.observeGearItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val beforeStartChecklist: StateFlow<List<ChecklistItemEntity>> =
        dao.observeChecklist(ChecklistType.BEFORE_START)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val postRaceChecklist: StateFlow<List<ChecklistItemEntity>> =
        dao.observeChecklist(ChecklistType.POST_RACE_BAG)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val runHistory: StateFlow<List<RunHistoryEntity>> = dao.observeRunHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recommendation: StateFlow<FullRunRecommendation?> = combine(
        _formState,
        _weatherAnalysis,
        shoes,
        gearItems,
        combine(postRaceChecklist, runHistory, _nutritionSettings, _retroConfig) { post, hist, nut, retro ->
            Quadruple(post, hist, nut, retro)
        }
    ) { form, weather, shoeList, gearList, quad ->
        val (postBag, historyList, nutSettings, retro) = quad
        val baseRec = RecommendationEngine.generateRecommendation(
            weather = weather,
            sessionType = form.sessionType,
            terrain = form.terrain,
            plannedDistanceKm = form.plannedDistanceKm,
            elevationGainDPlusMeters = form.elevationGainDPlusMeters,
            gpxTrack = form.gpxTrack,
            asphaltThresholdPercent = nutSettings.asphaltThresholdPercent,
            shoes = shoeList,
            gearItems = gearList,
            postRaceChecklist = postBag,
            history = historyList,
            carbRateMedium = nutSettings.carbRateMedium,
            carbRateLong = nutSettings.carbRateLong,
            gelCarbsGrams = nutSettings.gelCarbsGrams,
            retroConfig = retro,
            startHour = form.startHour,
            startMinute = form.startMinute,
            thermalProfile = form.thermalProfile,
            waterRefillCount = form.waterRefillCount
        )

        val finalRec = if (form.manualShoeOverrideId != null) {
            val manualShoe = shoeList.find { it.id == form.manualShoeOverrideId }
            if (manualShoe != null) {
                baseRec.copy(
                    selectedShoe = manualShoe,
                    shoeSelectionReason = "Sélection manuelle (${manualShoe.type.label} • ${manualShoe.currentKm.roundToInt()}/${manualShoe.maxKm.roundToInt()} km)"
                )
            } else baseRec
        } else baseRec

        // Synchronise le widget d'accueil avec la recommandation actuelle
        val outfitSummary = buildString {
            append(finalRec.outfitItems.take(2).joinToString(", "))
            finalRec.selectedShoe?.let { append(", ${it.name}") }
            if (finalRec.nutrition.recommendedGelsCount > 0) {
                append(", ${finalRec.nutrition.recommendedGelsCount} gels")
            }
        }
        NextRunWidgetProvider.saveAndPushWidgetUpdate(
            context = getApplication(),
            locationName = form.locationName,
            terrainLabel = form.terrain.label,
            distanceKm = form.plannedDistanceKm,
            referenceTempC = finalRec.effectiveReferenceTempC,
            outfitSummary = outfitSummary,
            alertsSummary = finalRec.widgetAlertSummary
        )

        finalRec
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        val savedProfileName = prefs.getString("thermal_profile", ThermalProfile.NEUTRE.name) ?: ThermalProfile.NEUTRE.name
        val savedProfile = runCatching { ThermalProfile.valueOf(savedProfileName) }.getOrDefault(ThermalProfile.NEUTRE)
        _formState.update { it.copy(thermalProfile = savedProfile) }

        viewModelScope.launch {
            seedInitialDataIfNeeded()
            refreshWeatherFromOpenMeteo()
        }
    }

    private fun loadNutritionSettings(): NutritionSettingsState {
        return NutritionSettingsState(
            carbRateMedium = prefs.getInt("carb_rate_medium", 45).coerceIn(30, 90),
            carbRateLong = prefs.getInt("carb_rate_long", 75).coerceIn(30, 90),
            gelCarbsGrams = prefs.getInt("gel_carbs_grams", 25).coerceIn(15, 50),
            asphaltThresholdPercent = prefs.getInt("asphalt_threshold_pct", 60).coerceIn(20, 95)
        )
    }

    private fun saveNutritionSettings(state: NutritionSettingsState) {
        prefs.edit()
            .putInt("carb_rate_medium", state.carbRateMedium.coerceIn(30, 90))
            .putInt("carb_rate_long", state.carbRateLong.coerceIn(30, 90))
            .putInt("gel_carbs_grams", state.gelCarbsGrams.coerceIn(15, 50))
            .putInt("asphalt_threshold_pct", state.asphaltThresholdPercent.coerceIn(20, 95))
            .apply()
        _nutritionSettings.value = state
    }

    private suspend fun seedInitialDataIfNeeded() = withContext(Dispatchers.IO) {
        val existingCodes = dao.getAllGearOnce().map { it.code }.toSet()
        val defaultGear = listOf(
            GearItemEntity("debardeur", "Débardeur ultra-respirant (Chaleur > 20°C)", GearCategory.HAUTS, true),
            GearItemEntity("haut_court", "T-shirt technique manches courtes", GearCategory.HAUTS, true),
            GearItemEntity("manchettes", "Manchettes amovibles (tenue modulable)", GearCategory.HAUTS, true),
            GearItemEntity("haut_long", "Maillot manches longues thermique", GearCategory.HAUTS, true),
            GearItemEntity("veste_pluie", "Veste imperméable 20 000 Schmerber", GearCategory.VESTES, true),
            GearItemEntity("coupe_vent", "Coupe-vent léger déperlant", GearCategory.VESTES, true),
            GearItemEntity("short", "Short de course respirant", GearCategory.BAS, true),
            GearItemEntity("cuissard_court", "Cuissard court", GearCategory.BAS, true),
            GearItemEntity("cuissard_long", "Collant / Cuissard long hiver", GearCategory.BAS, true),
            GearItemEntity("chaussettes", "Chaussettes techniques Route & Trail", GearCategory.ACCESSOIRES_THERMIQUES, true),
            GearItemEntity("gants", "Gants fins coupe-vent", GearCategory.ACCESSOIRES_THERMIQUES, true),
            GearItemEntity("bonnet", "Bonnet / Bandeau thermique", GearCategory.ACCESSOIRES_THERMIQUES, true),
            GearItemEntity("tour_de_cou", "Tour de cou / Buff multifonction", GearCategory.ACCESSOIRES_THERMIQUES, true),
            GearItemEntity("guetres", "Guêtres stop-boue & débris", GearCategory.TRAIL_SPECIFIQUE, true),
            GearItemEntity("batons_trail", "Bâtons de trail pliables", GearCategory.TRAIL_SPECIFIQUE, true),
            GearItemEntity("frontale", "Lampe frontale rechargeable", GearCategory.TRAIL_SPECIFIQUE, true),
            GearItemEntity("lunettes_soleil", "Lunettes de soleil photochromiques", GearCategory.SOLEIL, true),
            GearItemEntity("casquette", "Casquette respirante anti-UV", GearCategory.SOLEIL, true),
            GearItemEntity("creme_solaire", "Crème solaire sport SPF 50+", GearCategory.SOLEIL, true),
            GearItemEntity("gilet_hydratation", "Gilet / Ceinture d'hydratation + flasques", GearCategory.HYDRATATION_NUTRITION, true),
            GearItemEntity("gels", "Gels énergétiques (25 g glucides)", GearCategory.HYDRATATION_NUTRITION, true)
        )
        val missingGear = defaultGear.filter { it.code !in existingCodes }
        if (missingGear.isNotEmpty()) {
            dao.insertGearItems(missingGear)
        }

        if (dao.getAllShoesOnce().isEmpty()) {
            val s1 = dao.insertShoe(
                ShoeEntity(
                    name = "Salomon S/Lab Genesis",
                    type = ShoeType.TRAIL_POLYVALENT,
                    currentKm = 240.0,
                    maxKm = 700.0
                )
            )
            val s2 = dao.insertShoe(
                ShoeEntity(
                    name = "Salomon Speedcross 6 Mud",
                    type = ShoeType.TRAIL_CRAMPONS,
                    currentKm = 185.0,
                    maxKm = 700.0
                )
            )
            val s3 = dao.insertShoe(
                ShoeEntity(
                    name = "Hoka Speedgoat 5",
                    type = ShoeType.TRAIL_POLYVALENT,
                    currentKm = 648.0, // > 90 % de 700 km -> déclenche l'alerte d'usure !
                    maxKm = 700.0
                )
            )
            val s4 = dao.insertShoe(
                ShoeEntity(
                    name = "Nike Pegasus 41 Road",
                    type = ShoeType.ROUTE,
                    currentKm = 310.0,
                    maxKm = 700.0
                )
            )

            if (dao.getAllRunHistoryOnce().isEmpty()) {
                val now = System.currentTimeMillis()
                val dayMs = 24 * 3600 * 1000L
                val sampleRuns = listOf(
                    RunHistoryEntity(
                        dateEpochMillis = now - 4 * dayMs,
                        dateFormatted = formatDate(now - 4 * dayMs),
                        locationName = "Chamonix-Mont-Blanc",
                        sessionType = RunSessionType.SORTIE_LONGUE,
                        terrain = TerrainType.TRAIL,
                        plannedDistanceKm = 18.0,
                        actualDistanceKm = 18.5,
                        durationMinutes = 135,
                        realTempC = 11.5,
                        referenceTempC = 10.5,
                        windKmh = 14.0,
                        rainMm = 0.0,
                        rain72hMm = 18.2,
                        mudLevel = MudLevel.BOUEUX,
                        maxUvIndex = 4.2,
                        outfitWorn = "T-shirt manches longues respirant, Cuissard court, Guêtres de trail, Lunettes + Casquette",
                        shoeId = s2,
                        shoeName = "Salomon Speedcross 6 Mud",
                        shoeType = ShoeType.TRAIL_CRAMPONS,
                        gelsCarried = 4,
                        carbRateUsed = 45,
                        thermalFeeling = ThermalFeeling.PARFAIT,
                        gelFeedback = GelFeedback.PILE,
                        notes = "Sentiers gras en sous-bois, accroche crampons parfaite."
                    ),
                    RunHistoryEntity(
                        dateEpochMillis = now - 11 * dayMs,
                        dateFormatted = formatDate(now - 11 * dayMs),
                        locationName = "Annecy",
                        sessionType = RunSessionType.ENDURANCE,
                        terrain = TerrainType.ROUTE,
                        plannedDistanceKm = 12.0,
                        actualDistanceKm = 12.2,
                        durationMinutes = 65,
                        realTempC = 13.0,
                        referenceTempC = 12.0,
                        windKmh = 12.0,
                        rainMm = 0.0,
                        rain72hMm = 2.0,
                        mudLevel = MudLevel.SEC,
                        maxUvIndex = 3.5,
                        outfitWorn = "T-shirt manches courtes, Short léger, Lunettes + Casquette",
                        shoeId = s4,
                        shoeName = "Nike Pegasus 41 Road",
                        shoeType = ShoeType.ROUTE,
                        gelsCarried = 2,
                        carbRateUsed = 45,
                        thermalFeeling = ThermalFeeling.PARFAIT,
                        gelFeedback = GelFeedback.PILE,
                        notes = "Tour du lac fluide."
                    ),
                    RunHistoryEntity(
                        dateEpochMillis = now - 25 * dayMs,
                        dateFormatted = formatDate(now - 25 * dayMs),
                        locationName = "Grenoble - Chartreuse",
                        sessionType = RunSessionType.COURSE,
                        terrain = TerrainType.TRAIL,
                        plannedDistanceKm = 28.0,
                        actualDistanceKm = 28.4,
                        durationMinutes = 210,
                        realTempC = 4.5,
                        referenceTempC = 6.5,
                        windKmh = 28.0,
                        rainMm = 3.4,
                        rain72hMm = 21.0,
                        mudLevel = MudLevel.BOUEUX,
                        maxUvIndex = 2.0,
                        outfitWorn = "Maillot manches longues, Veste imperméable, Cuissard court, Gants fins, Guêtres",
                        shoeId = s2,
                        shoeName = "Salomon Speedcross 6 Mud",
                        shoeType = ShoeType.TRAIL_CRAMPONS,
                        gelsCarried = 10,
                        carbRateUsed = 75,
                        thermalFeeling = ThermalFeeling.TROP_FROID,
                        gelFeedback = GelFeedback.PAS_ASSEZ,
                        notes = "Passage de crête venté et pluvieux."
                    ),
                    RunHistoryEntity(
                        dateEpochMillis = now - 48 * dayMs,
                        dateFormatted = formatDate(now - 48 * dayMs),
                        locationName = "Lyon - Parc de la Tête d'Or",
                        sessionType = RunSessionType.FRACTIONNE,
                        terrain = TerrainType.ROUTE,
                        plannedDistanceKm = 10.0,
                        actualDistanceKm = 10.5,
                        durationMinutes = 55,
                        realTempC = 19.0,
                        referenceTempC = 22.0,
                        windKmh = 8.0,
                        rainMm = 0.0,
                        rain72hMm = 0.0,
                        mudLevel = MudLevel.SEC,
                        maxUvIndex = 6.8,
                        outfitWorn = "T-shirt manches courtes, Short léger, Lunettes + Casquette + Crème solaire",
                        shoeId = s1,
                        shoeName = "Salomon S/Lab Genesis",
                        shoeType = ShoeType.TRAIL_POLYVALENT,
                        gelsCarried = 0,
                        carbRateUsed = 0,
                        thermalFeeling = ThermalFeeling.TROP_CHAUD,
                        gelFeedback = GelFeedback.PILE,
                        notes = "Séance VMA au soleil."
                    )
                )
                dao.insertRunHistoryList(sampleRuns)
            }
        }

        if (dao.getAllChecklistsOnce().isEmpty()) {
            val defaultChecklists = listOf(
                ChecklistItemEntity(listType = ChecklistType.BEFORE_START, label = "Clés maison / voiture"),
                ChecklistItemEntity(listType = ChecklistType.BEFORE_START, label = "Téléphone chargé (> 80 %)"),
                ChecklistItemEntity(listType = ChecklistType.BEFORE_START, label = "Montre GPS chargée + trace GPX"),
                ChecklistItemEntity(listType = ChecklistType.BEFORE_START, label = "Frontale chargée (si départ tôt / tard)"),
                ChecklistItemEntity(listType = ChecklistType.BEFORE_START, label = "Flasques remplies & gels dans le gilet"),
                ChecklistItemEntity(listType = ChecklistType.POST_RACE_BAG, label = "T-shirt sec de rechange"),
                ChecklistItemEntity(listType = ChecklistType.POST_RACE_BAG, label = "Chaussettes sèches & claquettes / chaussures repos"),
                ChecklistItemEntity(listType = ChecklistType.POST_RACE_BAG, label = "Serviette microfibre & lingettes"),
                ChecklistItemEntity(listType = ChecklistType.POST_RACE_BAG, label = "Boisson de récupération & collation salée")
            )
            dao.insertChecklistItems(defaultChecklists)
        }
    }

    private fun formatDate(millis: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.FRENCH)
        return sdf.format(Date(millis))
    }

    // --- Form & Open-Meteo Actions ---

    private fun estimateDurationForDistance(
        distanceKm: Double,
        terrain: TerrainType = _formState.value.terrain,
        dPlusMeters: Int = _formState.value.elevationGainDPlusMeters,
        paceSecPerKm: Int = _formState.value.paceSecPerKm
    ): Int {
        val safeDist = if (distanceKm.isFinite()) distanceKm.coerceIn(0.1, 200.0) else 10.0
        val safeDPlus = dPlusMeters.coerceIn(0, 10000)
        val safePace = paceSecPerKm.coerceIn(210, 540)
        // Calcul basé sur les Km-Effort (100 m D+ = +1 km d'effort) × allure cible (min/km), arrondi à 5 min près (min 30 min)
        val effortKm = safeDist + (safeDPlus / 100.0)
        val rawMinutes = effortKm * (safePace / 60.0)
        if (!rawMinutes.isFinite() || rawMinutes <= 0.0) return 30
        val stepped = ((rawMinutes / 5.0).roundToInt() * 5)
        return stepped.coerceIn(30, 360)
    }

    fun updateTerrain(terrain: TerrainType) {
        val defaultPaceForTerrain = if (terrain == TerrainType.TRAIL && _formState.value.paceSecPerKm == 300) 345
        else if (terrain == TerrainType.ROUTE && _formState.value.paceSecPerKm == 345) 300
        else _formState.value.paceSecPerKm
        val newDur = estimateDurationForDistance(
            distanceKm = _formState.value.plannedDistanceKm,
            terrain = terrain,
            dPlusMeters = _formState.value.elevationGainDPlusMeters,
            paceSecPerKm = defaultPaceForTerrain
        )
        _formState.update {
            it.copy(
                terrain = terrain,
                paceSecPerKm = defaultPaceForTerrain,
                durationMinutes = newDur,
                manualShoeOverrideId = null
            )
        }
        recalculateWeatherSlot()
    }

    fun updatePlannedDistance(distanceKm: Double) {
        val safeDist = if (distanceKm.isFinite()) distanceKm.coerceIn(0.1, 200.0) else 10.0
        val estimatedMinutes = estimateDurationForDistance(
            distanceKm = safeDist,
            terrain = _formState.value.terrain,
            dPlusMeters = _formState.value.elevationGainDPlusMeters,
            paceSecPerKm = _formState.value.paceSecPerKm
        )
        _formState.update {
            it.copy(
                plannedDistanceKm = safeDist,
                durationMinutes = estimatedMinutes
            )
        }
        recalculateWeatherSlot()
    }

    fun updateElevationGainDPlus(dPlusMeters: Int) {
        val safeDPlus = dPlusMeters.coerceIn(0, 10000)
        val estimatedMinutes = estimateDurationForDistance(
            distanceKm = _formState.value.plannedDistanceKm,
            terrain = _formState.value.terrain,
            dPlusMeters = safeDPlus,
            paceSecPerKm = _formState.value.paceSecPerKm
        )
        _formState.update {
            it.copy(
                elevationGainDPlusMeters = safeDPlus,
                durationMinutes = estimatedMinutes
            )
        }
        recalculateWeatherSlot()
    }

    fun updatePaceSecPerKm(paceSec: Int) {
        val safePace = paceSec.coerceIn(210, 540) // de 3:30/km à 9:00/km
        val estimatedMinutes = estimateDurationForDistance(
            distanceKm = _formState.value.plannedDistanceKm,
            terrain = _formState.value.terrain,
            dPlusMeters = _formState.value.elevationGainDPlusMeters,
            paceSecPerKm = safePace
        )
        _formState.update {
            it.copy(
                paceSecPerKm = safePace,
                durationMinutes = estimatedMinutes
            )
        }
        recalculateWeatherSlot()
    }

    /**
     * Importe une trace GPX optionnelle :
     * - Calcule la distance et le D+ (champs pré-remplis, modifiables)
     * - Utilise le point de départ comme lieu pour la météo Open-Meteo
     * - Lance l'analyse du revêtement via l'API Overpass (mémorisée par hash)
     */
    fun importGpxFromXmlString(rawXml: String, fallbackName: String = "Trace GPX") {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.Default) {
                GpxAndOverpassAnalyzer.parseGpxContent(rawXml, fallbackName)
            }
            if (parsed == null) {
                _formState.update {
                    it.copy(statusBannerMessage = "Fichier GPX invalide ou aucun point (<trkpt>) trouvé.")
                }
                return@launch
            }

            val context = getApplication<Application>()
            val resolvedCity = resolveCityName(context, parsed.startLat, parsed.startLon)
            val estDur = estimateDurationForDistance(
                distanceKm = parsed.totalDistanceKm,
                terrain = _formState.value.terrain,
                dPlusMeters = parsed.elevationGainDPlusMeters
            )

            _formState.update {
                it.copy(
                    locationName = resolvedCity,
                    latitude = parsed.startLat,
                    longitude = parsed.startLon,
                    plannedDistanceKm = parsed.totalDistanceKm,
                    elevationGainDPlusMeters = parsed.elevationGainDPlusMeters,
                    durationMinutes = estDur,
                    gpxTrack = parsed,
                    manualShoeOverrideId = null,
                    statusBannerMessage = "Trace « ${parsed.trackName} » importée : ${parsed.totalDistanceKm} km • ${parsed.elevationGainDPlusMeters} m D+ • Analyse Overpass…"
                )
            }

            // Actualise la météo Open-Meteo au point de départ de la trace
            refreshWeatherFromOpenMeteo()

            // Analyse du revêtement via Overpass API (ou cache si déjà analysée)
            val currentTerrain = _formState.value.terrain
            val breakdown = withContext(Dispatchers.IO) {
                GpxAndOverpassAnalyzer.analyzeSurfaceWithOverpass(
                    track = parsed,
                    fallbackTerrain = currentTerrain
                )
            }
            val updatedTrack = parsed.copy(
                isAnalyzingSurface = false,
                surfaceBreakdown = breakdown
            )
            _formState.update { state ->
                if (state.gpxTrack?.trackIdHash == parsed.trackIdHash) {
                    val banner = if (breakdown.isManualOrOfflineEstimate && breakdown.overpassError != null) {
                        "Trace GPX (${parsed.totalDistanceKm} km, ${parsed.elevationGainDPlusMeters} m D+) : estimation appliquée (${breakdown.bitumePercent}% bitume, ${breakdown.cheminStabilisePercent}% stabilisé, ${breakdown.terreForetPercent}% terre)."
                    } else if (breakdown.overpassError != null) {
                        "Trace GPX chargée (${parsed.totalDistanceKm} km, ${parsed.elevationGainDPlusMeters} m D+). ${breakdown.overpassError}"
                    } else if (breakdown.isUnreliable) {
                        "Trace GPX analysée : ${breakdown.inconnuPercent}% inconnu (> 30 %) → Analyse peu fiable, règles actuelles appliquées."
                    } else {
                        "Revêtement GPX analysé : ${breakdown.bitumePercent}% bitume, ${breakdown.cheminStabilisePercent}% stabilisé, ${breakdown.terreForetPercent}% terre/forêt."
                    }
                    state.copy(gpxTrack = updatedTrack, statusBannerMessage = banner)
                } else state
            }
        }
    }

    fun retryGpxSurfaceAnalysis() {
        val currentTrack = _formState.value.gpxTrack ?: return
        val currentTerrain = _formState.value.terrain
        viewModelScope.launch {
            _formState.update {
                it.copy(
                    gpxTrack = currentTrack.copy(isAnalyzingSurface = true),
                    statusBannerMessage = "Nouvelle tentative d'analyse du revêtement via Overpass…"
                )
            }
            val breakdown = withContext(Dispatchers.IO) {
                GpxAndOverpassAnalyzer.analyzeSurfaceWithOverpass(
                    track = currentTrack,
                    forceRefresh = true,
                    fallbackTerrain = currentTerrain
                )
            }
            val updatedTrack = currentTrack.copy(
                isAnalyzingSurface = false,
                surfaceBreakdown = breakdown
            )
            _formState.update { state ->
                if (state.gpxTrack?.trackIdHash == currentTrack.trackIdHash) {
                    val banner = if (breakdown.isManualOrOfflineEstimate && breakdown.overpassError != null) {
                        "Trace GPX (${currentTrack.totalDistanceKm} km, ${currentTrack.elevationGainDPlusMeters} m D+) : estimation appliquée (${breakdown.bitumePercent}% bitume, ${breakdown.cheminStabilisePercent}% stabilisé, ${breakdown.terreForetPercent}% terre)."
                    } else if (breakdown.overpassError != null) {
                        "Trace GPX (${currentTrack.totalDistanceKm} km, ${currentTrack.elevationGainDPlusMeters} m D+). ${breakdown.overpassError}"
                    } else if (breakdown.isUnreliable) {
                        "Trace GPX analysée : ${breakdown.inconnuPercent}% inconnu (> 30 %) → Analyse peu fiable, règles actuelles appliquées."
                    } else {
                        "Revêtement GPX analysé : ${breakdown.bitumePercent}% bitume, ${breakdown.cheminStabilisePercent}% stabilisé, ${breakdown.terreForetPercent}% terre/forêt."
                    }
                    state.copy(gpxTrack = updatedTrack, statusBannerMessage = banner)
                } else state
            }
        }
    }

    fun updateCustomSurfaceBreakdown(bitumePct: Double, cheminPct: Double, terrePct: Double) {
        val currentTrack = _formState.value.gpxTrack ?: return
        val customBreakdown = GpxAndOverpassAnalyzer.buildCustomSurfaceBreakdown(
            track = currentTrack,
            bitumePct = bitumePct,
            cheminPct = cheminPct,
            terrePct = terrePct
        )
        val updatedTrack = currentTrack.copy(
            isAnalyzingSurface = false,
            surfaceBreakdown = customBreakdown
        )
        _formState.update { state ->
            state.copy(
                gpxTrack = updatedTrack,
                statusBannerMessage = "Revêtement personnalisé : ${customBreakdown.bitumePercent}% bitume, ${customBreakdown.cheminStabilisePercent}% stabilisé, ${customBreakdown.terreForetPercent}% terre."
            )
        }
    }

    fun clearImportedGpx() {
        _formState.update {
            it.copy(
                gpxTrack = null,
                elevationGainDPlusMeters = 0,
                manualShoeOverrideId = null,
                statusBannerMessage = "Trace GPX retirée : retour au fonctionnement classique."
            )
        }
    }

    fun updateDurationMinutes(minutes: Int) {
        val safeMin = minutes.coerceIn(30, 360)
        _formState.update { it.copy(durationMinutes = safeMin) }
        recalculateWeatherSlot()
    }

    fun updateSessionType(sessionType: RunSessionType) {
        _formState.update { it.copy(sessionType = sessionType) }
        recalculateWeatherSlot()
    }

    fun updateStartTime(dayOffset: Int, hour: Int, minute: Int = 0) {
        _formState.update {
            it.copy(
                dayOffset = dayOffset.coerceIn(0, 6),
                startHour = hour.coerceIn(0, 23),
                startMinute = minute.coerceIn(0, 59)
            )
        }
        _retroConfig.update {
            it.copy(raceStartHour = hour.coerceIn(0, 23), raceStartMinute = minute.coerceIn(0, 59))
        }
        refreshWeatherFromOpenMeteo()
    }

    fun selectManualShoe(shoeId: Long?) {
        _formState.update { it.copy(manualShoeOverrideId = shoeId) }
    }

    /**
     * Remise à zéro de la check-list avant départ et actualisation météo pour une nouvelle sortie.
     */
    fun startNewRunPreparation() {
        viewModelScope.launch {
            dao.resetAllChecklists()
            _formState.update {
                it.copy(
                    manualShoeOverrideId = null,
                    statusBannerMessage = "Nouvelle sortie initialisée : check-list avant départ remise à zéro."
                )
            }
            refreshWeatherFromOpenMeteo()
        }
    }

    fun clearBannerMessage() {
        _formState.update { it.copy(statusBannerMessage = null) }
    }

    fun updateCitySearchQuery(query: String) {
        _formState.update { it.copy(citySearchQuery = query) }
        if (query.trim().length < 2) {
            _formState.update { it.copy(citySearchResults = emptyList(), isSearchingCity = false) }
            return
        }
        viewModelScope.launch {
            _formState.update { it.copy(isSearchingCity = true) }
            try {
                val results = withContext(Dispatchers.IO) {
                    OpenMeteoClient.searchLocations(query)
                }
                _formState.update { it.copy(citySearchResults = results, isSearchingCity = false) }
            } catch (_: Exception) {
                _formState.update { it.copy(isSearchingCity = false) }
            }
        }
    }

    fun selectGeoLocation(geo: GeoLocationResult) {
        _formState.update { state ->
            val updatedRecents = (listOf(geo) + state.recentLocations.filterNot {
                it.name.equals(geo.name, ignoreCase = true)
            }).take(4)
            state.copy(
                locationName = geo.name,
                latitude = geo.latitude,
                longitude = geo.longitude,
                citySearchQuery = "",
                citySearchResults = emptyList(),
                recentLocations = updatedRecents
            )
        }
        refreshWeatherFromOpenMeteo()
    }

    fun updateThermalProfile(profile: ThermalProfile) {
        prefs.edit().putString("thermal_profile", profile.name).apply()
        _formState.update { it.copy(thermalProfile = profile) }
    }

    fun updateWaterRefillCount(count: Int) {
        _formState.update { it.copy(waterRefillCount = count.coerceIn(0, 5)) }
    }

    fun applyBestWeatherSlot(slot: BestWeatherSlotSuggestion) {
        updateStartTime(dayOffset = slot.dayOffset, hour = slot.startHour, minute = 0)
        _formState.update {
            it.copy(
                statusBannerMessage = "Créneau optimal appliqué : ${slot.dayLabel} à ${String.format("%02dh00", slot.startHour)} (${slot.avgTempC}°C • ${slot.weatherConditionLabel})."
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun detectGpsLocation() {
        val context = getApplication<Application>()
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        _formState.update { it.copy(isLoadingWeather = true, weatherError = null) }

        val tokenSource = CancellationTokenSource()
        fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    viewModelScope.launch {
                        val cityName = resolveCityName(context, location.latitude, location.longitude)
                        val geo = GeoLocationResult(cityName, "", null, location.latitude, location.longitude)
                        _formState.update { state ->
                            val updatedRecents = (listOf(geo) + state.recentLocations.filterNot {
                                it.name.equals(cityName, ignoreCase = true)
                            }).take(4)
                            state.copy(
                                locationName = cityName,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                recentLocations = updatedRecents
                            )
                        }
                        refreshWeatherFromOpenMeteo()
                    }
                } else {
                    _formState.update {
                        it.copy(
                            isLoadingWeather = false,
                            statusBannerMessage = "Position GPS indisponible dans l'émulateur : utilisation du lieu sélectionné (${it.locationName})."
                        )
                    }
                    refreshWeatherFromOpenMeteo()
                }
            }
            .addOnFailureListener {
                _formState.update {
                    it.copy(
                        isLoadingWeather = false,
                        statusBannerMessage = "Impossible d'obtenir le signal GPS : recherche par ville disponible."
                    )
                }
                refreshWeatherFromOpenMeteo()
            }
    }

    private suspend fun resolveCityName(context: Context, lat: Double, lon: Double): String =
        withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val addresses = Geocoder(context, Locale.FRENCH).getFromLocation(lat, lon, 1)
                val locality = addresses?.firstOrNull()?.locality
                    ?: addresses?.firstOrNull()?.subAdminArea
                locality ?: String.format(Locale.US, "GPS (%.2f, %.2f)", lat, lon)
            } catch (_: Exception) {
                String.format(Locale.US, "GPS (%.2f, %.2f)", lat, lon)
            }
        }

    private var cachedForecastResponse: com.example.data.remote.OpenMeteoForecastResponse? = null
    private var lastFetchedTimeLabel: String = ""

    fun refreshWeatherFromOpenMeteo() {
        val current = _formState.value
        viewModelScope.launch {
            _formState.update { it.copy(isLoadingWeather = true, weatherError = null) }
            try {
                val resp = withContext(Dispatchers.IO) {
                    OpenMeteoClient.fetchForecastWithRetry(
                        latitude = current.latitude,
                        longitude = current.longitude,
                        maxAttempts = 2
                    )
                }
                val nowTimeLabel = SimpleDateFormat("HH:mm", Locale.FRENCH).format(Date())
                cachedForecastResponse = resp
                lastFetchedTimeLabel = nowTimeLabel

                // Sauvegarde dans le cache local hors-ligne
                withContext(Dispatchers.IO) {
                    val json = OpenMeteoClient.serializeForecastToJson(resp)
                    if (json != null) {
                        prefs.edit()
                            .putString("cached_weather_json", json)
                            .putString("cached_weather_time", nowTimeLabel)
                            .putString("cached_weather_city", current.locationName)
                            .apply()
                    }
                }

                val analysis = OpenMeteoClient.analyzeSlotFromResponse(
                    response = resp,
                    locationName = current.locationName,
                    startHourOfDay = current.startHour,
                    dayOffsetFromToday = current.dayOffset,
                    durationMinutes = current.durationMinutes,
                    sessionType = current.sessionType,
                    minTrackEleMeters = current.gpxTrack?.minElevationMeters,
                    maxTrackEleMeters = current.gpxTrack?.maxElevationMeters,
                    fetchedAtTimeLabel = nowTimeLabel,
                    isFromOfflineCache = false
                )
                val topSlots = OpenMeteoClient.findBestWeatherSlots48h(
                    response = resp,
                    durationMinutes = current.durationMinutes,
                    sessionType = current.sessionType
                )
                _weatherAnalysis.value = analysis
                _formState.update {
                    it.copy(
                        isLoadingWeather = false,
                        weatherError = null,
                        bestSlots48h = topSlots
                    )
                }
            } catch (e: Exception) {
                // Tentative de restauration depuis le cache local hors-ligne avant le fallback statique
                val cachedJson = prefs.getString("cached_weather_json", null)
                val cachedTime = prefs.getString("cached_weather_time", "") ?: ""
                val cachedResp = withContext(Dispatchers.Default) {
                    OpenMeteoClient.deserializeForecastFromJson(cachedJson)
                }
                if (cachedResp != null) {
                    cachedForecastResponse = cachedResp
                    lastFetchedTimeLabel = cachedTime
                    val offlineAnalysis = OpenMeteoClient.analyzeSlotFromResponse(
                        response = cachedResp,
                        locationName = current.locationName,
                        startHourOfDay = current.startHour,
                        dayOffsetFromToday = current.dayOffset,
                        durationMinutes = current.durationMinutes,
                        sessionType = current.sessionType,
                        minTrackEleMeters = current.gpxTrack?.minElevationMeters,
                        maxTrackEleMeters = current.gpxTrack?.maxElevationMeters,
                        fetchedAtTimeLabel = cachedTime,
                        isFromOfflineCache = true
                    )
                    val topSlots = OpenMeteoClient.findBestWeatherSlots48h(
                        response = cachedResp,
                        durationMinutes = current.durationMinutes,
                        sessionType = current.sessionType
                    )
                    _weatherAnalysis.value = offlineAnalysis
                    _formState.update {
                        it.copy(
                            isLoadingWeather = false,
                            weatherError = "Mode hors-ligne : prévisions Open-Meteo restaurées depuis le cache ($cachedTime).",
                            bestSlots48h = topSlots
                        )
                    }
                } else {
                    val fallback = OpenMeteoClient.fallbackAnalysis(
                        locationName = current.locationName,
                        latitude = current.latitude,
                        longitude = current.longitude,
                        startHourOfDay = current.startHour,
                        durationMinutes = current.durationMinutes,
                        sessionType = current.sessionType
                    )
                    _weatherAnalysis.value = fallback
                    _formState.update {
                        it.copy(
                            isLoadingWeather = false,
                            weatherError = "Mode hors-ligne (Open-Meteo injoignable) : estimation locale activée."
                        )
                    }
                }
            }
        }
    }

    private fun recalculateWeatherSlot() {
        val resp = cachedForecastResponse
        val current = _formState.value
        if (resp != null) {
            _weatherAnalysis.value = OpenMeteoClient.analyzeSlotFromResponse(
                response = resp,
                locationName = current.locationName,
                startHourOfDay = current.startHour,
                dayOffsetFromToday = current.dayOffset,
                durationMinutes = current.durationMinutes,
                sessionType = current.sessionType,
                minTrackEleMeters = current.gpxTrack?.minElevationMeters,
                maxTrackEleMeters = current.gpxTrack?.maxElevationMeters,
                fetchedAtTimeLabel = lastFetchedTimeLabel,
                isFromOfflineCache = _weatherAnalysis.value.isFromOfflineCache
            )
            val topSlots = OpenMeteoClient.findBestWeatherSlots48h(
                response = resp,
                durationMinutes = current.durationMinutes,
                sessionType = current.sessionType
            )
            _formState.update { it.copy(bestSlots48h = topSlots) }
        } else {
            val prev = _weatherAnalysis.value
            val newRef = OpenMeteoClient.computeReferenceTemp(
                realTemp = prev.realTempC,
                windKmh = prev.maxWindKmh,
                windGustsKmh = prev.maxWindGustsKmh,
                slotRainMm = prev.slotRainMm,
                humidityPercent = prev.avgHumidityPercent,
                maxUv = prev.maxUvIndex,
                sessionType = current.sessionType
            )
            _weatherAnalysis.value = prev.copy(
                durationMinutes = current.durationMinutes,
                referenceTempC = (newRef * 10.0).roundToInt() / 10.0
            )
        }
    }

    /**
     * Permet de simuler/ajuster facilement la météo ou le cumul de pluie 72h si l'utilisateur
     * souhaite tester différents scénarios (sec, humide, boueux, froid < 10°C, UV fort).
     */
    fun applyWeatherScenarioOverride(
        realTempC: Double,
        past72hRainMm: Double,
        slotRainMm: Double,
        maxUvIndex: Double,
        windKmh: Double
    ) {
        val current = _formState.value
        val ref = OpenMeteoClient.computeReferenceTemp(
            realTemp = realTempC,
            windKmh = windKmh,
            slotRainMm = slotRainMm,
            maxUv = maxUvIndex,
            sessionType = current.sessionType
        )
        val simulatedRainProb = when {
            slotRainMm >= 2.0 -> 85
            slotRainMm >= 0.4 -> 55
            slotRainMm > 0.0 -> 40
            else -> 10
        }
        _weatherAnalysis.update {
            it.copy(
                realTempC = realTempC,
                apparentTempC = realTempC - 1.0,
                referenceTempC = (ref * 10.0).roundToInt() / 10.0,
                past72hRainMm = past72hRainMm,
                slotRainMm = slotRainMm,
                maxRainProbabilityPercent = simulatedRainProb,
                maxUvIndex = maxUvIndex,
                maxWindKmh = windKmh
            )
        }
    }

    // --- Checklists ---

    fun toggleChecklistItem(item: ChecklistItemEntity) {
        if (item.id < 0) return // Élément automatique (ex: veste chaude < 10°C)
        viewModelScope.launch {
            dao.updateChecklistItem(item.copy(checked = !item.checked))
        }
    }

    fun addChecklistItem(type: ChecklistType, label: String) {
        val clean = label.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            dao.insertChecklistItem(ChecklistItemEntity(listType = type, label = clean, checked = false))
        }
    }

    fun deleteChecklistItem(item: ChecklistItemEntity) {
        if (item.id < 0) return
        viewModelScope.launch {
            dao.deleteChecklistItem(item)
        }
    }

    fun resetBeforeStartChecklist() {
        viewModelScope.launch {
            dao.resetChecklist(ChecklistType.BEFORE_START)
            _formState.update { it.copy(statusBannerMessage = "Check-list avant départ remise à zéro.") }
        }
    }

    // --- Retroplanning & Notifications ---

    fun updateRetroplanningConfig(
        raceStartHour: Int = _retroConfig.value.raceStartHour,
        raceStartMinute: Int = _retroConfig.value.raceStartMinute,
        travelMinutes: Int = _retroConfig.value.travelMinutes,
        onSiteMarginMinutes: Int = _retroConfig.value.onSiteMarginMinutes,
        breakfastBeforeStartMinutes: Int = _retroConfig.value.breakfastBeforeStartMinutes,
        wakeUpBeforeBreakfastMinutes: Int = _retroConfig.value.wakeUpBeforeBreakfastMinutes
    ) {
        val cleanHour = raceStartHour.coerceIn(0, 23)
        val cleanMin = raceStartMinute.coerceIn(0, 59)
        _retroConfig.value = RetroplanningConfig(
            raceStartHour = cleanHour,
            raceStartMinute = cleanMin,
            travelMinutes = travelMinutes.coerceIn(0, 600),
            onSiteMarginMinutes = onSiteMarginMinutes.coerceIn(5, 240),
            breakfastBeforeStartMinutes = breakfastBeforeStartMinutes.coerceIn(30, 360),
            wakeUpBeforeBreakfastMinutes = wakeUpBeforeBreakfastMinutes.coerceIn(5, 180)
        )
        if (_formState.value.startHour != cleanHour || _formState.value.startMinute != cleanMin) {
            _formState.update {
                it.copy(startHour = cleanHour, startMinute = cleanMin)
            }
            recalculateWeatherSlot()
        }
    }

    fun scheduleRaceNotifications() {
        val rec = recommendation.value ?: return
        val count = RetroplanningNotificationReceiver.scheduleRetroplanningNotifications(
            context = getApplication(),
            steps = rec.retroplanningSteps,
            dayOffsetFromToday = _formState.value.dayOffset
        )
        _formState.update {
            it.copy(statusBannerMessage = "$count rappels programmés pour les étapes du rétroplanning !")
        }
    }

    // --- Retour Après Sortie (Validation, Chaussures Km + Nutrition ±5 g/h + Historique) ---

    fun submitPostRunFeedback(
        actualDistanceKm: Double,
        actualDPlusMeters: Int,
        thermalFeeling: ThermalFeeling,
        gelFeedback: GelFeedback,
        gelsActuallyCarried: Int,
        customOutfitText: String,
        notes: String
    ) {
        val rec = recommendation.value ?: return
        val form = _formState.value
        val validDistance = actualDistanceKm.coerceAtLeast(0.1)
        val validDPlus = actualDPlusMeters.coerceAtLeast(0)

        viewModelScope.launch {
            // 1. Ajouter les km réels à la paire de chaussures utilisée
            val usedShoe = rec.selectedShoe
            if (usedShoe != null) {
                dao.addKmToShoe(usedShoe.id, validDistance)
            }

            // 2. Ajuster le taux de glucides/h de ±5 g/h (borné entre 30 et 90 g/h)
            val currentNut = _nutritionSettings.value
            val delta = gelFeedback.deltaCarbPerHour
            val updatedNut = if (form.durationMinutes > 150) {
                currentNut.copy(carbRateLong = (currentNut.carbRateLong + delta).coerceIn(30, 90))
            } else {
                currentNut.copy(carbRateMedium = (currentNut.carbRateMedium + delta).coerceIn(30, 90))
            }
            saveNutritionSettings(updatedNut)

            // 3. Enregistrer la sortie dans l'Historique
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, form.dayOffset)
            }
            val gpxSummaryText = form.gpxTrack?.surfaceBreakdown?.let { b ->
                if (b.overpassError != null) "GPX (${form.gpxTrack.trackName}) : ${b.overpassError}"
                else "GPX ${form.gpxTrack.trackName} : ${b.bitumePercent}% bitume, ${b.cheminStabilisePercent}% stabilisé, ${b.terreForetPercent}% terre/forêt, ${b.inconnuPercent}% inconnu"
            } ?: ""

            val entry = RunHistoryEntity(
                dateEpochMillis = cal.timeInMillis,
                dateFormatted = formatDate(cal.timeInMillis),
                locationName = form.locationName,
                sessionType = form.sessionType,
                terrain = form.terrain,
                plannedDistanceKm = form.plannedDistanceKm,
                actualDistanceKm = validDistance,
                elevationGainDPlusMeters = validDPlus,
                gpxSurfaceSummary = gpxSummaryText,
                durationMinutes = form.durationMinutes,
                realTempC = rec.weather.realTempC,
                referenceTempC = rec.weather.referenceTempC,
                windKmh = rec.weather.maxWindKmh,
                rainMm = rec.weather.slotRainMm,
                rain72hMm = rec.weather.past72hRainMm,
                mudLevel = rec.mudAlert.mudLevel,
                maxUvIndex = rec.weather.maxUvIndex,
                outfitWorn = customOutfitText.ifBlank { (rec.outfitItems + rec.accessoriesItems).joinToString(", ") },
                shoeId = usedShoe?.id,
                shoeName = usedShoe?.name ?: "Non renseignée",
                shoeType = usedShoe?.type ?: ShoeType.ROUTE,
                gelsCarried = gelsActuallyCarried,
                carbRateUsed = rec.nutrition.targetCarbPerHour,
                thermalFeeling = thermalFeeling,
                gelFeedback = gelFeedback,
                notes = notes.trim()
            )
            dao.insertRunHistory(entry)

            // 4. Remettre à zéro la check-list avant départ pour la prochaine sortie
            dao.resetChecklist(ChecklistType.BEFORE_START)

            val rateMsg = if (delta != 0) {
                val newVal = if (form.durationMinutes > 150) updatedNut.carbRateLong else updatedNut.carbRateMedium
                " • Glucides ajustés à $newVal g/h"
            } else ""
            val shoeMsg = if (usedShoe != null) " • +${validDistance} km sur ${usedShoe.name}" else ""

            _formState.update {
                it.copy(
                    statusBannerMessage = "Sortie enregistrée dans l'historique$shoeMsg$rateMsg !"
                )
            }
        }
    }

    // --- Mon Matos & Chaussures Actions ---

    fun addOrUpdateShoe(
        id: Long = 0,
        name: String,
        type: ShoeType,
        currentKm: Double,
        maxKm: Double = 700.0
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        viewModelScope.launch {
            val shoe = ShoeEntity(
                id = id,
                name = cleanName,
                type = type,
                currentKm = currentKm.coerceAtLeast(0.0),
                maxKm = maxKm.coerceAtLeast(100.0)
            )
            dao.insertShoe(shoe)
        }
    }

    fun deleteShoe(shoe: ShoeEntity) {
        viewModelScope.launch {
            dao.deleteShoe(shoe)
            _formState.update { state ->
                val newOverride = if (state.manualShoeOverrideId == shoe.id) null else state.manualShoeOverrideId
                state.copy(
                    manualShoeOverrideId = newOverride,
                    statusBannerMessage = "Chaussure « ${shoe.name} » supprimée de Mon matos."
                )
            }
        }
    }

    fun toggleGearOwned(item: GearItemEntity) {
        viewModelScope.launch {
            dao.upsertGearItem(item.copy(owned = !item.owned))
        }
    }

    fun addCustomGearItem(name: String, category: GearCategory) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val code = "custom_" + clean.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "_") + "_" + (System.currentTimeMillis() % 10000)
        viewModelScope.launch {
            dao.upsertGearItem(
                GearItemEntity(
                    code = code,
                    name = clean,
                    category = category,
                    owned = true
                )
            )
        }
    }

    fun updateNutritionRatesManually(
        carbMedium: Int,
        carbLong: Int,
        gelGrams: Int,
        asphaltThresholdPct: Int = _nutritionSettings.value.asphaltThresholdPercent
    ) {
        saveNutritionSettings(
            NutritionSettingsState(
                carbRateMedium = carbMedium.coerceIn(30, 90),
                carbRateLong = carbLong.coerceIn(30, 90),
                gelCarbsGrams = gelGrams.coerceIn(15, 50),
                asphaltThresholdPercent = asphaltThresholdPct.coerceIn(20, 95)
            )
        )
    }

    fun deleteHistoryEntry(entry: RunHistoryEntity) {
        viewModelScope.launch {
            dao.deleteRunHistory(entry)
        }
    }

    // --- Ajout d'une Sortie Passée avec Météo Historique Open-Meteo ---

    fun initPastRunDialog() {
        val currentForm = _formState.value
        val yesterdayMillis = System.currentTimeMillis() - 24L * 3600L * 1000L
        _pastRunDraft.update {
            it.copy(
                dateEpochMillis = yesterdayMillis,
                dateFormatted = formatDate(yesterdayMillis),
                locationName = currentForm.locationName,
                latitude = currentForm.latitude,
                longitude = currentForm.longitude,
                terrain = currentForm.terrain,
                sessionType = currentForm.sessionType,
                citySearchQuery = "",
                citySearchResults = emptyList()
            )
        }
        refreshPastRunHistoricalWeather()
    }

    fun updatePastRunParams(
        dateEpochMillis: Long = _pastRunDraft.value.dateEpochMillis,
        startHour: Int = _pastRunDraft.value.startHour,
        durationMinutes: Int = _pastRunDraft.value.durationMinutes,
        terrain: TerrainType = _pastRunDraft.value.terrain,
        sessionType: RunSessionType = _pastRunDraft.value.sessionType
    ) {
        val safeDate = dateEpochMillis.coerceAtMost(System.currentTimeMillis())
        _pastRunDraft.update {
            it.copy(
                dateEpochMillis = safeDate,
                dateFormatted = formatDate(safeDate),
                startHour = startHour.coerceIn(0, 23),
                durationMinutes = durationMinutes.coerceIn(30, 360),
                terrain = terrain,
                sessionType = sessionType
            )
        }
        refreshPastRunHistoricalWeather()
    }

    fun updatePastRunCitySearch(query: String) {
        _pastRunDraft.update { it.copy(citySearchQuery = query) }
        if (query.trim().length < 2) {
            _pastRunDraft.update { it.copy(citySearchResults = emptyList(), isSearchingCity = false) }
            return
        }
        viewModelScope.launch {
            _pastRunDraft.update { it.copy(isSearchingCity = true) }
            try {
                val results = withContext(Dispatchers.IO) {
                    OpenMeteoClient.searchLocations(query)
                }
                _pastRunDraft.update { it.copy(citySearchResults = results, isSearchingCity = false) }
            } catch (_: Exception) {
                _pastRunDraft.update { it.copy(isSearchingCity = false) }
            }
        }
    }

    fun selectPastRunGeoLocation(geo: GeoLocationResult) {
        _pastRunDraft.update {
            it.copy(
                locationName = geo.name,
                latitude = geo.latitude,
                longitude = geo.longitude,
                citySearchQuery = "",
                citySearchResults = emptyList()
            )
        }
        refreshPastRunHistoricalWeather()
    }

    fun refreshPastRunHistoricalWeather() {
        val draft = _pastRunDraft.value
        viewModelScope.launch {
            _pastRunDraft.update {
                it.copy(
                    isLoadingWeather = true,
                    weatherStatusMessage = "Récupération de la météo du ${draft.dateFormatted} à ${draft.locationName}…"
                )
            }
            try {
                val weather = withContext(Dispatchers.IO) {
                    OpenMeteoClient.fetchHistoricalSlotWeather(
                        locationName = draft.locationName,
                        latitude = draft.latitude,
                        longitude = draft.longitude,
                        targetDateMillis = draft.dateEpochMillis,
                        startHourOfDay = draft.startHour,
                        durationMinutes = draft.durationMinutes,
                        sessionType = draft.sessionType
                    )
                }
                val ownedSet = gearItems.value.filter { it.owned }.map { it.code }.toSet()
                val mudAlert = RecommendationEngine.computeMudAlert(draft.terrain, weather.past72hRainMm, ownedSet)
                val uvAlert = RecommendationEngine.computeUvAlert(weather.maxUvIndex)
                val nutSettings = _nutritionSettings.value
                val nutrition = RecommendationEngine.computeNutrition(
                    durationMinutes = draft.durationMinutes,
                    carbRateMedium = nutSettings.carbRateMedium,
                    carbRateLong = nutSettings.carbRateLong,
                    gelCarbsGrams = nutSettings.gelCarbsGrams,
                    realTempC = weather.realTempC
                )
                val (outfit, accessories) = RecommendationEngine.buildOutfitAndAccessories(
                    weather = weather,
                    terrain = draft.terrain,
                    mudAlert = mudAlert,
                    uvAlert = uvAlert,
                    nutrition = nutrition,
                    gearItems = gearItems.value
                )
                val outfitSummary = (outfit + accessories).joinToString(", ")

                _pastRunDraft.update {
                    it.copy(
                        isLoadingWeather = false,
                        weather = weather,
                        suggestedOutfit = outfitSummary,
                        weatherStatusMessage = "Météo historique Open-Meteo récupérée (${draft.dateFormatted} • ${draft.startHour}h00)"
                    )
                }
            } catch (_: Exception) {
                val fallback = OpenMeteoClient.fallbackAnalysis(
                    locationName = draft.locationName,
                    latitude = draft.latitude,
                    longitude = draft.longitude,
                    startHourOfDay = draft.startHour,
                    durationMinutes = draft.durationMinutes,
                    sessionType = draft.sessionType
                )
                _pastRunDraft.update {
                    it.copy(
                        isLoadingWeather = false,
                        weather = fallback,
                        weatherStatusMessage = "Open-Meteo archive hors-ligne : valeurs météo estimées."
                    )
                }
            }
        }
    }

    fun savePastRunEntry(
        distanceKm: Double,
        dPlusMeters: Int,
        shoeId: Long?,
        addKmToShoe: Boolean,
        outfitWorn: String,
        gelsCarried: Int,
        thermalFeeling: ThermalFeeling,
        gelFeedback: GelFeedback,
        adjustCarbRate: Boolean,
        notes: String
    ) {
        val draft = _pastRunDraft.value
        val validDist = distanceKm.coerceAtLeast(0.1)
        val selectedShoe = shoes.value.find { it.id == shoeId } ?: shoes.value.firstOrNull()

        viewModelScope.launch {
            if (addKmToShoe && selectedShoe != null) {
                dao.addKmToShoe(selectedShoe.id, validDist)
            }

            val currentNut = _nutritionSettings.value
            val activeRate = if (draft.durationMinutes > 150) currentNut.carbRateLong else currentNut.carbRateMedium
            if (adjustCarbRate && gelFeedback.deltaCarbPerHour != 0) {
                val delta = gelFeedback.deltaCarbPerHour
                val updatedNut = if (draft.durationMinutes > 150) {
                    currentNut.copy(carbRateLong = (currentNut.carbRateLong + delta).coerceIn(30, 90))
                } else {
                    currentNut.copy(carbRateMedium = (currentNut.carbRateMedium + delta).coerceIn(30, 90))
                }
                saveNutritionSettings(updatedNut)
            }

            val ownedSet = gearItems.value.filter { it.owned }.map { it.code }.toSet()
            val mudAlert = RecommendationEngine.computeMudAlert(
                terrain = draft.terrain,
                rain72hMm = draft.weather.past72hRainMm,
                ownedGearCodes = ownedSet
            )

            val entry = RunHistoryEntity(
                dateEpochMillis = draft.dateEpochMillis,
                dateFormatted = draft.dateFormatted.ifBlank { formatDate(draft.dateEpochMillis) },
                locationName = draft.locationName,
                sessionType = draft.sessionType,
                terrain = draft.terrain,
                plannedDistanceKm = validDist,
                actualDistanceKm = validDist,
                elevationGainDPlusMeters = dPlusMeters.coerceAtLeast(0),
                gpxSurfaceSummary = "",
                durationMinutes = draft.durationMinutes,
                realTempC = draft.weather.realTempC,
                referenceTempC = draft.weather.referenceTempC,
                windKmh = draft.weather.maxWindKmh,
                rainMm = draft.weather.slotRainMm,
                rain72hMm = draft.weather.past72hRainMm,
                mudLevel = mudAlert.mudLevel,
                maxUvIndex = draft.weather.maxUvIndex,
                outfitWorn = outfitWorn.ifBlank { draft.suggestedOutfit },
                shoeId = selectedShoe?.id,
                shoeName = selectedShoe?.name ?: "Non renseignée",
                shoeType = selectedShoe?.type ?: ShoeType.ROUTE,
                gelsCarried = gelsCarried.coerceAtLeast(0),
                carbRateUsed = activeRate,
                thermalFeeling = thermalFeeling,
                gelFeedback = gelFeedback,
                notes = notes.trim()
            )
            dao.insertRunHistory(entry)

            _formState.update {
                it.copy(
                    statusBannerMessage = "Sortie passée du ${entry.dateFormatted} à ${entry.locationName} enregistrée dans l'historique !"
                )
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
