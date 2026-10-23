package com.example.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Geocoder
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.data.local.BackupManager
import com.example.data.local.ChecklistItemEntity
import com.example.data.local.DemoDataCleanup
import com.example.data.local.GearItemEntity
import com.example.data.local.RunCoachDatabase
import com.example.data.local.RunHistoryEntity
import com.example.data.local.ShoeEntity
import com.example.data.model.BestWeatherSlotSuggestion
import com.example.data.model.ChecklistType
import com.example.data.model.GearCategory
import com.example.data.model.GelFeedback
import com.example.data.model.GeoLocationResult
import com.example.data.model.GpxTrackAnalysis
import com.example.data.model.RetroplanningConfig
import com.example.data.model.RunSessionType
import com.example.data.model.ShoeType
import com.example.data.model.TerrainType
import com.example.data.model.ThermalFeeling
import com.example.data.model.ThermalProfile
import com.example.data.model.WeatherSlotAnalysis
import com.example.data.remote.GpxAndOverpassAnalyzer
import com.example.data.remote.OpenMeteoClient
import com.example.data.remote.OpenMeteoForecastResponse
import com.example.data.remote.WeatherCachePolicy
import com.example.domain.FullRunRecommendation
import com.example.domain.RecommendationEngine
import com.example.widget.NextRunWidgetProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class NewRunFormState(
    /** Faux tant qu'aucun lieu n'a été choisi (ville, GPS ou trace GPX) : pas de météo, pas de reco. */
    val hasLocation: Boolean = false,
    val locationName: String = "Aucun lieu choisi",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
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
    val recentLocations: List<GeoLocationResult> = emptyList(),
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
    val locationName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val hasLocation: Boolean = false,
    val citySearchQuery: String = "",
    val citySearchResults: List<GeoLocationResult> = emptyList(),
    val isSearchingCity: Boolean = false,
    val terrain: TerrainType = TerrainType.TRAIL,
    val sessionType: RunSessionType = RunSessionType.ENDURANCE,
    val isLoadingWeather: Boolean = false,
    val weatherStatusMessage: String? = null,
    /** Null tant que la vraie météo historique n'a pas été récupérée : l'enregistrement est alors bloqué. */
    val weather: WeatherSlotAnalysis? = null,
    val suggestedOutfit: String = ""
)

/** Prévision Open-Meteo mémorisée avec le lieu et l'heure de récupération. */
private data class CachedForecast(
    val response: OpenMeteoForecastResponse,
    val latitude: Double,
    val longitude: Double,
    val fetchedAtMillis: Long,
    val fetchedAtLabel: String,
    val isFromOfflineStore: Boolean
)

@OptIn(FlowPreview::class)
class RunCoachViewModel(application: Application) : AndroidViewModel(application) {

    private val db = RunCoachDatabase.getInstance(application)
    private val dao = db.dao()
    private val prefs = application.getSharedPreferences("runcoach_nutrition_prefs", Context.MODE_PRIVATE)

    private val _formState = MutableStateFlow(NewRunFormState())
    val formState: StateFlow<NewRunFormState> = _formState.asStateFlow()

    private val _weatherAnalysis = MutableStateFlow<WeatherSlotAnalysis?>(null)
    val weatherAnalysis: StateFlow<WeatherSlotAnalysis?> = _weatherAnalysis.asStateFlow()

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

