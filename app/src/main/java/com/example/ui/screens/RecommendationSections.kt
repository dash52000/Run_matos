package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.ChecklistItemEntity
import com.example.data.local.RunHistoryEntity
import com.example.data.local.ShoeEntity
import com.example.data.model.ChecklistType
import com.example.data.model.GelFeedback
import com.example.data.model.NutritionPhaseType
import com.example.data.model.NutritionTimelineStep
import com.example.data.model.RetroplanningConfig
import com.example.data.model.RetroplanningStep
import com.example.data.model.ThermalFeeling
import com.example.domain.FullRunRecommendation
import kotlin.math.roundToInt

@Composable
fun SimilarRunsCard(
    similarRuns: List<RunHistoryEntity>,
    currentRefTemp: Double
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("similar_runs_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .testTag("toggle_similar_runs_button"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Sorties similaires",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Sorties similaires (±2 °C)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Réf actuelle ${currentRefTemp}°C • ${similarRuns.size} sortie(s) proche(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "${similarRuns.size}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Replier" else "Déplier",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    if (similarRuns.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Aucune sortie passée à ±2 °C avec vent/pluie comparables. Enregistrez votre retour après sortie pour enrichir votre mémoire thermique !",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        similarRuns.forEachIndexed { idx, run ->
                            if (idx > 0) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${run.dateFormatted} • ${run.locationName}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = "${run.thermalFeeling.emoji} ${run.thermalFeeling.label}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Réf ${run.referenceTempC}°C (réel ${run.realTempC}°C) • Vent ${run.windKmh.roundToInt()} km/h • Pluie ${run.rainMm} mm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Tenue portée : ${run.outfitWorn}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Chaussures : ${run.shoeName} • Gels : ${run.gelsCarried} (${run.gelFeedback.label})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NutritionTimelineCard(
    steps: List<NutritionTimelineStep>
) {
    if (steps.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nutrition_timeline_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .testTag("toggle_nutrition_timeline_button"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = "Frise chronologique nutrition",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Frise Nutrition : Avant & Après Sortie",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${steps.size} étapes clés (avant-départ, pendant, récup & repas)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${steps.size} étapes",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Replier" else "Déplier",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    steps.forEachIndexed { idx, step ->
                        val isLast = idx == steps.lastIndex
                        val phaseAccent = when (step.phase) {
                            NutritionPhaseType.VEILLE_SOIR -> MaterialTheme.colorScheme.tertiary
                            NutritionPhaseType.AVANT_DEPART -> MaterialTheme.colorScheme.primary
                            NutritionPhaseType.PENDANT_SORTIE -> MaterialTheme.colorScheme.secondary
                            NutritionPhaseType.RECUP_IMMEDIATE -> androidx.compose.ui.graphics.Color(0xFF10B981)
                            NutritionPhaseType.REPAS_APRES -> androidx.compose.ui.graphics.Color(0xFF3B82F6)
                        }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nutrition_timeline_step_${step.id}"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Colonne de gauche : pastille + ligne verticale de frise chronologique
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(phaseAccent)
                        )
                        if (!isLast) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(94.dp)
                                    .background(phaseAccent.copy(alpha = 0.35f))
                            )
                        }
                    }

                    // Contenu de l'étape nutritionnelle
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = if (isLast) 0.dp else 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = phaseAccent.copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        text = step.timingLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = phaseAccent,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Text(
                                    text = step.phase.badgeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Text(
                                text = step.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "Aliments : ${step.foodTypesAdvice}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = "Hydratation : ${step.hydrationAdvice}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
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

@Composable
fun ChecklistCard(
    title: String,
    subtitle: String,
    isPostRaceBag: Boolean,
    hasAutoWarmJacket: Boolean,
    items: List<ChecklistItemEntity>,
    onToggleItem: (ChecklistItemEntity) -> Unit,
    onAddItem: (String) -> Unit,
    onDeleteItem: (ChecklistItemEntity) -> Unit,
    onResetChecklist: (() -> Unit)? = null
) {
    var newItemText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(true) }
    val checkedCount = items.count { it.id < 0 || it.checked }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (isPostRaceBag) "post_race_bag_card" else "before_start_checklist_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isPostRaceBag) Icons.Default.Backpack else Icons.Default.Checklist,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "$title ($checkedCount/${items.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onResetChecklist != null) {
                        IconButton(
                            onClick = onResetChecklist,
                            modifier = Modifier.testTag("reset_checklist_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Remettre à zéro la check-list"
                            )
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Replier" else "Déplier",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    if (isPostRaceBag && hasAutoWarmJacket) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Température < 10 °C : une veste chaude a été ajoutée automatiquement au sac d'après-course.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    items.forEach { item ->
                        val isAutoItem = item.id < 0
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isAutoItem) { onToggleItem(item) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = if (isAutoItem) true else item.checked,
                                onCheckedChange = { if (!isAutoItem) onToggleItem(item) }
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isAutoItem) FontWeight.Bold else FontWeight.Normal,
                                color = if (isAutoItem) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (!isAutoItem) {
                                IconButton(onClick = { onDeleteItem(item) }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Supprimer ${item.label}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newItemText,
                            onValueChange = { newItemText = it },
                            placeholder = { Text("Ajouter un élément personnalisé…") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(if (isPostRaceBag) "add_post_race_input" else "add_checklist_input")
                        )
                        Button(
                            onClick = {
                                if (newItemText.isNotBlank()) {
                                    onAddItem(newItemText)
                                    newItemText = ""
                                }
                            },
                            modifier = Modifier.testTag(if (isPostRaceBag) "add_post_race_button" else "add_checklist_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Ajouter")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RetroplanningCard(
    config: RetroplanningConfig,
    steps: List<RetroplanningStep>,
    onUpdateConfig: (Int, Int, Int, Int, Int, Int) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("retroplanning_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = "Rétroplanning Course",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Rétroplanning Course",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Départ ${String.format("%02dh%02d", config.raceStartHour, config.raceStartMinute)} • Trajet ${config.travelMinutes} min • Marge ${config.onSiteMarginMinutes} min",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = { showEditDialog = true },
                    modifier = Modifier.testTag("edit_retroplanning_button")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Modifier les durées du rétroplanning")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            steps.forEachIndexed { idx, step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (idx == steps.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.width(68.dp)
                    ) {
                        Text(
                            text = step.timeFormatted,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (idx == steps.lastIndex) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = step.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showEditDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Ajuster durées")
            }
        }
    }

    if (showEditDialog) {
        RetroplanningEditDialog(
            config = config,
            onDismiss = { showEditDialog = false },
            onSave = { h, m, travel, margin, breakfast, wakeup ->
                onUpdateConfig(h, m, travel, margin, breakfast, wakeup)
                showEditDialog = false
            }
        )
    }
}

@Composable
private fun RetroplanningEditDialog(
    config: RetroplanningConfig,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int, Int, Int, Int) -> Unit
) {
    var startHour by remember { mutableStateOf(config.raceStartHour.toString()) }
    var startMin by remember { mutableStateOf(config.raceStartMinute.toString()) }
    var travelMin by remember { mutableStateOf(config.travelMinutes.toString()) }
    var marginMin by remember { mutableStateOf(config.onSiteMarginMinutes.toString()) }
    var breakfastMin by remember { mutableStateOf(config.breakfastBeforeStartMinutes.toString()) }
    var wakeUpMin by remember { mutableStateOf(config.wakeUpBeforeBreakfastMinutes.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Personnaliser le rétroplanning") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startHour,
                        onValueChange = { startHour = it },
                        label = { Text("Heure départ (0-23)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = startMin,
                        onValueChange = { startMin = it },
                        label = { Text("Minutes (0-59)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = travelMin,
                    onValueChange = { travelMin = it },
                    label = { Text("Temps de trajet (min)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = marginMin,
                    onValueChange = { marginMin = it },
                    label = { Text("Marge sur place (min, déf. 45)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = breakfastMin,
                    onValueChange = { breakfastMin = it },
                    label = { Text("Petit-déjeuner avant départ (min, déf. 180 = 3h)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = wakeUpMin,
                    onValueChange = { wakeUpMin = it },
                    label = { Text("Réveil avant petit-déjeuner (min, déf. 30)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        startHour.toIntOrNull() ?: config.raceStartHour,
                        startMin.toIntOrNull() ?: config.raceStartMinute,
                        travelMin.toIntOrNull() ?: config.travelMinutes,
                        marginMin.toIntOrNull() ?: 45,
                        breakfastMin.toIntOrNull() ?: 180,
                        wakeUpMin.toIntOrNull() ?: 30
                    )
                }
            ) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostRunFeedbackDialog(
    recommendation: FullRunRecommendation,
    shoes: List<ShoeEntity>,
    onDismiss: () -> Unit,
    onConfirm: (
        actualDistanceKm: Double,
        actualDPlusMeters: Int,
        thermalFeeling: ThermalFeeling,
        gelFeedback: GelFeedback,
        gelsCarried: Int,
        outfitText: String,
        notes: String,
        wornShoeId: Long?
    ) -> Unit
) {
    var wornShoeId by remember { mutableStateOf(recommendation.selectedShoe?.id) }
    var actualDistanceText by remember { mutableStateOf(recommendation.plannedDistanceKm.toString()) }
    var actualDPlusText by remember { mutableStateOf(recommendation.elevationGainDPlusMeters.toString()) }
    var selectedFeeling by remember { mutableStateOf(ThermalFeeling.PARFAIT) }
    var selectedGelFeedback by remember { mutableStateOf(GelFeedback.PILE) }
    var gelsCarriedText by remember { mutableStateOf(recommendation.nutrition.recommendedGelsCount.toString()) }
    var outfitText by remember {
        mutableStateOf((recommendation.outfitItems + recommendation.accessoriesItems).joinToString(", "))
    }
    var notesText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Retour après sortie",
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
                    text = "Les km réels seront ajoutés à la paire réellement portée, et ton retour sur les gels ajustera ta cible glucidique (±5 g/h).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (shoes.isNotEmpty()) {
                    Text(
                        text = "Chaussures portées",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        shoes.forEach { shoe ->
                            FilterChip(
                                selected = wornShoeId == shoe.id,
                                onClick = { wornShoeId = shoe.id },
                                label = { Text("${shoe.name} (${shoe.currentKm.roundToInt()} km)") },
                                modifier = Modifier.testTag("post_run_shoe_${shoe.id}")
                            )
                        }
                        FilterChip(
                            selected = wornShoeId == null,
                            onClick = { wornShoeId = null },
                            label = { Text("Non renseignée") }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = actualDistanceText,
                        onValueChange = { actualDistanceText = it },
                        label = { Text("Distance réelle (km)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("post_run_actual_distance_input")
                    )

                    OutlinedTextField(
                        value = actualDPlusText,
                        onValueChange = { actualDPlusText = it },
                        label = { Text("D+ réel (m)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(0.85f)
                            .testTag("post_run_actual_dplus_input")
                    )
                }

                Text(
                    text = "Ressenti thermique",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ThermalFeeling.entries.forEach { feeling ->
                        FilterChip(
                            selected = selectedFeeling == feeling,
                            onClick = { selectedFeeling = feeling },
                            label = { Text("${feeling.emoji} ${feeling.label}") }
                        )
                    }
                }

                Text(
                    text = "Retour nutrition (Gels : Pas assez +5 g/h • Pile 0 • Trop -5 g/h)",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GelFeedback.entries.forEach { fb ->
                        FilterChip(
                            selected = selectedGelFeedback == fb,
                            onClick = { selectedGelFeedback = fb },
                            label = { Text(fb.label) },
                            modifier = Modifier.testTag("gel_feedback_${fb.name.lowercase()}")
                        )
                    }
                }

                OutlinedTextField(
                    value = gelsCarriedText,
                    onValueChange = { gelsCarriedText = it },
                    label = { Text("Nombre de gels emportés") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = outfitText,
                    onValueChange = { outfitText = it },
                    label = { Text("Tenue réellement portée") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Notes / sensations") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val dist = actualDistanceText.replace(",", ".").toDoubleOrNull()
                        ?: recommendation.plannedDistanceKm
                    val dPlus = actualDPlusText.toIntOrNull()
                        ?: recommendation.elevationGainDPlusMeters
                    val gels = gelsCarriedText.toIntOrNull()
                        ?: recommendation.nutrition.recommendedGelsCount
                    onConfirm(dist, dPlus, selectedFeeling, selectedGelFeedback, gels, outfitText, notesText, wornShoeId)
                },
                modifier = Modifier.testTag("confirm_post_run_button")
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Valider la sortie")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
