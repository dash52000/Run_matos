package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.ChecklistItemEntity
import com.example.domain.FullRunRecommendation
import com.example.domain.RecommendationEngine
import com.example.ui.NewRunFormState

@Composable
fun SafetyAndShareCard(
    formState: NewRunFormState,
    recommendation: FullRunRecommendation,
    beforeStartChecklist: List<ChecklistItemEntity>
) {
    val context = LocalContext.current
    var showPreview by remember { mutableStateOf(false) }
    var includeGpsLink by remember { mutableStateOf(true) }
    var copiedFeedback by remember { mutableStateOf(false) }

    val startTotalMin = formState.startHour * 60 + formState.startMinute
    val returnTotalMin = startTotalMin + recommendation.weather.durationMinutes

    val startFormatted = RecommendationEngine.formatMinutesOfDay(startTotalMin)
    val returnFormatted = RecommendationEngine.formatMinutesOfDay(returnTotalMin)

    val fullSummarySheet = remember(
        formState.locationName,
        formState.latitude,
        formState.longitude,
        formState.dayOffset,
        formState.startHour,
        formState.startMinute,
        recommendation,
        beforeStartChecklist,
        includeGpsLink
    ) {
        RecommendationEngine.buildFullRunSummarySheet(
            locationName = formState.locationName,
            latitude = formState.latitude,
            longitude = formState.longitude,
            dayOffset = formState.dayOffset,
            startHour = formState.startHour,
            startMinute = formState.startMinute,
            recommendation = recommendation,
            beforeChecklist = beforeStartChecklist,
            includeGpsLink = includeGpsLink
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("safety_and_share_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // En-tête : Partager & Copier la fiche de sortie
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Partager et copier la fiche de sortie",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Partager & Copier la fiche sortie",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Exporter le récapitulatif complet (météo, tenue, nutrition & check-list)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(
                    onClick = { showPreview = !showPreview },
                    modifier = Modifier.testTag("toggle_share_preview_button")
                ) {
                    Icon(
                        imageVector = if (showPreview) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (showPreview) "Masquer" else "Aperçu")
                }
            }

            // Bandeau Horaires Départ -> Retour prévu + Option Lien GPS
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Départ $startFormatted → Retour estimé $returnFormatted (${recommendation.weather.durationMinutes} min)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Inclure le point GPS (${formState.locationName})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Switch(
                            checked = includeGpsLink,
                            onCheckedChange = {
                                includeGpsLink = it
                                copiedFeedback = false
                            },
                            modifier = Modifier.testTag("share_include_gps_switch")
                        )
                    }
                }
            }

            AnimatedVisibility(visible = showPreview) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_message_preview_box")
                ) {
                    Text(
                        text = fullSummarySheet,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            HorizontalDivider()

            // Boutons d'action : Partager (WhatsApp/Mail/Notes) et Copier
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        shareTextViaChooser(
                            context = context,
                            subject = "Sortie ${formState.locationName} (${recommendation.plannedDistanceKm} km)",
                            message = fullSummarySheet
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("safety_share_sheet_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Partager la fiche", maxLines = 1, softWrap = false)
                }

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Fiche Sortie RunCoach", fullSummarySheet))
                        copiedFeedback = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("safety_copy_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = if (copiedFeedback) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (copiedFeedback) "Fiche copiée !" else "Copier la fiche", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private fun shareTextViaChooser(context: Context, subject: String, message: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, message)
    }
    val chooser = Intent.createChooser(sendIntent, "Partager la fiche de sortie")
    try {
        context.startActivity(chooser)
    } catch (_: Exception) {
        // Ignoré si aucune application cible n'est présente sur l'émulateur
    }
}

