package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ChecklistItemEntity
import com.example.data.local.ShoeEntity
import com.example.data.model.ChecklistType
import com.example.data.model.GelFeedback
import com.example.data.model.GeoLocationResult
import com.example.data.model.MudLevel
import com.example.data.model.RetroplanningConfig
import com.example.data.model.RunSessionType
import com.example.data.model.TerrainType
import com.example.data.model.ThermalFeeling
import com.example.domain.FullRunRecommendation
import com.example.ui.NewRunFormState
import com.example.data.model.BestWeatherSlotSuggestion
import com.example.data.model.ThermalProfile
import com.example.ui.theme.AlertRed
import com.example.ui.theme.MudAmber
import com.example.ui.theme.MudBrown
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NewRunAndRecommendationScreen(
    formState: NewRunFormState,
    recommendation: FullRunRecommendation?,
    shoes: List<ShoeEntity>,
    beforeStartChecklist: List<ChecklistItemEntity>,
    retroConfig: RetroplanningConfig,
    asphaltThresholdPercent: Int = 60,
    onTerrainChange: (TerrainType) -> Unit,
    onPlannedDistanceChange: (Double) -> Unit,
    onElevationGainChange: (Int) -> Unit = {},
    onPaceChange: (Int) -> Unit = {},
    onImportGpxUri: (android.net.Uri, String) -> Unit = { _, _ -> },
    onRetryOverpassAnalysis: () -> Unit = {},
    onApplyCustomSurfaceBreakdown: (Double, Double, Double) -> Unit = { _, _, _ -> },
    onClearGpx: () -> Unit = {},
    onDurationChange: (Int) -> Unit,
    onSessionTypeChange: (RunSessionType) -> Unit,
    onStartTimeChange: (Int, Int, Int) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectLocation: (GeoLocationResult) -> Unit,
    onDetectGps: () -> Unit,
    onRefreshWeather: () -> Unit,
    onStartNewRunReset: () -> Unit,
    onClearBanner: () -> Unit,
    onSelectManualShoe: (Long?) -> Unit,
    onToggleChecklist: (ChecklistItemEntity) -> Unit,
    onAddChecklist: (ChecklistType, String) -> Unit,
    onDeleteChecklist: (ChecklistItemEntity) -> Unit,
    onResetBeforeChecklist: () -> Unit,
    onUpdateRetroConfig: (Int, Int, Int, Int, Int, Int) -> Unit,
    onThermalProfileChange: (ThermalProfile) -> Unit = {},
    onWaterRefillCountChange: (Int) -> Unit = {},
    onApplyBestWeatherSlot: (BestWeatherSlotSuggestion) -> Unit = {},
    onSubmitPostRun: (Double, Int, ThermalFeeling, GelFeedback, Int, String, String, Long?) -> Unit
) {
    var showPostRunDialog by remember { mutableStateOf(false) }
    var showBestSlots48h by remember { mutableStateOf(false) }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    var distanceInputText by remember(formState.plannedDistanceKm) {
        mutableStateOf(
            if (formState.plannedDistanceKm % 1.0 == 0.0) formState.plannedDistanceKm.toInt().toString()
            else formState.plannedDistanceKm.toString()
        )
    }
    var dPlusInputText by remember(formState.elevationGainDPlusMeters) {
        mutableStateOf(formState.elevationGainDPlusMeters.toString())
    }

    val gpxFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Trace GPX"
            onImportGpxUri(uri, fileName)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            onDetectGps()
        }
    }

    // Premier lancement sans lieu mémorisé : on propose la localisation GPS une fois.
    var askedLocationOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(formState.hasLocation) {
        if (!formState.hasLocation && !askedLocationOnce) {
            askedLocationOnce = true
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("new_run_screen_list"),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Hero Header Banner
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_trail_hero_1792133585708),
                    contentDescription = "Bannière Trail & Route",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.25f),
                                    Color.Black.copy(alpha = 0.80f)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "NOUVELLE SORTIE & RECOMMANDATION",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFFFF8A65)
                            )
                            Text(
                                text = buildString {
                                    append("${formState.locationName} • ${formState.terrain.label} ${formState.plannedDistanceKm.roundToInt()} km")
                                    if (formState.elevationGainDPlusMeters > 0) {
                                        append(" • ${formState.elevationGainDPlusMeters} m D+")
                                    }
                                },
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onStartNewRunReset,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("new_run_reset_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("RAZ Sortie", color = Color.White, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // Status / Notification Banner
        if (formState.statusBannerMessage != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formState.statusBannerMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearBanner) {
                            Icon(Icons.Default.Close, contentDescription = "Fermer")
                        }
                    }
                }
            }
        }

        // 2. Section « Nouvelle sortie » : Import GPX optionnel, Lieu, Terrain (Route / Trail), Distance & D+, Durée (min 30m), Type & Heure libre
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .testTag("new_run_config_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Paramètres de la sortie",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Row {
                            IconButton(
                                onClick = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("gps_location_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MyLocation,
                                    contentDescription = "Localisation GPS",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Bouton facultatif « Importer une trace GPX »
                    GpxImportBarSection(
                        gpxTrack = formState.gpxTrack,
                        onPickGpxFileClick = { gpxFileLauncher.launch("*/*") },
                        onClearGpx = onClearGpx
                    )

                    // Recherche de lieu Open-Meteo Geocoding
                    OutlinedTextField(
                        value = formState.citySearchQuery,
                        onValueChange = onSearchQueryChange,
                        label = { Text("Lieu (${formState.locationName}) — Rechercher une ville") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (formState.isLoadingWeather || formState.isSearchingCity) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = onRefreshWeather) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Actualiser Open-Meteo")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("city_search_input")
                    )

                    if (formState.citySearchResults.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                formState.citySearchResults.forEach { geo ->
                                    Text(
                                        text = geo.displayLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelectLocation(geo) }
                                            .padding(vertical = 8.dp, horizontal = 8.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Villes récentes / favorites en 1 clic
                    if (formState.recentLocations.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            formState.recentLocations.forEach { loc ->
                                val isCurrent = loc.name.equals(formState.locationName, ignoreCase = true)
                                FilterChip(
                                    selected = isCurrent,
                                    onClick = { onSelectLocation(loc) },
                                    label = { Text(loc.name, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                        }
                    }

                    // Choix du TERRAIN : Route / Trail
                    Text(
                        text = "Terrain",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TerrainType.entries.forEach { terrain ->
                            val selected = formState.terrain == terrain
                            FilterChip(
                                selected = selected,
                                onClick = { onTerrainChange(terrain) },
                                label = {
                                    Text(
                                        text = terrain.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (terrain == TerrainType.TRAIL) Icons.Default.Landscape else Icons.Default.DirectionsRun,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("terrain_chip_${terrain.name.lowercase()}")
                            )
                        }
                    }

                    // Distance prévue en km + D+ (m) (pré-remplis par GPX si importé, modifiables)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = distanceInputText,
                            onValueChange = { txt ->
                                distanceInputText = txt
                                txt.replace(",", ".").toDoubleOrNull()?.let { onPlannedDistanceChange(it) }
                            },
                            label = { Text("Distance prévue (km)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("planned_distance_input")
                        )

                        OutlinedTextField(
                            value = dPlusInputText,
                            onValueChange = { txt ->
                                dPlusInputText = txt
                                txt.toIntOrNull()?.let { onElevationGainChange(it) }
                            },
                            label = { Text("D+ (m)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(0.85f)
                                .testTag("elevation_gain_input")
                        )
                    }

                    // Affichage automatique du Km-Effort lorsque D+ > 0
                    if (formState.elevationGainDPlusMeters > 0) {
                        val kmEffort = ((formState.plannedDistanceKm + formState.elevationGainDPlusMeters / 100.0) * 10.0).roundToInt() / 10.0
                        val slopePerKm = (formState.elevationGainDPlusMeters / formState.plannedDistanceKm.coerceAtLeast(0.1)).roundToInt()
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Charge réelle : $kmEffort km-effort (${formState.plannedDistanceKm} km + ${formState.elevationGainDPlusMeters} m D+ • ratio $slopePerKm m D+/km)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Allure cible (recalcule automatiquement la durée selon les Km-Effort)
                    val paceMin = formState.paceSecPerKm / 60
                    val paceSec = formState.paceSecPerKm % 60
                    val paceFormatted = String.format("%d:%02d /km", paceMin, paceSec)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Allure cible (durée auto) : $paceFormatted",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = { onPaceChange((formState.paceSecPerKm - 15).coerceAtLeast(210)) },
                                label = { Text("-15s", maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("pace_minus_15s_button")
                            )
                            AssistChip(
                                onClick = { onPaceChange((formState.paceSecPerKm + 15).coerceAtMost(540)) },
                                label = { Text("+15s", maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("pace_plus_15s_button")
                            )
                        }
                    }

                    // Durée estimée : minimum strict à 30 min (avec boutons -15 min / +15 min + curseur)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Durée : ${
                                    if (formState.durationMinutes < 60) "${formState.durationMinutes} min"
                                    else "${formState.durationMinutes / 60}h${String.format("%02d", formState.durationMinutes % 60)} (${formState.durationMinutes} min)"
                                }",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(
                                    onClick = { onDurationChange((formState.durationMinutes - 15).coerceAtLeast(30)) },
                                    label = { Text("-15m", maxLines = 1, softWrap = false) },
                                    modifier = Modifier.testTag("duration_minus_15_button")
                                )
                                AssistChip(
                                    onClick = { onDurationChange((formState.durationMinutes + 15).coerceAtMost(360)) },
                                    label = { Text("+15m", maxLines = 1, softWrap = false) },
                                    modifier = Modifier.testTag("duration_plus_15_button")
                                )
                            }
                        }
                        Slider(
                            value = formState.durationMinutes.coerceIn(30, 360).toFloat(),
                            onValueChange = { raw ->
                                val snapped = (((raw - 30f) / 15f).roundToInt() * 15 + 30).coerceIn(30, 360)
                                onDurationChange(snapped)
                            },
                            valueRange = 30f..360f,
                            modifier = Modifier.testTag("duration_slider")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Min : 30 min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Max : 6h00", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Type de sortie (Endurance, Sortie longue, Fractionné, Course)
                    Text(
                        text = "Type de sortie",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RunSessionType.entries.forEach { sType ->
                            FilterChip(
                                selected = formState.sessionType == sType,
                                onClick = { onSessionTypeChange(sType) },
                                label = { Text(sType.label) },
                                modifier = Modifier.testTag("session_type_${sType.name.lowercase()}")
                            )
                        }
                    }

                    // Créneau horaire de départ : Libre choix du jour et de l'heure (00h00 à 23h59)
                    Text(
                        text = "Jour & Heure de départ",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(0 to "Aujourd'hui", 1 to "Demain", 2 to "J+2").forEach { (offset, label) ->
                            FilterChip(
                                selected = formState.dayOffset == offset,
                                onClick = { onStartTimeChange(offset, formState.startHour, formState.startMinute) },
                                label = { Text(label) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val totalMin = (formState.startHour * 60 + formState.startMinute - 30 + 1440) % 1440
                                onStartTimeChange(formState.dayOffset, totalMin / 60, totalMin % 60)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text("-30m", maxLines = 1, softWrap = false)
                        }

                        Button(
                            onClick = { showTimePickerDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_time_picker_button")
                        ) {
                            Text(
                                text = "Départ : ${String.format("%02dh%02d", formState.startHour, formState.startMinute)} (Modifier)",
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val totalMin = (formState.startHour * 60 + formState.startMinute + 30) % 1440
                                onStartTimeChange(formState.dayOffset, totalMin / 60, totalMin % 60)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text("+30m", maxLines = 1, softWrap = false)
                        }
                    }

                    // Détecteur des 3 meilleurs créneaux météo sur 48 h (« Quand courir ? »)
                    if (formState.bestSlots48h.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showBestSlots48h = !showBestSlots48h },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("toggle_best_slots_48h_button")
                        ) {
                            Icon(Icons.Default.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (showBestSlots48h) "Masquer les 3 meilleurs créneaux météo 48h"
                                else "Quand courir ? Voir les 3 meilleurs créneaux météo 48h",
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        AnimatedVisibility(visible = showBestSlots48h) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Top 3 créneaux favorables sur 48 h (toucher pour appliquer) :",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    formState.bestSlots48h.forEachIndexed { idx, slot ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onApplyBestWeatherSlot(slot) }
                                                .testTag("best_slot_item_$idx")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "#${idx + 1} • ${slot.dayLabel} ${String.format("%02dh00–%02dh00", slot.startHour, slot.endHour)} (${slot.weatherConditionLabel})",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${slot.avgTempC}°C • Vent ${slot.maxWindKmh.roundToInt()} km/h • Pluie ${slot.slotRainMm} mm (${slot.maxRainProbPercent}%)",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer
                                                ) {
                                                    Text(
                                                        text = "${slot.score}/100",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                }
            }
        }

        // Statut météo : pas de lieu, météo indisponible ou mode hors-ligne
        val weatherStatusText = when {
            !formState.hasLocation ->
                "Aucun lieu choisi : recherche une ville ou utilise le GPS pour obtenir la météo et la recommandation."
            formState.weatherError != null -> formState.weatherError
            recommendation == null && formState.isLoadingWeather -> "Chargement de la météo Open-Meteo…"
            else -> null
        }
        if (weatherStatusText != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (recommendation == null) MudAmber.copy(alpha = 0.18f) else MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .testTag("weather_status_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = weatherStatusText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (formState.hasLocation && !formState.isLoadingWeather) {
                            OutlinedButton(onClick = onRefreshWeather) {
                                Text("Réessayer", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }

        // 2b. Si une trace GPX est importée : afficher la répartition en % (barre colorée) et la carte de la trace colorée par catégorie
        if (formState.gpxTrack != null) {
            item {
                GpxSurfaceAnalysisCard(
                    gpxTrack = formState.gpxTrack,
                    asphaltThresholdPercent = asphaltThresholdPercent,
                    onRetryAnalysis = onRetryOverpassAnalysis,
                    onApplyCustomBreakdown = onApplyCustomSurfaceBreakdown
                )
            }
        }

        // 3. Météo sur le créneau, Température de référence, Alerte Boue (72h) & Alerte UV (Soleil)
        if (recommendation != null) {
            item {
                WeatherAndAlertsSection(recommendation = recommendation)
            }

            // 4. Choix automatique des Chaussures + Alerte > 90 % km max
            item {
                ShoeRecommendationCard(
                    recommendation = recommendation,
                    allShoes = shoes,
                    onSelectShoeOverride = onSelectManualShoe
                )
            }

            // 5. Tenue recommandée, Accessoires & Nutrition (Gels g/h)
            item {
                OutfitAndNutritionCard(
                    recommendation = recommendation,
                    selectedThermalProfile = formState.thermalProfile,
                    onThermalProfileChange = onThermalProfileChange,
                    onWaterRefillCountChange = onWaterRefillCountChange,
                    onOpenPostRunDialog = { showPostRunDialog = true }
                )
            }

            // 5b. Frise chronologique de nutrition Avant & Après sortie (de la veille à l'après-sortie)
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    NutritionTimelineCard(
                        steps = recommendation.nutritionTimeline
                    )
                }
            }

            // 6. Sorties similaires (±2 °C, vent et pluie comparables)
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SimilarRunsCard(
                        similarRuns = recommendation.similarRuns,
                        currentRefTemp = recommendation.weather.referenceTempC
                    )
                }
            }

            // 7. Check-list avant départ (remise à zéro à chaque sortie)
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ChecklistCard(
                        title = "Check-list avant départ",
                        subtitle = "Personnalisable • Remise à zéro à chaque nouvelle sortie",
                        isPostRaceBag = false,
                        hasAutoWarmJacket = false,
                        items = beforeStartChecklist,
                        onToggleItem = onToggleChecklist,
                        onAddItem = { label -> onAddChecklist(ChecklistType.BEFORE_START, label) },
                        onDeleteItem = onDeleteChecklist,
                        onResetChecklist = onResetBeforeChecklist
                    )
                }
            }

            // 7b. Partager & Copier la fiche de sortie
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SafetyAndShareCard(
                        formState = formState,
                        recommendation = recommendation,
                        beforeStartChecklist = beforeStartChecklist
                    )
                }
            }

            // 8. Si type == COURSE : Sac d'après-course (avec veste chaude auto si < 10°C) + Rétroplanning minuté
            if (formState.sessionType == RunSessionType.COURSE) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        ChecklistCard(
                            title = "Sac d'après-course",
                            subtitle = "Spécial Course • Ajout auto d'une veste chaude si T° < 10 °C",
                            isPostRaceBag = true,
                            hasAutoWarmJacket = recommendation.hasAutoWarmJacketInBag,
                            items = recommendation.postRaceBagItems,
                            onToggleItem = onToggleChecklist,
                            onAddItem = { label -> onAddChecklist(ChecklistType.POST_RACE_BAG, label) },
                            onDeleteItem = onDeleteChecklist
                        )
                    }
                }

                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        RetroplanningCard(
                            config = retroConfig,
                            steps = recommendation.retroplanningSteps,
                            onUpdateConfig = onUpdateRetroConfig
                        )
                    }
                }
            }
        }
    }

    if (showTimePickerDialog) {
        val timePickerState = androidx.compose.material3.rememberTimePickerState(
            initialHour = formState.startHour,
            initialMinute = formState.startMinute,
            is24Hour = true
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePickerDialog = false },
            title = { Text("Choisir l'heure de départ") },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onStartTimeChange(formState.dayOffset, timePickerState.hour, timePickerState.minute)
                        showTimePickerDialog = false
                    }
                ) {
                    Text("Valider")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showTimePickerDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    if (showPostRunDialog && recommendation != null) {
        PostRunFeedbackDialog(
            recommendation = recommendation,
            shoes = shoes,
            onDismiss = { showPostRunDialog = false },
            onConfirm = { actualKm, actualDPlus, feeling, gelFb, gelsCount, outfitTxt, notes, shoeId ->
                onSubmitPostRun(actualKm, actualDPlus, feeling, gelFb, gelsCount, outfitTxt, notes, shoeId)
                showPostRunDialog = false
            }
        )
    }
}

@Composable
private fun WeatherAndAlertsSection(recommendation: FullRunRecommendation) {
    val w = recommendation.weather
    val mud = recommendation.mudAlert
    val uv = recommendation.uvAlert

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Métriques Météo Open-Meteo
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("weather_summary_card"),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp)
                    ) {
                        Text(
                            text = "Météo Open-Meteo • ${w.weatherConditionLabel}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val freshnessNote = when {
                            w.isFromOfflineCache && w.fetchedAtTimeLabel.isNotBlank() -> " • Cache du ${w.fetchedAtTimeLabel}"
                            w.fetchedAtTimeLabel.isNotBlank() -> " • Relevé ${w.fetchedAtTimeLabel}"
                            else -> ""
                        }
                        val sunNote = if (w.sunriseLabel != null && w.sunsetLabel != null) {
                            " • Soleil ${w.sunriseLabel} – ${w.sunsetLabel}${if (w.isNightOrDawn) " (frontale)" else ""}"
                        } else ""
                        Text(
                            text = "Cumul 72h : ${w.past72hRainMm} mm • Humidité : ${w.avgHumidityPercent} %$freshnessNote$sunNote",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Réf ${recommendation.effectiveReferenceTempC}°C",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    WeatherMetricPill(
                        icon = Icons.Default.Thermostat,
                        label = "T° réelle (ress. ${w.apparentTempC}°)",
                        value = "${w.realTempC}°C"
                    )
                    WeatherMetricPill(
                        icon = Icons.Default.Air,
                        label = "Rafales ${w.maxWindGustsKmh.roundToInt()} km/h",
                        value = "${w.maxWindKmh.roundToInt()} km/h • ${w.windDirectionCardinal}"
                    )
                    WeatherMetricPill(
                        icon = Icons.Default.WaterDrop,
                        label = "Proba pluie",
                        value = "${w.maxRainProbabilityPercent} % (${w.slotRainMm} mm)"
                    )
                    WeatherMetricPill(
                        icon = Icons.Default.WbSunny,
                        label = "UV max",
                        value = "${w.maxUvIndex}"
                    )
                }

                // Météo au point culminant si une trace GPX avec altitude est chargée
                if (w.summitTempC != null && w.summitElevationMeters != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Météo au point culminant GPX (${w.summitElevationMeters} m) : ~${w.summitTempC}°C estimés (écart ${(w.summitTempC - w.realTempC).roundToInt()}°C vs départ)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Évolution météo heure par heure pendant la sortie
                if (w.hourlySteps.size >= 2) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Évolution heure par heure sur le créneau :",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        w.hourlySteps.take(5).forEach { step ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = step.hourLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${step.tempC}°C",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${step.rainProbPercent}% • ${step.windKmh.roundToInt()}km/h",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Indication veste selon la probabilité de pluie et les rafales sur le créneau
                val jacketContainerColor = when {
                    w.slotRainMm >= 1.0 || (w.maxRainProbabilityPercent >= 70 && w.slotRainMm >= 0.3) ->
                        MaterialTheme.colorScheme.primaryContainer
                    w.needsJacketJustInCase ->
                        MaterialTheme.colorScheme.tertiaryContainer
                    else ->
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                }
                val jacketAdviceText = when {
                    w.slotRainMm >= 1.0 || (w.maxRainProbabilityPercent >= 70 && w.slotRainMm >= 0.3) ->
                        "Pluie prévue (${w.maxRainProbabilityPercent} % • ${w.slotRainMm} mm) : portez une veste imperméable dès le départ."
                    w.needsJacketJustInCase && w.maxRainProbabilityPercent >= 40 ->
                        "Probabilité de pluie ${w.maxRainProbabilityPercent} % : emportez une veste de pluie / coupe-vent au cas où."
                    w.needsJacketJustInCase ->
                        "Rafales prévues (${w.maxWindGustsKmh.roundToInt()} km/h) : emportez un coupe-vent dans le gilet."
                    else ->
                        "Probabilité de pluie faible (${w.maxRainProbabilityPercent} %) : pas de veste de pluie nécessaire."
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = jacketContainerColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rain_probability_jacket_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Checkroom,
                            contentDescription = "Conseil veste pluie",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = jacketAdviceText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (w.needsJacketJustInCase || w.slotRainMm >= 1.0) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Alerte Boue (Trail uniquement)
        if (mud.isApplicable) {
            val mudColor = when (mud.mudLevel) {
                MudLevel.SEC -> MaterialTheme.colorScheme.secondaryContainer
                MudLevel.HUMIDE -> MudAmber.copy(alpha = 0.2f)
                MudLevel.BOUEUX -> MudBrown.copy(alpha = 0.25f)
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = mudColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mud_alert_banner")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Hiking,
                        contentDescription = "État du terrain",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "État du terrain Trail : ${mud.mudLevel.label.uppercase()} (${mud.rain72hMm} mm / 72 h)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = mud.message,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Alerte Soleil / Indice UV
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (uv.needsSunscreen) MaterialTheme.colorScheme.primaryContainer
            else if (uv.needsGlassesAndCap) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("uv_alert_banner")
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WbSunny,
                    contentDescription = "Indice UV",
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Soleil & UV Max : ${uv.maxUv}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = uv.message,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherMetricPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShoeRecommendationCard(
    recommendation: FullRunRecommendation,
    allShoes: List<ShoeEntity>,
    onSelectShoeOverride: (Long?) -> Unit
) {
    val shoe = recommendation.selectedShoe

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .testTag("shoe_recommendation_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = "Chaussures recommandées",
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Chaussures sélectionnées",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = recommendation.shoeSelectionReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (shoe != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = shoe.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = shoe.type.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { shoe.wearRatio.coerceAtMost(1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (shoe.isOver90Percent) AlertRed else MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Usure : ${shoe.currentKm.roundToInt()} / ${shoe.maxKm.roundToInt()} km (${(shoe.wearRatio * 100).roundToInt()}%)",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Après sortie : ${(shoe.currentKm + recommendation.plannedDistanceKm).roundToInt()} km",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (shoe.isOver90Percent) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = "Alerte usure",
                                    tint = AlertRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Alerte : cette paire dépasse 90 % de son km max (${shoe.maxKm.roundToInt()} km) !",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlertRed,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Permet aussi de basculer sur une autre paire ou revenir au choix auto
                if (allShoes.size > 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AssistChip(
                            onClick = { onSelectShoeOverride(null) },
                            label = { Text("Choix auto") }
                        )
                        allShoes.forEach { candidate ->
                            FilterChip(
                                selected = candidate.id == shoe.id,
                                onClick = { onSelectShoeOverride(candidate.id) },
                                label = { Text("${candidate.name} (${candidate.currentKm.roundToInt()} km)") }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutfitAndNutritionCard(
    recommendation: FullRunRecommendation,
    selectedThermalProfile: ThermalProfile = ThermalProfile.NEUTRE,
    onThermalProfileChange: (ThermalProfile) -> Unit = {},
    onWaterRefillCountChange: (Int) -> Unit = {},
    onOpenPostRunDialog: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .testTag("outfit_and_nutrition_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Checkroom,
                    contentDescription = "Tenue & Équipement",
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tenue & Accessoires recommandés",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (recommendation.thermalAdjustmentExplanation.isNotBlank()) {
                        Text(
                            text = recommendation.thermalAdjustmentExplanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Sélecteur rapide de profil thermique personnel (Frileux / Neutre / Chaud)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ThermalProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = selectedThermalProfile == profile,
                        onClick = { onThermalProfileChange(profile) },
                        label = { Text(profile.label, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.testTag("thermal_profile_${profile.name.lowercase()}")
                    )
                }
            }

            recommendation.outfitItems.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            if (recommendation.accessoriesItems.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Accessoires (Soleil, Trail, D+, Hydratation)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                recommendation.accessoriesItems.forEach { acc ->
                    Text(
                        text = "• $acc",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            HorizontalDivider()

            // Section Nutrition & Gels + Chrono des prises + Électrolytes + Ravitaillements
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Nutrition & Gels",
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Stratégie Nutrition, Gels & Hydratation",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = recommendation.nutrition.ruleExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (recommendation.nutrition.gelIntakeSchedule.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Chrono prises : ${recommendation.nutrition.gelIntakeSchedule}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            if (recommendation.nutrition.electrolytesAdvice.isNotBlank()) {
                Text(
                    text = "• ${recommendation.nutrition.electrolytesAdvice}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (recommendation.nutrition.solidOrSaltyAdvice.isNotBlank()) {
                Text(
                    text = "• ${recommendation.nutrition.solidOrSaltyAdvice}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Sélecteur de Points d'eau / Ravitaillements pour alléger le portage d'eau
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (recommendation.nutrition.waterRefillCount > 0) {
                        "Points d'eau sur parcours : porter ${recommendation.nutrition.carriedWaterPerSegmentLiters} L au départ (${recommendation.nutrition.waterLiters} L au total)"
                    } else {
                        "Points d'eau sur parcours : autonomie complète (${recommendation.nutrition.waterLiters} L à porter)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        0 to "0 ravito (Autonomie)",
                        1 to "1 point d'eau",
                        2 to "2+ ravitos"
                    ).forEach { (count, label) ->
                        FilterChip(
                            selected = recommendation.nutrition.waterRefillCount == count,
                            onClick = { onWaterRefillCountChange(count) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("water_refill_chip_$count")
                        )
                    }
                }
            }

            Button(
                onClick = onOpenPostRunDialog,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_post_run_feedback_button")
            ) {
                Icon(Icons.Default.DirectionsRun, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retour après sortie (Km réels + Ressenti + Gels)")
            }
        }
    }
}
