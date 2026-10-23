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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.GearItemEntity
import com.example.data.local.ShoeEntity
import com.example.data.model.GearCategory
import com.example.data.model.ShoeType
import com.example.ui.NutritionSettingsState
import com.example.ui.theme.AlertRed
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GearAndShoesScreen(
    shoes: List<ShoeEntity>,
    gearItems: List<GearItemEntity>,
    nutritionSettings: NutritionSettingsState,
    onAddOrUpdateShoe: (Long, String, ShoeType, Double, Double) -> Unit,
    onDeleteShoe: (ShoeEntity) -> Unit,
    onToggleGearOwned: (GearItemEntity) -> Unit,
    onAddCustomGear: (String, GearCategory) -> Unit,
    onUpdateNutritionSettings: (Int, Int, Int, Int) -> Unit
) {
    var editingShoe by remember { mutableStateOf<ShoeEntity?>(null) }
    var showAddShoeDialog by remember { mutableStateOf(false) }
    var showAddGearDialog by remember { mutableStateOf(false) }
    var shoeToDelete by remember { mutableStateOf<ShoeEntity?>(null) }

    val over90Shoes = shoes.filter { it.isOver90Percent }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("gear_and_shoes_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // En-tête Mon Matos
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Mon matos & Chaussures",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Gérez vos paires de chaussures (suivi d'usure sur 700 km par défaut), votre garde-robe technique et vos cibles glucidiques.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Alerte globale si au moins une paire dépasse 90 % de son km max
        if (over90Shoes.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AlertRed.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shoes_wear_global_alert")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Alerte usure chaussures",
                            tint = AlertRed
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Alerte usure (> 90 % du km max)",
                                style = MaterialTheme.typography.titleSmall,
                                color = AlertRed,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = over90Shoes.joinToString(" • ") {
                                    "${it.name} (${it.currentKm.roundToInt()}/${it.maxKm.roundToInt()} km)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Section 1 : CHAUSSURES
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shoes_section_card"),
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
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Chaussures",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Mes Chaussures (${shoes.size})",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { showAddShoeDialog = true },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("add_shoe_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ajouter", maxLines = 1, softWrap = false)
                        }
                    }

                    shoes.forEachIndexed { idx, shoe ->
                        if (idx > 0) HorizontalDivider()
                        ShoeItemRow(
                            shoe = shoe,
                            onEdit = { editingShoe = shoe },
                            onDelete = { shoeToDelete = shoe }
                        )
                    }
                }
            }
        }

        // Section 2 : CALIBRAGE NUTRITION (Glucides/h 1h-2h30 & >2h30)
        item {
            NutritionCalibrationCard(
                settings = nutritionSettings,
                onUpdate = onUpdateNutritionSettings
            )
        }

        // Section 3 : INVENTAIRE VÊTEMENTS & ACCESSOIRES
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("gear_inventory_card"),
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
                                imageVector = Icons.Default.Checkroom,
                                contentDescription = "Équipements",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Vêtements & Équipements",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { showAddGearDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("add_custom_gear_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ajouter", maxLines = 1, softWrap = false)
                        }
                    }

                    GearCategory.entries.forEach { category ->
                        val itemsForCat = gearItems.filter { it.category == category }
                        if (itemsForCat.isNotEmpty()) {
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            itemsForCat.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = item.owned,
                                        onCheckedChange = { onToggleGearOwned(item) },
                                        modifier = Modifier.testTag("gear_switch_${item.code}")
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showAddShoeDialog) {
        ShoeEditDialog(
            initialShoe = null,
            onDismiss = { showAddShoeDialog = false },
            onSave = { id, name, type, currentKm, maxKm ->
                onAddOrUpdateShoe(id, name, type, currentKm, maxKm)
                showAddShoeDialog = false
            }
        )
    }

    editingShoe?.let { shoeToEdit ->
        ShoeEditDialog(
            initialShoe = shoeToEdit,
            onDismiss = { editingShoe = null },
            onSave = { id, name, type, currentKm, maxKm ->
                onAddOrUpdateShoe(id, name, type, currentKm, maxKm)
                editingShoe = null
            }
        )
    }

    if (showAddGearDialog) {
        AddCustomGearDialog(
            onDismiss = { showAddGearDialog = false },
            onSave = { name, cat ->
                onAddCustomGear(name, cat)
                showAddGearDialog = false
            }
        )
    }

    shoeToDelete?.let { shoe ->
        AlertDialog(
            onDismissRequest = { shoeToDelete = null },
            title = {
                Text(
                    text = "Supprimer cette paire ?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Êtes-vous sûr de vouloir supprimer définitivement « ${shoe.name} » (${shoe.currentKm.roundToInt()} km parcourus sur ${shoe.maxKm.roundToInt()} km) ? Cette action est irréversible et retirera son historique d'usure.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteShoe(shoe)
                        shoeToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Supprimer définitivement")
                }
            },
            dismissButton = {
                TextButton(onClick = { shoeToDelete = null }) {
                    Text("Annuler")
                }
            }
        )
    }
}