    /** Null tant qu'il n'y a pas de vraie météo pour le créneau : aucune recommandation sur des valeurs inventées. */
    val recommendation: StateFlow<FullRunRecommendation?> = combine(
        _formState,
        _weatherAnalysis,
        shoes,
        gearItems,
        combine(postRaceChecklist, runHistory, _nutritionSettings, _retroConfig) { post, hist, nut, retro ->
            Quadruple(post, hist, nut, retro)
        }
    ) { form, weather, shoeList, gearList, quad ->
        if (weather == null || !form.hasLocation) null else buildRecommendation(form, weather, shoeList, gearList, quad)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun buildRecommendation(
        form: NewRunFormState,
        weather: WeatherSlotAnalysis,
        shoeList: List<ShoeEntity>,
        gearList: List<GearItemEntity>,
        quad: Quadruple<List<ChecklistItemEntity>, List<RunHistoryEntity>, NutritionSettingsState, RetroplanningConfig>
    ): FullRunRecommendation {
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

        return if (form.manualShoeOverrideId != null) {
            val manualShoe = shoeList.find { it.id == form.manualShoeOverrideId }
            if (manualShoe != null) {
                baseRec.copy(
                    selectedShoe = manualShoe,
                    shoeSelectionReason = "Sélection manuelle (${manualShoe.type.label} • ${manualShoe.currentKm.roundToInt()}/${manualShoe.maxKm.roundToInt()} km)"
                )
            } else baseRec
        } else baseRec
    }

    private var cachedForecast: CachedForecast? = null
    private var weatherJob: Job? = null
    private var pastWeatherJob: Job? = null

    init {
        val savedProfileName = prefs.getString(KEY_THERMAL_PROFILE, ThermalProfile.NEUTRE.name) ?: ThermalProfile.NEUTRE.name
        val savedProfile = runCatching { ThermalProfile.valueOf(savedProfileName) }.getOrDefault(ThermalProfile.NEUTRE)
        _formState.update { it.copy(thermalProfile = savedProfile) }
        restoreSavedLocations()

        viewModelScope.launch {
            seedAndCleanUpData()
            refreshWeatherFromOpenMeteo()
        }

        // Widget d'accueil : mis à jour avec la recommandation, sans écrire à chaque frappe.
        viewModelScope.launch {
            recommendation.filterNotNull().debounce(800L).collect { pushWidgetUpdate(it) }
        }
    }

    private fun loadNutritionSettings(): NutritionSettingsState {
        return NutritionSettingsState(
            carbRateMedium = prefs.getInt(KEY_CARB_MEDIUM, 45).coerceIn(30, 90),
            carbRateLong = prefs.getInt(KEY_CARB_LONG, 75).coerceIn(30, 90),
            gelCarbsGrams = prefs.getInt(KEY_GEL_GRAMS, 25).coerceIn(15, 50),
            asphaltThresholdPercent = prefs.getInt(KEY_ASPHALT_PCT, 60).coerceIn(20, 95)
        )
    }

    private fun saveNutritionSettings(state: NutritionSettingsState) {
        prefs.edit()
            .putInt(KEY_CARB_MEDIUM, state.carbRateMedium.coerceIn(30, 90))
            .putInt(KEY_CARB_LONG, state.carbRateLong.coerceIn(30, 90))
            .putInt(KEY_GEL_GRAMS, state.gelCarbsGrams.coerceIn(15, 50))
            .putInt(KEY_ASPHALT_PCT, state.asphaltThresholdPercent.coerceIn(20, 95))
            .apply()
        _nutritionSettings.value = state
    }

    /**
     * Crée le matos par défaut (non coché) et les check-lists de base.
     * Retire une seule fois les données démo de la v1.0.1 (paires, sorties fictives, matos tout coché)
     * si elles n'ont pas été utilisées.
     */
    private suspend fun seedAndCleanUpData() = withContext(Dispatchers.IO) {
        val existingCodes = dao.getAllGearOnce().map { it.code }.toSet()
        val defaultGear = listOf(
            GearItemEntity("debardeur", "Débardeur ultra-respirant (Chaleur > 20°C)", GearCategory.HAUTS, false),
            GearItemEntity("haut_court", "T-shirt technique manches courtes", GearCategory.HAUTS, false),
            GearItemEntity("manchettes", "Manchettes amovibles (tenue modulable)", GearCategory.HAUTS, false),
            GearItemEntity("haut_long", "Maillot manches longues thermique", GearCategory.HAUTS, false),
            GearItemEntity("veste_pluie", "Veste imperméable", GearCategory.VESTES, false),
            GearItemEntity("coupe_vent", "Coupe-vent léger déperlant", GearCategory.VESTES, false),
            GearItemEntity("short", "Short de course respirant", GearCategory.BAS, false),
            GearItemEntity("cuissard_court", "Cuissard court", GearCategory.BAS, false),
            GearItemEntity("cuissard_long", "Collant / Cuissard long hiver", GearCategory.BAS, false),
            GearItemEntity("chaussettes", "Chaussettes techniques Route & Trail", GearCategory.ACCESSOIRES_THERMIQUES, false),
            GearItemEntity("gants", "Gants fins coupe-vent", GearCategory.ACCESSOIRES_THERMIQUES, false),
            GearItemEntity("bonnet", "Bonnet / Bandeau thermique", GearCategory.ACCESSOIRES_THERMIQUES, false),
            GearItemEntity("tour_de_cou", "Tour de cou / Buff multifonction", GearCategory.ACCESSOIRES_THERMIQUES, false),
            GearItemEntity("guetres", "Guêtres stop-boue & débris", GearCategory.TRAIL_SPECIFIQUE, false),
            GearItemEntity("batons_trail", "Bâtons de trail pliables", GearCategory.TRAIL_SPECIFIQUE, false),
            GearItemEntity("frontale", "Lampe frontale rechargeable", GearCategory.TRAIL_SPECIFIQUE, false),
            GearItemEntity("lunettes_soleil", "Lunettes de soleil", GearCategory.SOLEIL, false),
            GearItemEntity("casquette", "Casquette respirante", GearCategory.SOLEIL, false),
            GearItemEntity("creme_solaire", "Crème solaire", GearCategory.SOLEIL, false),
            GearItemEntity("gilet_hydratation", "Gilet / Ceinture d'hydratation + flasques", GearCategory.HYDRATATION_NUTRITION, false),
            GearItemEntity("gels", "Gels énergétiques", GearCategory.HYDRATATION_NUTRITION, false)
        )
        val missingGear = defaultGear.filter { it.code !in existingCodes }
        if (missingGear.isNotEmpty()) {
            dao.insertGearItems(missingGear)
        }

        if (!prefs.getBoolean(KEY_DEMO_CLEANUP_DONE, false)) {
            val history = dao.getAllRunHistoryOnce()
            history.filter { DemoDataCleanup.isDemoRun(it) }.forEach { dao.deleteRunHistory(it) }
            DemoDataCleanup.unusedDemoShoes(dao.getAllShoesOnce(), history).forEach { dao.deleteShoe(it) }
            val gear = dao.getAllGearOnce()
            if (DemoDataCleanup.shouldUncheckDefaultGear(gear)) {
                gear.filter { it.code in DemoDataCleanup.defaultGearCodes }
                    .forEach { dao.upsertGearItem(it.copy(owned = false)) }
            }
            prefs.edit().putBoolean(KEY_DEMO_CLEANUP_DONE, true).apply()
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

    // --- Lieux mémorisés ---

    private fun geoToJson(geo: GeoLocationResult): JSONObject = JSONObject().apply {
        put("name", geo.name)
        put("country", geo.country)
        put("admin1", geo.admin1 ?: "")
        put("lat", geo.latitude)
        put("lon", geo.longitude)
    }

    private fun geoFromJson(o: JSONObject): GeoLocationResult? = try {
        GeoLocationResult(
            name = o.getString("name"),
            country = o.optString("country", ""),
            admin1 = o.optString("admin1", "").ifBlank { null },
            latitude = o.getDouble("lat"),
            longitude = o.getDouble("lon")
        )
    } catch (_: Exception) {
        null
    }

    private fun restoreSavedLocations() {
        val last = prefs.getString(KEY_LAST_LOCATION, null)?.let { runCatching { geoFromJson(JSONObject(it)) }.getOrNull() }
        val recents = prefs.getString(KEY_RECENT_LOCATIONS, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { geoFromJson(arr.getJSONObject(it)) }
            }.getOrNull()
        }.orEmpty()
        _formState.update { state ->
            if (last != null) {
                state.copy(
                    hasLocation = true,
                    locationName = last.name,
                    latitude = last.latitude,
                    longitude = last.longitude,
                    recentLocations = recents
                )
            } else {
                state.copy(recentLocations = recents)
            }
        }
    }

    private fun persistLocations(last: GeoLocationResult?, recents: List<GeoLocationResult>) {
        val editor = prefs.edit()
        if (last != null) editor.putString(KEY_LAST_LOCATION, geoToJson(last).toString())
        editor.putString(KEY_RECENT_LOCATIONS, JSONArray().apply { recents.forEach { put(geoToJson(it)) } }.toString())
        editor.apply()
    }

    private fun applyLocation(geo: GeoLocationResult, addToRecents: Boolean) {
        var newRecents: List<GeoLocationResult> = emptyList()
        _formState.update { state ->
            newRecents = if (addToRecents) {
                (listOf(geo) + state.recentLocations.filterNot { it.name.equals(geo.name, ignoreCase = true) }).take(4)
            } else {
                state.recentLocations
            }
            state.copy(
                hasLocation = true,
                locationName = geo.name,
                latitude = geo.latitude,
                longitude = geo.longitude,
                citySearchQuery = "",
                citySearchResults = emptyList(),
                recentLocations = newRecents
            )
        }
        persistLocations(geo, newRecents)
    }

    // --- Form & Open-Meteo Actions ---

    private fun estimateDurationForDistance(
        distanceKm: Double,
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
        val newDur = estimateDurationForDistance(
            distanceKm = _formState.value.plannedDistanceKm,
            dPlusMeters = _formState.value.elevationGainDPlusMeters,
            paceSecPerKm = _formState.value.paceSecPerKm
        )
        _formState.update {
            it.copy(
                terrain = terrain,
                durationMinutes = newDur,
                manualShoeOverrideId = null
            )
        }
        recalculateWeatherSlot()
    }

    fun updatePlannedDistance(distanceKm: Double) {
        val safeDist = if (distanceKm.isFinite()) distanceKm.coerceIn(0.1, 200.0) else 10.0
        val estimatedMinutes = estimateDurationForDistance(distanceKm = safeDist)
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
            dPlusMeters = safeDPlus
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

    /** Lit le fichier GPX choisi hors du thread principal, puis l'importe. */
    fun importGpxFromUri(uri: Uri, fileName: String) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            if (content.isNullOrBlank()) {
                _formState.update { it.copy(statusBannerMessage = "Impossible de lire ce fichier GPX.") }
            } else {
                importGpxFromXmlString(content, fileName)
            }
        }
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
                    it.copy(statusBannerMessage = "Fichier GPX invalide : aucune trace (<trkpt> ou <rtept>) exploitable.")
                }
                return@launch
            }

