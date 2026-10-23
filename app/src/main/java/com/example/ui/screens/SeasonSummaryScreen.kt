package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.example.data.local.RunHistoryEntity
import com.example.data.model.MudLevel
import com.example.data.model.TerrainType
import com.example.data.model.ThermalFeeling
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class SummaryGroupingMode(val label: String) {
    PAR_SAISON("Par saison"),
    PAR_MOIS("Par mois")
}

data class PeriodSummaryStats(
    val periodLabel: String,
    val runsCount: Int,
    val totalKm: Double,
    val totalDPlusMeters: Int,
    val totalEffortKm: Double,
    val comfortPercent: Int,
    val kmByShoe: Map<String, Double>,
    val coldRunsCount: Int,
    val rainRunsCount: Int,
    val mudRunsCount: Int,
    val dominantFeeling: ThermalFeeling
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeasonSummaryScreen(
    history: List<RunHistoryEntity>
) {
    var groupingMode by remember { mutableStateOf(SummaryGroupingMode.PAR_SAISON) }

    val summaries = remember(history, groupingMode) {
        buildSummaries(history, groupingMode)
    }

    val globalTotalKm = history.sumOf { it.actualDistanceKm }
    val globalTotalDPlus = history.sumOf { it.elevationGainDPlusMeters }
    val globalEffortKm = globalTotalKm + (globalTotalDPlus / 100.0)
    val globalPerfectCount = history.count { it.thermalFeeling == ThermalFeeling.PARFAIT }
    val globalComfortPct = if (history.isNotEmpty()) ((globalPerfectCount * 100.0) / history.size).roundToInt() else 100
    val globalCold = history.count { it.realTempC < 8.0 || it.referenceTempC < 8.0 }
    val globalRain = history.count { it.rainMm >= 0.5 }
    val globalMud = history.count { it.terrain == TerrainType.TRAIL && it.mudLevel != MudLevel.SEC }
    val globalDominantFeeling = history
        .groupingBy { it.thermalFeeling }
        .eachCount()
        .maxByOrNull { it.value }?.key ?: ThermalFeeling.PARFAIT

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("season_summary_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Bilan de saison & mensuel",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Synthèse de vos sorties, kilométrage total, km par paire de chaussures, répartition des conditions (froid, pluie, boue) et ressenti dominant.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Vue Globale Cumulée
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("global_season_summary_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "TOTAL CUMULÉ",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatMetricBox(
                            value = "${history.size}",
                            label = "Sorties",
                            onPrimary = true
                        )
                        StatMetricBox(
                            value = "${(globalTotalKm * 10.0).roundToInt() / 10.0} km",
                            label = "Km totaux",
                            onPrimary = true
                        )
                        StatMetricBox(
                            value = "${globalDominantFeeling.emoji} ${globalDominantFeeling.label}",
                            label = "Ressenti dominant",
                            onPrimary = true
                        )
                    }
                    Text(
                        text = "Cumul D+ : +$globalTotalDPlus m • Charge : ${(globalEffortKm * 10.0).roundToInt() / 10.0} km-effort • Confort thermique : $globalComfortPct % « Parfait »",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Conditions affrontées : Froid ($globalCold) • Pluie ($globalRain) • Boue ($globalMud)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Sélecteur Par saison / Par mois
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SummaryGroupingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = groupingMode == mode,
                        onClick = { groupingMode = mode },
                        label = { Text(mode.label, fontWeight = FontWeight.Bold) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (mode == SummaryGroupingMode.PAR_SAISON) Icons.Default.WbSunny else Icons.Default.CalendarMonth,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.testTag("summary_mode_${mode.name.lowercase()}")
                    )
                }
            }
        }

        // Cartes détaillées par période (Saison ou Mois)
        summaries.forEach { summary ->
            item {
                PeriodSummaryCard(summary = summary)
            }
        }
    }
}

