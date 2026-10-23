package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.GpxColoredSegment
import com.example.data.model.GpxSurfaceBreakdown
import com.example.data.model.GpxTrackAnalysis
import com.example.data.model.SurfaceCategory
import com.example.ui.theme.AlertRed
import com.example.ui.theme.MudAmber
import kotlin.math.max
import kotlin.math.roundToInt

val ColorBitume = Color(0xFF3B82F6)         // Bleu Acier (Bitume)
val ColorCheminStabilise = Color(0xFFF59E0B) // Ambre / Ocre (Chemin stabilisé)
val ColorTerreForet = Color(0xFF10B981)     // Vert Forêt (Terre / forêt)
val ColorInconnu = Color(0xFF94A3B8)        // Gris Ardoise (Inconnu)

fun colorForSurface(category: SurfaceCategory): Color = when (category) {
    SurfaceCategory.BITUME -> ColorBitume
    SurfaceCategory.CHEMIN_STABILISE -> ColorCheminStabilise
    SurfaceCategory.TERRE_FORET -> ColorTerreForet
    SurfaceCategory.INCONNU -> ColorInconnu
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GpxImportBarSection(
    gpxTrack: GpxTrackAnalysis?,
    onPickGpxFileClick: () -> Unit,
    onLoadSampleTrailGpx: () -> Unit,
    onLoadSampleRoadGpx: () -> Unit,
    onClearGpx: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gpx_import_section")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = "Trace GPX optionnelle",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = if (gpxTrack == null) "Trace GPX (Optionnel)" else "Trace GPX : ${gpxTrack.trackName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (gpxTrack == null) {
                                "Importez un fichier .gpx pour pré-remplir distance, D+, lieu météo et analyser le revêtement (Overpass)."
                            } else {
                                "${gpxTrack.totalDistanceKm} km • ${gpxTrack.elevationGainDPlusMeters} m D+ • Départ (${String.format("%.3f", gpxTrack.startLat)}, ${String.format("%.3f", gpxTrack.startLon)})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (gpxTrack != null) {
                    IconButton(
                        onClick = onClearGpx,
                        modifier = Modifier.testTag("clear_gpx_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Retirer la trace GPX"
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPickGpxFileClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("import_gpx_file_button")
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Importer une trace GPX", maxLines = 1, softWrap = false)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AssistChip(
                    onClick = onLoadSampleTrailGpx,
                    label = { Text("Exemple GPX Sentier (Chamonix)") },
                    modifier = Modifier.testTag("sample_trail_gpx_chip")
                )
                AssistChip(
                    onClick = onLoadSampleRoadGpx,
                    label = { Text("Exemple GPX >60% Bitume (Annecy)") },
                    modifier = Modifier.testTag("sample_road_gpx_chip")
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GpxSurfaceAnalysisCard(
    gpxTrack: GpxTrackAnalysis,
    asphaltThresholdPercent: Int,
    onRetryAnalysis: () -> Unit = {},
    onApplyCustomBreakdown: (Double, Double, Double) -> Unit = { _, _, _ -> }
) {
    val breakdown = gpxTrack.surfaceBreakdown
    var showAdjustDialog by remember { mutableStateOf(false) }
    val previewSegments = remember(gpxTrack.trackIdHash) {
        com.example.data.remote.GpxAndOverpassAnalyzer.buildPreviewSegments(gpxTrack.points)
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .testTag("gpx_surface_analysis_card"),
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
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "Analyse du revêtement GPX",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = if (breakdown?.isManualOrOfflineEstimate == true && breakdown.overpassError == null) {
                                "Revêtement personnalisé"
                            } else if (breakdown?.isManualOrOfflineEstimate == true) {
                                "Revêtement estimé (Hors-ligne)"
                            } else {
                                "Analyse du revêtement (Overpass OSM)"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${gpxTrack.trackName} • ${gpxTrack.totalDistanceKm} km • ${gpxTrack.elevationGainDPlusMeters} m D+ • Seuil route : $asphaltThresholdPercent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (gpxTrack.isAnalyzingSurface) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAdjustDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("adjust_surface_button")
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ajuster", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = onRetryAnalysis,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("retry_overpass_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Réessayer", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            if (gpxTrack.isAnalyzingSurface && breakdown == null) {
                Text(
                    text = "Interrogation de l'API Overpass (OpenStreetMap, corridor + 50 m, rayon segment 20 m)…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ColoredGpxTrackMapCanvas(
                    segments = previewSegments,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F172A))
                        .testTag("gpx_colored_map_canvas")
                )
            } else if (breakdown != null) {
                // Message d'information ou d'erreur Overpass
                if (breakdown.overpassError != null) {
                    val isOfflineEst = breakdown.isManualOrOfflineEstimate
                    val bannerBg = if (isOfflineEst) MudAmber.copy(alpha = 0.14f) else AlertRed.copy(alpha = 0.14f)
                    val iconTint = if (isOfflineEst) MudAmber else AlertRed

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = bannerBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overpass_error_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = "Statut Overpass",
                                    tint = iconTint
                                )
                                Text(
                                    text = breakdown.overpassError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { showAdjustDialog = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Ajuster manuellement")
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = onRetryAnalysis,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Réessayer Overpass", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                } else if (breakdown.isUnreliable) {
                    // Si la part « inconnu » dépasse 30 % : afficher « analyse peu fiable »
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AlertRed.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gpx_unreliable_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "Analyse peu fiable",
                                tint = AlertRed
                            )
                            Text(
                                text = "Analyse peu fiable (part « inconnu » = ${breakdown.inconnuPercent}% > 30 %) — Application des règles actuelles.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AlertRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Barre colorée de répartition en %
                SurfaceStackedBar(breakdown = breakdown)

                // Légende détaillée avec les %
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SurfaceLegendItem(
                        color = ColorBitume,
                        label = "Bitume",
                        percent = breakdown.bitumePercent
                    )
                    SurfaceLegendItem(
                        color = ColorCheminStabilise,
                        label = "Chemin stabilisé",
                        percent = breakdown.cheminStabilisePercent
                    )
                    SurfaceLegendItem(
                        color = ColorTerreForet,
                        label = "Terre / forêt",
                        percent = breakdown.terreForetPercent
                    )
                    SurfaceLegendItem(
                        color = ColorInconnu,
                        label = "Inconnu (non bitumé)",
                        percent = breakdown.inconnuPercent
                    )
                }

                HorizontalDivider()

                // Carte de la trace colorée par catégorie
                Text(
                    text = "Carte de la trace colorée par revêtement",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                ColoredGpxTrackMapCanvas(
                    segments = breakdown.coloredSegments,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F172A))
                        .testTag("gpx_colored_map_canvas")
                )

                // Profil altimétrique GPX lorsque les points contiennent l'altitude <ele>
                val elevations = gpxTrack.points.mapNotNull { it.ele }
                if (elevations.size >= 3) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Profil altimétrique GPX (+${gpxTrack.elevationGainDPlusMeters} m D+)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (gpxTrack.minElevationMeters != null && gpxTrack.maxElevationMeters != null) {
                            Text(
                                text = "Alt. min ${gpxTrack.minElevationMeters} m • Sommet ${gpxTrack.maxElevationMeters} m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    GpxElevationProfileCanvas(
                        elevations = elevations,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A))
                            .testTag("gpx_elevation_profile_canvas")
                    )
                }
            }
        }
    }

    if (showAdjustDialog && breakdown != null) {
        ManualSurfaceAdjustDialog(
            initialBitumePct = breakdown.bitumePercent,
            initialCheminPct = breakdown.cheminStabilisePercent,
            initialTerrePct = breakdown.terreForetPercent,
            onDismiss = { showAdjustDialog = false },
            onApply = { b, c, t ->
                onApplyCustomBreakdown(b, c, t)
                showAdjustDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualSurfaceAdjustDialog(
    initialBitumePct: Double,
    initialCheminPct: Double,
    initialTerrePct: Double,
    onDismiss: () -> Unit,
    onApply: (Double, Double, Double) -> Unit
) {
    var bitumePct by remember { mutableStateOf(initialBitumePct.coerceIn(0.0, 100.0)) }
    var cheminPct by remember { mutableStateOf(initialCheminPct.coerceIn(0.0, 100.0)) }
    var terrePct by remember { mutableStateOf(initialTerrePct.coerceIn(0.0, 100.0)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Ajuster le revêtement", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Sélectionnez un profil type ou réglez les proportions pour adapter la recommandation de chaussures :",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Profils rapides
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = bitumePct >= 70.0,
                        onClick = {
                            bitumePct = 85.0
                            cheminPct = 15.0
                            terrePct = 0.0
                        },
                        label = { Text("Route (85% Bitume)") }
                    )
                    FilterChip(
                        selected = cheminPct >= 50.0,
                        onClick = {
                            bitumePct = 15.0
                            cheminPct = 65.0
                            terrePct = 20.0
                        },
                        label = { Text("Chemins (65%)") }
                    )
                    FilterChip(
                        selected = terrePct >= 60.0,
                        onClick = {
                            bitumePct = 5.0
                            cheminPct = 20.0
                            terrePct = 75.0
                        },
                        label = { Text("Trail / Forêt (75%)") }
                    )
                }

                HorizontalDivider()

                // Slider Bitume
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Bitume / Route", style = MaterialTheme.typography.labelMedium, color = ColorBitume, fontWeight = FontWeight.Bold)
                        Text("${bitumePct.roundToInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = bitumePct.toFloat(),
                        onValueChange = { bitumePct = it.toDouble().coerceIn(0.0, 100.0) },
                        valueRange = 0f..100f
                    )
                }

                // Slider Chemin stabilisé
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Chemin stabilisé / Gravier", style = MaterialTheme.typography.labelMedium, color = ColorCheminStabilise, fontWeight = FontWeight.Bold)
                        Text("${cheminPct.roundToInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = cheminPct.toFloat(),
                        onValueChange = { cheminPct = it.toDouble().coerceIn(0.0, 100.0) },
                        valueRange = 0f..100f
                    )
                }

                // Slider Terre / Forêt
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Terre / Forêt / Sentier", style = MaterialTheme.typography.labelMedium, color = ColorTerreForet, fontWeight = FontWeight.Bold)
                        Text("${terrePct.roundToInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = terrePct.toFloat(),
                        onValueChange = { terrePct = it.toDouble().coerceIn(0.0, 100.0) },
                        valueRange = 0f..100f
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val total = (bitumePct + cheminPct + terrePct).coerceAtLeast(1.0)
                    val normB = (bitumePct / total) * 100.0
                    val normC = (cheminPct / total) * 100.0
                    val normT = (terrePct / total) * 100.0
                    onApply(normB, normC, normT)
                }
            ) {
                Text("Appliquer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}

@Composable
private fun GpxElevationProfileCanvas(
    elevations: List<Double>,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (elevations.size < 2) return@Canvas
            val minEle = elevations.minOrNull() ?: 0.0
            val maxEle = elevations.maxOrNull() ?: 1.0
            val eleSpan = (maxEle - minEle).coerceAtLeast(10.0)
            val padX = 12.dp.toPx()
            val padY = 14.dp.toPx()
            val w = (size.width - 2 * padX).coerceAtLeast(10f)
            val h = (size.height - 2 * padY).coerceAtLeast(10f)

            val step = (elevations.size / 120).coerceAtLeast(1)
            val sampled = elevations.filterIndexed { index, _ -> index % step == 0 || index == elevations.lastIndex }

            for (i in 0 until sampled.lastIndex) {
                val x1 = padX + (i.toFloat() / sampled.lastIndex.coerceAtLeast(1)) * w
                val y1 = padY + (1f - ((sampled[i] - minEle) / eleSpan).toFloat().coerceIn(0f, 1f)) * h
                val x2 = padX + ((i + 1).toFloat() / sampled.lastIndex.coerceAtLeast(1)) * w
                val y2 = padY + (1f - ((sampled[i + 1] - minEle) / eleSpan).toFloat().coerceIn(0f, 1f)) * h

                // Remplissage discret sous la courbe
                drawLine(
                    color = Color(0xFF10B981).copy(alpha = 0.22f),
                    start = Offset((x1 + x2) / 2f, (y1 + y2) / 2f),
                    end = Offset((x1 + x2) / 2f, size.height - 4.dp.toPx()),
                    strokeWidth = (w / sampled.size.coerceAtLeast(1)).coerceAtLeast(2f)
                )
                drawLine(
                    color = Color(0xFF10B981),
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun SurfaceStackedBar(breakdown: GpxSurfaceBreakdown) {
    val bWeight = max(0.01f, breakdown.bitumePercent.toFloat())
    val sWeight = max(0.01f, breakdown.cheminStabilisePercent.toFloat())
    val tWeight = max(0.01f, breakdown.terreForetPercent.toFloat())
    val uWeight = max(0.01f, breakdown.inconnuPercent.toFloat())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .testTag("gpx_surface_stacked_bar")
    ) {
        if (breakdown.bitumePercent > 0.0) {
            Box(
                modifier = Modifier
                    .weight(bWeight)
                    .fillMaxHeight()
                    .background(ColorBitume)
            )
        }
        if (breakdown.cheminStabilisePercent > 0.0) {
            Box(
                modifier = Modifier
                    .weight(sWeight)
                    .fillMaxHeight()
                    .background(ColorCheminStabilise)
            )
        }
        if (breakdown.terreForetPercent > 0.0) {
            Box(
                modifier = Modifier
                    .weight(tWeight)
                    .fillMaxHeight()
                    .background(ColorTerreForet)
            )
        }
        if (breakdown.inconnuPercent > 0.0 || (breakdown.bitumePercent + breakdown.cheminStabilisePercent + breakdown.terreForetPercent == 0.0)) {
            Box(
                modifier = Modifier
                    .weight(uWeight)
                    .fillMaxHeight()
                    .background(ColorInconnu)
            )
        }
    }
}

@Composable
private fun SurfaceLegendItem(
    color: Color,
    label: String,
    percent: Double
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = "$label : $percent%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ColoredGpxTrackMapCanvas(
    segments: List<GpxColoredSegment>,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (segments.isEmpty()) return@Canvas

            val allLats = segments.flatMap { listOf(it.startLat, it.endLat) }
            val allLons = segments.flatMap { listOf(it.startLon, it.endLon) }
            val minLat = allLats.minOrNull() ?: 0.0
            val maxLat = allLats.maxOrNull() ?: 1.0
            val minLon = allLons.minOrNull() ?: 0.0
            val maxLon = allLons.maxOrNull() ?: 1.0

            val latSpan = (maxLat - minLat).coerceAtLeast(1e-5)
            val lonSpan = (maxLon - minLon).coerceAtLeast(1e-5)

            val pad = 24.dp.toPx()
            val drawW = (size.width - 2 * pad).coerceAtLeast(10f)
            val drawH = (size.height - 2 * pad).coerceAtLeast(10f)

            fun toScreenOffset(lat: Double, lon: Double): Offset {
                val xRatio = ((lon - minLon) / lonSpan).toFloat().coerceIn(0f, 1f)
                val yRatio = (1.0 - (lat - minLat) / latSpan).toFloat().coerceIn(0f, 1f)
                return Offset(
                    x = pad + xRatio * drawW,
                    y = pad + yRatio * drawH
                )
            }

            // Grille topographique discrète en arrière-plan
            val gridColor = Color.White.copy(alpha = 0.06f)
            for (i in 1..3) {
                val y = size.height * (i / 4f)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                val x = size.width * (i / 4f)
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }

            // Dessin de chaque segment coloré selon sa catégorie de revêtement
            segments.forEach { seg ->
                val startPt = toScreenOffset(seg.startLat, seg.startLon)
                val endPt = toScreenOffset(seg.endLat, seg.endLon)
                drawLine(
                    color = colorForSurface(seg.category),
                    start = startPt,
                    end = endPt,
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Marqueur Départ (vert) et Arrivée (orange)
            val first = segments.first()
            val last = segments.last()
            val startOffset = toScreenOffset(first.startLat, first.startLon)
            val endOffset = toScreenOffset(last.endLat, last.endLon)

            drawCircle(
                color = Color.White,
                radius = 7.dp.toPx(),
                center = startOffset
            )
            drawCircle(
                color = Color(0xFF22C55E),
                radius = 5.dp.toPx(),
                center = startOffset
            )

            drawCircle(
                color = Color.White,
                radius = 7.dp.toPx(),
                center = endOffset
            )
            drawCircle(
                color = Color(0xFFFF5722),
                radius = 5.dp.toPx(),
                center = endOffset
            )
        }

        // Légende Départ / Arrivée en bas à droite de la carte
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                Text("Départ", style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF5722)))
                Text("Arrivée", style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
        }
    }
}