@Composable
private fun ShoeItemRow(
    shoe: ShoeEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shoe.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${shoe.type.label} • ${shoe.currentKm.roundToInt()} / ${shoe.maxKm.roundToInt()} km",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Modifier ${shoe.name}")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Supprimer ${shoe.name}")
                }
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

        if (shoe.isOver90Percent) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = "Alerte 90%",
                    tint = AlertRed,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Attention : dépasse 90 % du km max (${(shoe.wearRatio * 100).roundToInt()} %) !",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertRed,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun NutritionCalibrationCard(
    settings: NutritionSettingsState,
    onUpdate: (Int, Int, Int, Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nutrition_calibration_card"),
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
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Calibrage Glucides & Seuil GPX",
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = "Réglages Nutrition & Seuil Bitume GPX",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Glucides ajustés de ±5 g/h après sortie • Seuil bitume GPX modifiable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "Sorties 1 h à 2 h 30 : ${settings.carbRateMedium} g/h (défaut 45 g/h)",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = settings.carbRateMedium.toFloat(),
                onValueChange = {
                    val stepped = ((it / 5f).roundToInt() * 5).coerceIn(30, 90)
                    onUpdate(stepped, settings.carbRateLong, settings.gelCarbsGrams, settings.asphaltThresholdPercent)
                },
                valueRange = 30f..90f,
                steps = 11
            )

            Text(
                text = "Sorties > 2 h 30 : ${settings.carbRateLong} g/h (défaut 75 g/h)",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = settings.carbRateLong.toFloat(),
                onValueChange = {
                    val stepped = ((it / 5f).roundToInt() * 5).coerceIn(30, 90)
                    onUpdate(settings.carbRateMedium, stepped, settings.gelCarbsGrams, settings.asphaltThresholdPercent)
                },
                valueRange = 30f..90f,
                steps = 11
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "Seuil bitume GPX (chaussures route si terrain sec) : ${settings.asphaltThresholdPercent} % (défaut 60 %)",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = settings.asphaltThresholdPercent.toFloat(),
                onValueChange = {
                    val stepped = ((it / 5f).roundToInt() * 5).coerceIn(30, 90)
                    onUpdate(settings.carbRateMedium, settings.carbRateLong, settings.gelCarbsGrams, stepped)
                },
                valueRange = 30f..90f,
                steps = 11,
                modifier = Modifier.testTag("asphalt_threshold_slider")
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShoeEditDialog(
    initialShoe: ShoeEntity?,
    onDismiss: () -> Unit,
    onSave: (Long, String, ShoeType, Double, Double) -> Unit
) {
    var name by remember { mutableStateOf(initialShoe?.name ?: "") }
    var selectedType by remember { mutableStateOf(initialShoe?.type ?: ShoeType.TRAIL_POLYVALENT) }
    var currentKmText by remember { mutableStateOf(initialShoe?.currentKm?.roundToInt()?.toString() ?: "0") }
    var maxKmText by remember { mutableStateOf(initialShoe?.maxKm?.roundToInt()?.toString() ?: "700") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialShoe == null) "Nouvelle paire de chaussures" else "Modifier la paire")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom du modèle (ex. Salomon Genesis)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shoe_name_input")
                )

                Text("Type de chaussure", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ShoeType.entries.forEach { sType ->
                        FilterChip(
                            selected = selectedType == sType,
                            onClick = { selectedType = sType },
                            label = { Text(sType.label) }
                        )
                    }
                }

                OutlinedTextField(
                    value = currentKmText,
                    onValueChange = { currentKmText = it },
                    label = { Text("Km déjà parcourus") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shoe_current_km_input")
                )

                OutlinedTextField(
                    value = maxKmText,
                    onValueChange = { maxKmText = it },
                    label = { Text("Km max avant retraite (700 par défaut)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shoe_max_km_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cur = currentKmText.replace(",", ".").toDoubleOrNull() ?: 0.0
                    val max = maxKmText.replace(",", ".").toDoubleOrNull() ?: 700.0
                    onSave(initialShoe?.id ?: 0L, name, selectedType, cur, max)
                },
                modifier = Modifier.testTag("save_shoe_button")
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
private fun AddCustomGearDialog(
    onDismiss: () -> Unit,
    onSave: (String, GearCategory) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(GearCategory.HAUTS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter un équipement dans Mon matos") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom de l'équipement") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Catégorie", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GearCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, category) }) {
                Text("Ajouter")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