@Composable
private fun StatMetricBox(
    value: String,
    label: String,
    onPrimary: Boolean = false
) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (onPrimary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (onPrimary) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PeriodSummaryCard(summary: PeriodSummaryStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                        imageVector = Icons.Default.Assessment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = summary.periodLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "${summary.dominantFeeling.emoji} ${summary.dominantFeeling.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${summary.runsCount} sortie(s) • +${summary.totalDPlusMeters} m D+",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${(summary.totalKm * 10.0).roundToInt() / 10.0} km (${(summary.totalEffortKm * 10.0).roundToInt() / 10.0} km-eff.)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider()

            // Km par paire de chaussures
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Km par paire de chaussures",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            summary.kmByShoe.forEach { (shoeName, km) ->
                val ratio = if (summary.totalKm > 0) (km / summary.totalKm).toFloat().coerceIn(0f, 1f) else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = shoeName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${(km * 10.0).roundToInt() / 10.0} km (${(ratio * 100).roundToInt()}%)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }

            HorizontalDivider()

            // Répartition des conditions (froid, pluie, boue)
            Text(
                text = "Répartition des conditions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ConditionChipStat("Froid (< 8°C)", summary.coldRunsCount, summary.runsCount)
                ConditionChipStat("Pluie", summary.rainRunsCount, summary.runsCount)
                ConditionChipStat("Boue (Trail)", summary.mudRunsCount, summary.runsCount)
            }
        }
    }
}

@Composable
private fun ConditionChipStat(label: String, count: Int, total: Int) {
    val pct = if (total > 0) ((count * 100.0) / total).roundToInt() else 0
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$count ($pct%)",
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
}

private fun buildSummaries(
    history: List<RunHistoryEntity>,
    mode: SummaryGroupingMode
): List<PeriodSummaryStats> {
    if (history.isEmpty()) return emptyList()

    val grouped = history.groupBy { run ->
        val cal = Calendar.getInstance().apply { timeInMillis = run.dateEpochMillis }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) // 0..11
        when (mode) {
            SummaryGroupingMode.PAR_MOIS -> {
                val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.FRENCH)
                monthFormat.format(Date(run.dateEpochMillis)).replaceFirstChar { it.uppercase() }
            }
            SummaryGroupingMode.PAR_SAISON -> {
                val season = when (month) {
                    Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> "Hiver"
                    Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> "Printemps"
                    Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> "Été"
                    else -> "Automne"
                }
                "$season $year"
            }
        }
    }

    return grouped.map { (periodLabel, runs) ->
        val totalKm = runs.sumOf { it.actualDistanceKm }
        val totalDPlus = runs.sumOf { it.elevationGainDPlusMeters }
        val totalEffortKm = totalKm + (totalDPlus / 100.0)
        val perfectCount = runs.count { it.thermalFeeling == ThermalFeeling.PARFAIT }
        val comfortPct = if (runs.isNotEmpty()) ((perfectCount * 100.0) / runs.size).roundToInt() else 100
        val kmByShoe = runs
            .groupBy { it.shoeName }
            .mapValues { (_, list) -> list.sumOf { it.actualDistanceKm } }

        val coldCount = runs.count { it.realTempC < 8.0 || it.referenceTempC < 8.0 }
        val rainCount = runs.count { it.rainMm >= 0.5 }
        val mudCount = runs.count { it.terrain == TerrainType.TRAIL && it.mudLevel != MudLevel.SEC }
        val dominantFeeling = runs
            .groupingBy { it.thermalFeeling }
            .eachCount()
            .maxByOrNull { it.value }?.key ?: ThermalFeeling.PARFAIT

        PeriodSummaryStats(
            periodLabel = periodLabel,
            runsCount = runs.size,
            totalKm = totalKm,
            totalDPlusMeters = totalDPlus,
            totalEffortKm = totalEffortKm,
            comfortPercent = comfortPct,
            kmByShoe = kmByShoe,
            coldRunsCount = coldCount,
            rainRunsCount = rainCount,
            mudRunsCount = mudCount,
            dominantFeeling = dominantFeeling
        )
    }
}