            val context = getApplication<Application>()
            val resolvedCity = resolveCityName(context, parsed.startLat, parsed.startLon)
            val estDur = estimateDurationForDistance(
                distanceKm = parsed.totalDistanceKm,
                dPlusMeters = parsed.elevationGainDPlusMeters
            )

            applyLocation(
                GeoLocationResult(resolvedCity, "", null, parsed.startLat, parsed.startLon),
                addToRecents = false
            )
            _formState.update {
                it.copy(
                    plannedDistanceKm = parsed.totalDistanceKm,
                    elevationGainDPlusMeters = parsed.elevationGainDPlusMeters,
                    durationMinutes = estDur,
                    gpxTrack = parsed,
                    manualShoeOverrideId = null,
                    statusBannerMessage = "Trace « ${parsed.trackName} » importée : ${parsed.totalDistanceKm} km • ${parsed.elevationGainDPlusMeters} m D+ • Analyse du revêtement…"
                )
            }

            // Actualise la météo Open-Meteo au point de départ de la trace
            refreshWeatherFromOpenMeteo()

            // Analyse du revêtement via Overpass API (ou cache si déjà analysée)
            val breakdown = withContext(Dispatchers.IO) {
                GpxAndOverpassAnalyzer.analyzeSurfaceWithOverpass(track = parsed)
            }
            applySurfaceBreakdown(parsed, breakdown)
        }
    }

    private fun surfaceBanner(track: GpxTrackAnalysis, breakdown: com.example.data.model.GpxSurfaceBreakdown): String = when {
        breakdown.overpassError != null ->
            "Trace GPX (${track.totalDistanceKm} km, ${track.elevationGainDPlusMeters} m D+) : ${breakdown.overpassError}"
        breakdown.isUnreliable ->
            "Trace GPX analysée : ${breakdown.inconnuPercent}% inconnu (> 30 %) → analyse peu fiable, règles actuelles appliquées."
        else ->
            "Revêtement GPX analysé : ${breakdown.bitumePercent}% bitume, ${breakdown.cheminStabilisePercent}% stabilisé, ${breakdown.terreForetPercent}% terre/forêt."
    }

    private fun applySurfaceBreakdown(track: GpxTrackAnalysis, breakdown: com.example.data.model.GpxSurfaceBreakdown) {
        val updatedTrack = track.copy(
            isAnalyzingSurface = false,
            surfaceBreakdown = breakdown
        )
        var applied = false
        _formState.update { state ->
            if (state.gpxTrack?.trackIdHash == track.trackIdHash) {
                applied = true
                state.copy(gpxTrack = updatedTrack, statusBannerMessage = surfaceBanner(track, breakdown))
            } else state
        }
        // La part en forêt modifie l'effet du vent : on recalcule le créneau.
        if (applied) recalculateWeatherSlot()
    }

    fun retryGpxSurfaceAnalysis() {
        val currentTrack = _formState.value.gpxTrack ?: return
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
                    forceRefresh = true
                )
            }
            applySurfaceBreakdown(currentTrack, breakdown)
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
                statusBannerMessage = "Revêtement saisi : ${customBreakdown.bitumePercent}% bitume, ${customBreakdown.cheminStabilisePercent}% stabilisé, ${customBreakdown.terreForetPercent}% terre."
            )
        }
        recalculateWeatherSlot()
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
        recalculateWeatherSlot()
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
        recalculateWeatherSlot()
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
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _formState.update { it.copy(isSearchingCity = false) }
            }
        }
    }

    fun selectGeoLocation(geo: GeoLocationResult) {
        applyLocation(geo, addToRecents = true)
        refreshWeatherFromOpenMeteo()
    }

    fun updateThermalProfile(profile: ThermalProfile) {
        prefs.edit().putString(KEY_THERMAL_PROFILE, profile.name).apply()
        _formState.update { it.copy(thermalProfile = profile) }
    }

    fun updateWaterRefillCount(count: Int) {
        _formState.update { it.copy(waterRefillCount = count.coerceIn(0, 5)) }
    }

    fun applyBestWeatherSlot(slot: BestWeatherSlotSuggestion) {
        updateStartTime(dayOffset = slot.dayOffset, hour = slot.startHour, minute = 0)
        _formState.update {
            it.copy(
                statusBannerMessage = "Créneau appliqué : ${slot.dayLabel} à ${String.format("%02dh00", slot.startHour)} (${slot.avgTempC}°C • ${slot.weatherConditionLabel})."
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
                        applyLocation(
                            GeoLocationResult(cityName, "", null, location.latitude, location.longitude),
                            addToRecents = true
                        )
                        refreshWeatherFromOpenMeteo()
                    }
                } else {
                    _formState.update {
                        it.copy(
                            isLoadingWeather = false,
                            statusBannerMessage = "Position GPS indisponible : recherche une ville."
                        )
                    }
                    refreshWeatherFromOpenMeteo()
                }
            }
            .addOnFailureListener {
                _formState.update {
                    it.copy(
                        isLoadingWeather = false,
                        statusBannerMessage = "Impossible d'obtenir le signal GPS : recherche une ville."
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

    // --- Météo : réseau, cache lié au lieu et à l'heure, jamais de valeurs inventées ---

    private fun currentForestShare(): Double = RecommendationEngine.forestShareOf(_formState.value.gpxTrack)

    /** Analyse le créneau courant à partir d'une prévision. Renvoie faux si la prévision ne couvre pas le créneau. */
    private fun applyForecast(cached: CachedForecast): Boolean {
        val current = _formState.value
        val analysis = OpenMeteoClient.analyzeSlotFromResponse(
            response = cached.response,
            locationName = current.locationName,
            startHourOfDay = current.startHour,
            dayOffsetFromToday = current.dayOffset,
            durationMinutes = current.durationMinutes,
            sessionType = current.sessionType,
            startMinute = current.startMinute,
            minTrackEleMeters = current.gpxTrack?.minElevationMeters,
            maxTrackEleMeters = current.gpxTrack?.maxElevationMeters,
            forestShare = currentForestShare(),
            fetchedAtTimeLabel = cached.fetchedAtLabel,
            isFromOfflineCache = cached.isFromOfflineStore
        )
        val topSlots = OpenMeteoClient.findBestWeatherSlots48h(
            response = cached.response,
            durationMinutes = current.durationMinutes,
            sessionType = current.sessionType,
            forestShare = currentForestShare()
        )
        _weatherAnalysis.value = analysis
        _formState.update { it.copy(bestSlots48h = topSlots) }
        return analysis != null
    }

    private fun loadStoredForecast(): CachedForecast? {
        val json = prefs.getString(KEY_CACHE_JSON, null) ?: return null
        val lat = prefs.getString(KEY_CACHE_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_CACHE_LON, null)?.toDoubleOrNull() ?: return null
        val at = prefs.getLong(KEY_CACHE_AT_MILLIS, 0L)
        val label = prefs.getString(KEY_CACHE_LABEL, "") ?: ""
        val resp = OpenMeteoClient.deserializeForecastFromJson(json) ?: return null
        return CachedForecast(resp, lat, lon, at, label, isFromOfflineStore = true)
    }

    private fun CachedForecast.isUsableFor(state: NewRunFormState): Boolean =
        state.hasLocation && WeatherCachePolicy.isUsable(latitude, longitude, fetchedAtMillis, state.latitude, state.longitude)

    fun refreshWeatherFromOpenMeteo() {
        val current = _formState.value
        if (!current.hasLocation) {
            weatherJob?.cancel()
            _weatherAnalysis.value = null
            _formState.update { it.copy(isLoadingWeather = false, weatherError = null, bestSlots48h = emptyList()) }
            return
        }
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            _formState.update { it.copy(isLoadingWeather = true, weatherError = null) }
            try {
                val resp = withContext(Dispatchers.IO) {
                    OpenMeteoClient.fetchForecastWithRetry(
                        latitude = current.latitude,
                        longitude = current.longitude,
                        maxAttempts = 2
                    )
                }
                val nowMillis = System.currentTimeMillis()
                val label = SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH).format(Date(nowMillis))
                val fresh = CachedForecast(resp, current.latitude, current.longitude, nowMillis, label, isFromOfflineStore = false)
                cachedForecast = fresh

                // Sauvegarde dans le cache local hors-ligne (avec le lieu et l'heure)
                withContext(Dispatchers.IO) {
                    val json = OpenMeteoClient.serializeForecastToJson(resp)
                    if (json != null) {
                        prefs.edit()
                            .putString(KEY_CACHE_JSON, json)
                            .putString(KEY_CACHE_LAT, current.latitude.toString())
                            .putString(KEY_CACHE_LON, current.longitude.toString())
                            .putLong(KEY_CACHE_AT_MILLIS, nowMillis)
                            .putString(KEY_CACHE_LABEL, label)
                            .apply()
                    }
                }

                val covered = applyForecast(fresh)
                _formState.update {
                    it.copy(
                        isLoadingWeather = false,
                        weatherError = if (covered) null else NO_SLOT_COVERAGE_MESSAGE
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val state = _formState.value
                val usable = listOfNotNull(cachedForecast, loadStoredForecast())
                    .filter { it.isUsableFor(state) }
                    .maxByOrNull { it.fetchedAtMillis }
                    ?.copy(isFromOfflineStore = true)
                if (usable != null) {
                    cachedForecast = usable
                    val covered = applyForecast(usable)
                    _formState.update {
                        it.copy(
                            isLoadingWeather = false,
                            weatherError = if (covered) {
                                "Hors-ligne : prévisions Open-Meteo du ${usable.fetchedAtLabel} pour ce lieu."
                            } else NO_SLOT_COVERAGE_MESSAGE
                        )
                    }
                } else {
                    _weatherAnalysis.value = null
                    _formState.update {
                        it.copy(
                            isLoadingWeather = false,
                            bestSlots48h = emptyList(),
                            weatherError = "Météo indisponible : Open-Meteo injoignable et aucune prévision de moins de 12 h pour ce lieu. Pas de recommandation sans vraie météo."
                        )
                    }
                }
            }
        }
    }

    /** Recalcule le créneau à partir de la prévision en mémoire si elle vaut pour ce lieu, sinon recharge. */
    private fun recalculateWeatherSlot() {
        val cached = cachedForecast
        val current = _formState.value
        if (cached != null && cached.isUsableFor(current)) {
            val covered = applyForecast(cached)
            _formState.update {
                it.copy(
                    weatherError = when {
                        !covered -> NO_SLOT_COVERAGE_MESSAGE
                        cached.isFromOfflineStore -> "Hors-ligne : prévisions Open-Meteo du ${cached.fetchedAtLabel} pour ce lieu."
                        else -> null
                    }
                )
            }
        } else {
            refreshWeatherFromOpenMeteo()
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

    // --- Rétroplanning (affichage seul, sans notifications) ---

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

    // --- Retour Après Sortie (Validation, Chaussures Km + Nutrition ±5 g/h + Historique) ---

    /** Date et heure de la sortie prévue (celles du créneau météo), pas celles du moment du retour. */
    private fun plannedRunMillis(rec: FullRunRecommendation, form: NewRunFormState): Long {
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(rec.weather.startDateTimeIso)?.time
        }.getOrNull()
        if (parsed != null) {
            return parsed + form.startMinute * 60_000L
        }
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, form.dayOffset)
            set(Calendar.HOUR_OF_DAY, form.startHour)
            set(Calendar.MINUTE, form.startMinute)
        }.timeInMillis
    }

    fun submitPostRunFeedback(
        actualDistanceKm: Double,
        actualDPlusMeters: Int,
        thermalFeeling: ThermalFeeling,
        gelFeedback: GelFeedback,
        gelsActuallyCarried: Int,
        customOutfitText: String,
        notes: String,
        wornShoeId: Long?
    ) {
        val rec = recommendation.value ?: return
        val form = _formState.value
        val validDistance = actualDistanceKm.coerceAtLeast(0.1)
        val validDPlus = actualDPlusMeters.coerceAtLeast(0)
        val usedShoe = wornShoeId?.let { id -> shoes.value.find { it.id == id } }

        viewModelScope.launch {
            // 1. Ajouter les km réels à la paire réellement portée
            if (usedShoe != null) {
                dao.addKmToShoe(usedShoe.id, validDistance)
            }

            // 2. Ajuster le taux de glucides/h de ±5 g/h (borné entre 30 et 90 g/h)
            val currentNut = _nutritionSettings.value
            val delta = gelFeedback.deltaCarbPerHour
            val updatedNut = if (form.durationMinutes > RecommendationEngine.LONG_EFFORT_THRESHOLD_MINUTES) {
                currentNut.copy(carbRateLong = (currentNut.carbRateLong + delta).coerceIn(30, 90))
            } else {
                currentNut.copy(carbRateMedium = (currentNut.carbRateMedium + delta).coerceIn(30, 90))
            }
            saveNutritionSettings(updatedNut)

            // 3. Enregistrer la sortie dans l'Historique, à la date prévue
            val runMillis = plannedRunMillis(rec, form)
            val gpxSummaryText = form.gpxTrack?.surfaceBreakdown?.let { b ->
                if (b.overpassError != null) "GPX (${form.gpxTrack.trackName}) : ${b.overpassError}"
                else "GPX ${form.gpxTrack.trackName} : ${b.bitumePercent}% bitume, ${b.cheminStabilisePercent}% stabilisé, ${b.terreForetPercent}% terre/forêt, ${b.inconnuPercent}% inconnu"
            } ?: ""

            val entry = RunHistoryEntity(
                dateEpochMillis = runMillis,
                dateFormatted = formatDate(runMillis),
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
                val newVal = if (form.durationMinutes > RecommendationEngine.LONG_EFFORT_THRESHOLD_MINUTES) updatedNut.carbRateLong else updatedNut.carbRateMedium
                " • Glucides ajustés à $newVal g/h"
            } else ""
            val shoeMsg = if (usedShoe != null) " • +${validDistance} km sur ${usedShoe.name}" else ""

            _formState.update {
                it.copy(
                    statusBannerMessage = "Sortie du ${entry.dateFormatted} enregistrée dans l'historique$shoeMsg$rateMsg."
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

    // --- Sauvegarde / restauration JSON ---

    private val backupSettingKeys = listOf(
        KEY_CARB_MEDIUM, KEY_CARB_LONG, KEY_GEL_GRAMS, KEY_ASPHALT_PCT,
        KEY_THERMAL_PROFILE, KEY_LAST_LOCATION, KEY_RECENT_LOCATIONS
    )

    fun exportBackup(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val message = try {
                val content = withContext(Dispatchers.IO) {
                    val settings = mutableMapOf<String, Any>()
                    val all = prefs.all
                    backupSettingKeys.forEach { key ->
                        when (val v = all[key]) {
                            is Int -> settings[key] = v
                            is String -> settings[key] = v
                        }
                    }
                    BackupManager.BackupContent(
                        shoes = dao.getAllShoesOnce(),
                        gearItems = dao.getAllGearOnce(),
                        checklistItems = dao.getAllChecklistsOnce(),
                        runHistory = dao.getAllRunHistoryOnce(),
                        settings = settings
                    )
                }
                withContext(Dispatchers.IO) {
                    val json = BackupManager.toJson(content)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Impossible d'écrire le fichier.")
                }
                "Sauvegarde exportée : ${content.shoes.size} paire(s), ${content.runHistory.size} sortie(s)."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Échec de l'export : ${e.message ?: "erreur inconnue"}"
            }
            onResult(message)
        }
    }

    fun importBackup(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val message = try {
                val content = withContext(Dispatchers.IO) {
                    val json = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: throw IllegalArgumentException("Fichier illisible.")
                    BackupManager.fromJson(json)
                }
                withContext(Dispatchers.IO) {
                    db.withTransaction {
                        dao.deleteAllRunHistory()
                        dao.deleteAllShoes()
                        dao.deleteAllGear()
                        dao.deleteAllChecklists()
                        dao.insertShoes(content.shoes)
                        dao.insertGearItems(content.gearItems)
                        dao.insertChecklistItems(content.checklistItems)
                        dao.insertRunHistoryList(content.runHistory)
                    }
                    val editor = prefs.edit()
                    content.settings.forEach { (key, value) ->
                        if (key in backupSettingKeys) {
                            when (value) {
                                is Int -> editor.putInt(key, value)
                                is String -> editor.putString(key, value)
                            }
                        }
                    }
                    editor.apply()
                }
                _nutritionSettings.value = loadNutritionSettings()
                val profileName = prefs.getString(KEY_THERMAL_PROFILE, ThermalProfile.NEUTRE.name) ?: ThermalProfile.NEUTRE.name
                _formState.update {
                    it.copy(
                        thermalProfile = runCatching { ThermalProfile.valueOf(profileName) }.getOrDefault(ThermalProfile.NEUTRE),
                        manualShoeOverrideId = null
                    )
                }
                restoreSavedLocations()
                refreshWeatherFromOpenMeteo()
                "Sauvegarde restaurée : ${content.shoes.size} paire(s), ${content.runHistory.size} sortie(s)."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Échec de l'import : ${e.message ?: "erreur inconnue"}"
            }
            onResult(message)
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
                locationName = if (currentForm.hasLocation) currentForm.locationName else "",
                latitude = currentForm.latitude,
                longitude = currentForm.longitude,
                hasLocation = currentForm.hasLocation,
                terrain = currentForm.terrain,
                sessionType = currentForm.sessionType,
                citySearchQuery = "",
                citySearchResults = emptyList(),
                weather = null,
                suggestedOutfit = ""
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
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
                hasLocation = true,
                citySearchQuery = "",
                citySearchResults = emptyList()
            )
        }
        refreshPastRunHistoricalWeather()
    }

    fun refreshPastRunHistoricalWeather() {
        val draft = _pastRunDraft.value
        if (!draft.hasLocation) {
            _pastRunDraft.update {
                it.copy(
                    isLoadingWeather = false,
                    weather = null,
                    weatherStatusMessage = "Choisis d'abord le lieu de la sortie."
                )
            }
            return
        }
        pastWeatherJob?.cancel()
        pastWeatherJob = viewModelScope.launch {
            _pastRunDraft.update {
                it.copy(
                    isLoadingWeather = true,
                    weather = null,
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
                if (weather == null) {
                    _pastRunDraft.update {
                        it.copy(
                            isLoadingWeather = false,
                            weather = null,
                            weatherStatusMessage = "Pas de données météo pour ce créneau : impossible d'enregistrer avec une météo fiable."
                        )
                    }
                    return@launch
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
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _pastRunDraft.update {
                    it.copy(
                        isLoadingWeather = false,
                        weather = null,
                        weatherStatusMessage = "Météo historique indisponible (réseau ?) : réessaie, l'enregistrement attend une vraie météo."
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
        val weather = draft.weather
        if (weather == null) {
            _formState.update {
                it.copy(statusBannerMessage = "Sortie non enregistrée : météo historique indisponible.")
            }
            return
        }
        val validDist = distanceKm.coerceAtLeast(0.1)
        val selectedShoe = shoeId?.let { id -> shoes.value.find { it.id == id } }

        viewModelScope.launch {
            if (addKmToShoe && selectedShoe != null) {
                dao.addKmToShoe(selectedShoe.id, validDist)
            }

            val currentNut = _nutritionSettings.value
            val isLong = draft.durationMinutes > RecommendationEngine.LONG_EFFORT_THRESHOLD_MINUTES
            val activeRate = if (isLong) currentNut.carbRateLong else currentNut.carbRateMedium
            if (adjustCarbRate && gelFeedback.deltaCarbPerHour != 0) {
                val delta = gelFeedback.deltaCarbPerHour
                val updatedNut = if (isLong) {
                    currentNut.copy(carbRateLong = (currentNut.carbRateLong + delta).coerceIn(30, 90))
                } else {
                    currentNut.copy(carbRateMedium = (currentNut.carbRateMedium + delta).coerceIn(30, 90))
                }
                saveNutritionSettings(updatedNut)
            }

            val ownedSet = gearItems.value.filter { it.owned }.map { it.code }.toSet()
            val mudAlert = RecommendationEngine.computeMudAlert(
                terrain = draft.terrain,
                rain72hMm = weather.past72hRainMm,
                ownedGearCodes = ownedSet
            )

            val runMillis = Calendar.getInstance().apply {
                timeInMillis = draft.dateEpochMillis
                set(Calendar.HOUR_OF_DAY, draft.startHour)
                set(Calendar.MINUTE, 0)
            }.timeInMillis

            val entry = RunHistoryEntity(
                dateEpochMillis = runMillis,
                dateFormatted = draft.dateFormatted.ifBlank { formatDate(runMillis) },
                locationName = draft.locationName,
                sessionType = draft.sessionType,
                terrain = draft.terrain,
                plannedDistanceKm = validDist,
                actualDistanceKm = validDist,
                elevationGainDPlusMeters = dPlusMeters.coerceAtLeast(0),
                gpxSurfaceSummary = "",
                durationMinutes = draft.durationMinutes,
                realTempC = weather.realTempC,
                referenceTempC = weather.referenceTempC,
                windKmh = weather.maxWindKmh,
                rainMm = weather.slotRainMm,
                rain72hMm = weather.past72hRainMm,
                mudLevel = mudAlert.mudLevel,
                maxUvIndex = weather.maxUvIndex,
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
                    statusBannerMessage = "Sortie passée du ${entry.dateFormatted} à ${entry.locationName} enregistrée dans l'historique."
                )
            }
        }
    }

    // --- Widget ---

    private fun pushWidgetUpdate(rec: FullRunRecommendation) {
        val form = _formState.value
        val outfitSummary = buildString {
            append(rec.outfitItems.take(2).joinToString(", "))
            rec.selectedShoe?.let { append(", ${it.name}") }
            if (rec.nutrition.recommendedGelsCount > 0) {
                append(", ${rec.nutrition.recommendedGelsCount} gels")
            }
        }
        NextRunWidgetProvider.saveAndPushWidgetUpdate(
            context = getApplication(),
            locationName = form.locationName,
            terrainLabel = form.terrain.label,
            distanceKm = form.plannedDistanceKm,
            referenceTempC = rec.effectiveReferenceTempC,
            outfitSummary = outfitSummary,
            alertsSummary = rec.widgetAlertSummary
        )
    }

    companion object {
        private const val KEY_THERMAL_PROFILE = "thermal_profile"
        private const val KEY_CARB_MEDIUM = "carb_rate_medium"
        private const val KEY_CARB_LONG = "carb_rate_long"
        private const val KEY_GEL_GRAMS = "gel_carbs_grams"
        private const val KEY_ASPHALT_PCT = "asphalt_threshold_pct"
        private const val KEY_LAST_LOCATION = "last_location_json"
        private const val KEY_RECENT_LOCATIONS = "recent_locations_json"
        private const val KEY_CACHE_JSON = "cached_weather_json"
        private const val KEY_CACHE_LAT = "cached_weather_lat"
        private const val KEY_CACHE_LON = "cached_weather_lon"
        private const val KEY_CACHE_AT_MILLIS = "cached_weather_at_millis"
        private const val KEY_CACHE_LABEL = "cached_weather_label"
        private const val KEY_DEMO_CLEANUP_DONE = "demo_cleanup_v1_done"

        private const val NO_SLOT_COVERAGE_MESSAGE =
            "La prévision ne couvre pas ce créneau (trop loin ou déjà passé) : pas de recommandation."
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
