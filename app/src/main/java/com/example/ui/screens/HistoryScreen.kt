package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.RunHistoryEntity
import com.example.data.local.ShoeEntity
import com.example.data.model.GelFeedback
import com.example.data.model.GeoLocationResult
import com.example.data.model.MudLevel
import com.example.data.model.RunSessionType
import com.example.data.model.TerrainType
import com.example.data.model.ThermalFeeling
import com.example.ui.PastRunDraftState
import kotlin.math.roundToInt

enum class HistoryPeriodFilter(val label: String, val days: Int?) {
    ALL("Toutes dates", null),
    DAYS_30("30 derniers jours", 30),
    MONTHS_3("3 derniers mois", 90),
    YEAR_1("Cette saison (1 an)", 365)
}

enum class HistoryTerrainFilter(val label: String, val terrain: TerrainType?) {
    ALL("Tous terrains", null),
    ROUTE("Route", TerrainType.ROUTE),
    TRAIL("Trail", TerrainType.TRAIL)
}

enum class HistoryConditionFilter(val label: String) {
    ALL("Toutes conditions"),
    COLD("Froid (< 8°C)"),
    RAIN("Pluie (> 0.5 mm)"),
    MUD("Boue (Humide/Boueux)"),
    SUN_UV("Soleil (UV ≥ 3)")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    history: List<RunHistoryEntity>,
    shoes: List<ShoeEntity> = emptyList(),
    pastRunDraft: PastRunDraftState = PastRunDraftState(),
    onInitPastRunDialog: () -> Unit = {},
    onUpdatePastRunParams: (Long, Int, Int, TerrainType, RunSessionType) -> Unit = { _, _, _, _, _ -> },
    onUpdatePastRunCitySearch: (String) -> Unit = {},
    onSelectPastRunLocation: (GeoLocationResult) -> Unit = {},
    onRefreshPastRunWeather: () -> Unit = {},
    onSavePastRun: (Double, Int, Long?, Boolean, String, Int, ThermalFeeling, GelFeedback, Boolean, String) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> },
    onDeleteEntry: (RunHistoryEntity) -> Unit
) {
    var selectedPeriod by remember { mutableStateOf(HistoryPeriodFilter.ALL) }
    var selectedTerrain by remember { mutableStateOf(HistoryTerrainFilter.ALL) }
    var selectedCondition by remember { mutableStateOf(HistoryConditionFilter.ALL) }
    var showAddPastRunDialog by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val filteredRuns = remember(history, selectedPeriod, selectedTerrain, selectedCondition) {
        history.filter { run ->
            val periodMatch = selectedPeriod.days?.let { days ->
                val cutoff = now - days * 24L * 3600L * 1000L
                run.dateEpochMillis >= cutoff
            } ?: true

            val terrainMatch = selectedTerrain.terrain?.let { it == run.terrain } ?: true

            val conditionMatch = when (selectedCondition) {
                HistoryConditionFilter.ALL -> true
                HistoryConditionFilter.COLD -> run.realTempC < 8.0 || run.referenceTempC < 8.0
                HistoryConditionFilter.RAIN -> run.rainMm >= 0.5
                HistoryConditionFilter.MUD -> run.terrain == TerrainType.TRAIL && run.mudLevel != MudLevel.SEC
                HistoryConditionFilter.SUN_UV -> run.maxUvIndex >= 3.0
            }

            periodMatch && terrainMatch && conditionMatch
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("history_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Historique (${filteredRuns.size})",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Consultez vos sorties ou ajoutez une sortie passée avec récupération automatique de la météo à cette date.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onInitPastRunDialog()
                        showAddPastRunDialog = true
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("add_past_run_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sortie passée", maxLines = 1, softWrap = false)
                }
            }
        }

        // Carte de Filtres (Période, Terrain, Conditions)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_filters_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Filtres",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Filtres (Période, Terrain, Conditions)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text("Période", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HistoryPeriodFilter.entries.forEach { pf ->
                            FilterChip(
                                selected = selectedPeriod == pf,
                                onClick = { selectedPeriod = pf },
                                label = { Text(pf.label) }
                            )
                        }
                    }

                    Text("Terrain", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HistoryTerrainFilter.entries.forEach { tf ->
                            FilterChip(
                                selected = selectedTerrain == tf,
                                onClick = { selectedTerrain = tf },
                                label = { Text(tf.label) },
                                modifier = Modifier.testTag("history_filter_terrain_${tf.name.lowercase()}")
                            )
                        }
                    }

                    Text("Conditions", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HistoryConditionFilter.entries.forEach { cf ->
                            FilterChip(
                                selected = selectedCondition == cf,
                                onClick = { selectedCondition = cf },
                                label = { Text(cf.label) },
                                modifier = Modifier.testTag("history_filter_cond_${cf.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        if (filteredRuns.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Aucune sortie ne correspond à ces filtres.",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            items(filteredRuns, key = { it.id }) { run ->
                HistoryRunCard(run = run, onDelete = { onDeleteEntry(run) })
            }
        }
    }

    if (showAddPastRunDialog) {
        AddPastRunDialog(
            draft = pastRunDraft,
            shoes = shoes,
            onUpdateParams = onUpdatePastRunParams,
            onSearchCityChange = onUpdatePastRunCitySearch,
            onSelectLocation = onSelectPastRunLocation,
            onRefreshWeather = onRefreshPastRunWeather,
            onDismiss = { showAddPastRunDialog = false },
            onSave = { distKm, dPlus, shoeId, addKm, outfit, gels, feeling, gelFb, adjustCarb, notes ->
                onSavePastRun(distKm, dPlus, shoeId, addKm, outfit, gels, feeling, gelFb, adjustCarb, notes)
                showAddPastRunDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AddPastRunDialog(
    draft: PastRunDraftState,
    shoes: List<ShoeEntity>,
    onUpdateParams: (Long, Int, Int, TerrainType, RunSessionType) -> Unit,
    onSearchCityChange: (String) -> Unit,
    onSelectLocation: (GeoLocationResult) -> Unit,
    onRefreshWeather: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Double, Int, Long?, Boolean, String, Int, ThermalFeeling, GelFeedback, Boolean, String) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var distanceText by remember { mutableStateOf("12.0") }
    var dPlusText by remember { mutableStateOf("200") }
    var selectedShoeId by remember { mutableStateOf<Long?>(null) }
    var addKmToShoe by remember { mutableStateOf(true) }
    var outfitText by remember { mutableStateOf(draft.suggestedOutfit) }
    var outfitEditedManually by remember { mutableStateOf(false) }
    var gelsCarriedText by remember { mutableStateOf("2") }
    var thermalFeeling by remember { mutableStateOf(ThermalFeeling.PARFAIT) }
    var gelFeedback by remember { mutableStateOf(GelFeedback.PILE) }
    var adjustCarbRate by remember { mutableStateOf(true) }
    var notesText by remember { mutableStateOf("") }

    // Met à jour la tenue suggérée automatiquement lorsque la météo historique change (sauf si déjà modifiée manuellement)
    LaunchedEffect(draft.suggestedOutfit) {
        if (!outfitEditedManually && draft.suggestedOutfit.isNotBlank()) {
            outfitText = draft.suggestedOutfit
        }
    }

    val pastWeather = draft.weather
    val mudLevel = pastWeather?.let {
        when {
            it.past72hRainMm < 5.0 -> MudLevel.SEC
            it.past72hRainMm <= 15.0 -> MudLevel.HUMIDE
            else -> MudLevel.BOUEUX
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Ajouter une sortie passée",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Choisissez la date, l'heure et le lieu : l'application récupère automatiquement la météo réelle et la pluie des 72 h précédentes à cette date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 1. Date & Heure de la sortie passée
                Text("1. Date & Créneau horaire", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("past_run_date_picker_button")
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Date : ${draft.dateFormatted}", maxLines = 1, softWrap = false)
                    }
                }

                // Raccourcis rapides de date passée
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val dayMs = 24L * 3600L * 1000L
                    listOf(
                        1 to "Hier",
                        2 to "Il y a 2 j",
                        7 to "Il y a 7 j",
                        14 to "Il y a 14 j"
                    ).forEach { (daysAgo, label) ->
                        AssistChip(
                            onClick = {
                                val target = System.currentTimeMillis() - daysAgo * dayMs
                                onUpdateParams(target, draft.startHour, draft.durationMinutes, draft.terrain, draft.sessionType)
                            },
                            label = { Text(label) }
                        )
                    }
                }

                // Heure de départ & Durée
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {
                            val h = (draft.startHour - 1 + 24) % 24
                            onUpdateParams(draft.dateEpochMillis, h, draft.durationMinutes, draft.terrain, draft.sessionType)
                        },
                        label = { Text("-1h") }
                    )
                    Text(
                        text = "Départ ${String.format("%02dh00", draft.startHour)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    AssistChip(
                        onClick = {
                            val h = (draft.startHour + 1) % 24
                            onUpdateParams(draft.dateEpochMillis, h, draft.durationMinutes, draft.terrain, draft.sessionType)
                        },
                        label = { Text("+1h") }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    AssistChip(
                        onClick = {
                            val d = (draft.durationMinutes - 15).coerceAtLeast(30)
                            onUpdateParams(draft.dateEpochMillis, draft.startHour, d, draft.terrain, draft.sessionType)
                        },
                        label = { Text("-15m") }
                    )
                    Text(
                        text = "${draft.durationMinutes}m",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    AssistChip(
                        onClick = {
                            val d = (draft.durationMinutes + 15).coerceAtMost(360)
                            onUpdateParams(draft.dateEpochMillis, draft.startHour, d, draft.terrain, draft.sessionType)
                        },
                        label = { Text("+15m") }
                    )
                }

                // 2. Lieu de la sortie passée
                Text("2. Lieu (${draft.locationName.ifBlank { "à choisir" }})", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = draft.citySearchQuery,
                    onValueChange = onSearchCityChange,
                    label = { Text("Changer de ville (Open-Meteo)") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (draft.isSearchingCity || draft.isLoadingWeather) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = onRefreshWeather) {
                                Icon(Icons.Default.Refresh, contentDescription = "Actualiser météo historique")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("past_run_city_search_input")
                )

                if (draft.citySearchResults.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            draft.citySearchResults.forEach { geo ->
                                Text(
                                    text = geo.displayLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectLocation(geo) }
                                        .padding(6.dp)
                                )
                            }
                        }
                    }
                }

                // 3. Encart Météo Historique récupérée
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("past_run_historical_weather_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = draft.weatherStatusMessage ?: "Météo historique (${draft.dateFormatted})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        if (pastWeather != null && mudLevel != null) {
                            Text(
                                text = "Réf ${pastWeather.referenceTempC}°C (réelle ${pastWeather.realTempC}°C) • Vent ${pastWeather.maxWindKmh.roundToInt()} km/h • Pluie créneau ${pastWeather.slotRainMm} mm • UV ${pastWeather.maxUvIndex}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Pluie 72 h avant sortie : ${pastWeather.past72hRainMm} mm → État terrain : ${mudLevel.label}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (!draft.isLoadingWeather) {
                            Text(
                                text = "Pas de météo fiable pour l'instant : l'enregistrement est bloqué tant qu'elle n'est pas récupérée.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 4. Terrain, Type, Distance & D+
                Text("3. Terrain, Séance & Distance", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TerrainType.entries.forEach { t ->
                        FilterChip(
                            selected = draft.terrain == t,
                            onClick = {
                                onUpdateParams(draft.dateEpochMillis, draft.startHour, draft.durationMinutes, t, draft.sessionType)
                            },
                            label = { Text(t.label) }
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RunSessionType.entries.forEach { st ->
                        FilterChip(
                            selected = draft.sessionType == st,
                            onClick = {
                                onUpdateParams(draft.dateEpochMillis, draft.startHour, draft.durationMinutes, draft.terrain, st)
                            },
                            label = { Text(st.label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = distanceText,
                        onValueChange = { distanceText = it },
                        label = { Text("Distance (km)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("past_run_distance_input")
                    )
                    OutlinedTextField(
                        value = dPlusText,
                        onValueChange = { dPlusText = it },
                        label = { Text("D+ (m)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(0.85f)
                    )
                }

                // 5. Chaussures portées
                if (shoes.isNotEmpty()) {
                    Text("4. Chaussures portées", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        shoes.forEach { shoe ->
                            FilterChip(
                                selected = selectedShoeId == shoe.id,
                                onClick = { selectedShoeId = shoe.id },
                                label = { Text("${shoe.name} (${shoe.currentKm.roundToInt()} km)") }
                            )
                        }
                        FilterChip(
                            selected = selectedShoeId == null,
                            onClick = { selectedShoeId = null },
                            label = { Text("Non renseignée") }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = addKmToShoe,
                            onCheckedChange = { addKmToShoe = it }
                        )
                        Text(
                            text = "Ajouter ces km au compteur d'usure de la paire",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // 6. Tenue portée
                Text("5. Tenue portée & Ressenti", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = outfitText,
                    onValueChange = {
                        outfitText = it
                        outfitEditedManually = true
                    },
                    label = { Text("Tenue portée ce jour-là") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("past_run_outfit_input")
                )

                Text("Ressenti thermique", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ThermalFeeling.entries.forEach { tf ->
                        FilterChip(
                            selected = thermalFeeling == tf,
                            onClick = { thermalFeeling = tf },
                            label = { Text("${tf.emoji} ${tf.label}") }
                        )
                    }
                }

                // 7. Retour Nutrition / Gels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = gelsCarriedText,
                        onValueChange = { gelsCarriedText = it },
                        label = { Text("Gels pris") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(105.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Retour nutrition", style = MaterialTheme.typography.labelSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            GelFeedback.entries.forEach { gf ->
                                FilterChip(
                                    selected = gelFeedback == gf,
                                    onClick = { gelFeedback = gf },
                                    label = { Text(gf.label) }
                                )
                            }
                        }
                    }
                }

                if (gelFeedback != GelFeedback.PILE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = adjustCarbRate,
                            onCheckedChange = { adjustCarbRate = it }
                        )
                        Text(
                            text = "Ajuster ma cible glucides/h (${if (gelFeedback.deltaCarbPerHour > 0) "+5" else "-5"} g/h)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Notes / sensations (optionnel)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val dist = distanceText.replace(",", ".").toDoubleOrNull() ?: 10.0
                    val dPlus = dPlusText.toIntOrNull() ?: 0
                    val gels = gelsCarriedText.toIntOrNull() ?: 0
                    onSave(
                        dist,
                        dPlus,
                        selectedShoeId,
                        addKmToShoe,
                        outfitText,
                        gels,
                        thermalFeeling,
                        gelFeedback,
                        adjustCarbRate,
                        notesText
                    )
                },
                enabled = draft.weather != null && !draft.isLoadingWeather,
                modifier = Modifier.testTag("save_past_run_confirm_button")
            ) {
                Text("Enregistrer la sortie")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = draft.dateEpochMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        val chosen = datePickerState.selectedDateMillis ?: draft.dateEpochMillis
                        onUpdateParams(
                            chosen.coerceAtMost(System.currentTimeMillis()),
                            draft.startHour,
                            draft.durationMinutes,
                            draft.terrain,
                            draft.sessionType
                        )
                        showDatePicker = false
                    }
                ) {
                    Text("Valider la date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Annuler")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun HistoryRunCard(
    run: RunHistoryEntity,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_item_card_${run.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${run.dateFormatted} • ${run.locationName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = buildString {
                            append("${run.terrain.label} • ${run.actualDistanceKm} km")
                            if (run.elevationGainDPlusMeters > 0) append(" • ${run.elevationGainDPlusMeters} m D+")
                            append(" (${run.durationMinutes} min) • ${run.sessionType.label}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "${run.thermalFeeling.emoji} ${run.thermalFeeling.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Supprimer cette sortie"
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "Météo créneau : Réf ${run.referenceTempC}°C (réelle ${run.realTempC}°C) • Vent ${run.windKmh.roundToInt()} km/h • Pluie ${run.rainMm} mm • UV ${run.maxUvIndex}" +
                    if (run.terrain == TerrainType.TRAIL) " • Boue : ${run.mudLevel.label} (${run.rain72hMm} mm/72h)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (run.gpxSurfaceSummary.isNotBlank()) {
                Text(
                    text = "Trace GPX & Revêtement : ${run.gpxSurfaceSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = "Tenue portée : ${run.outfitWorn}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Chaussures : ${run.shoeName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Gels : ${run.gelsCarried} (${run.gelFeedback.label})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (run.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "« ${run.notes} »",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
