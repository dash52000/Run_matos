package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.ChecklistType
import com.example.data.model.GearCategory
import com.example.data.model.GelFeedback
import com.example.data.model.MudLevel
import com.example.data.model.RunSessionType
import com.example.data.model.ShoeType
import com.example.data.model.TerrainType
import com.example.data.model.ThermalFeeling

@Entity(tableName = "shoes")
data class ShoeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ShoeType,
    val currentKm: Double,
    val maxKm: Double = 700.0
) {
    val wearRatio: Float
        get() = if (maxKm > 0) (currentKm / maxKm).toFloat().coerceIn(0f, 1.5f) else 0f

    val isOver90Percent: Boolean
        get() = maxKm > 0 && currentKm >= (0.9 * maxKm)
}

@Entity(tableName = "gear_items")
data class GearItemEntity(
    @PrimaryKey val code: String,
    val name: String,
    val category: GearCategory,
    val owned: Boolean
)

@Entity(tableName = "checklist_items")
data class ChecklistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listType: ChecklistType,
    val label: String,
    val checked: Boolean = false
)

@Entity(tableName = "run_history")
data class RunHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochMillis: Long,
    val dateFormatted: String,
    val locationName: String,
    val sessionType: RunSessionType,
    val terrain: TerrainType,
    val plannedDistanceKm: Double,
    val actualDistanceKm: Double,
    val elevationGainDPlusMeters: Int = 0,
    val gpxSurfaceSummary: String = "",
    val durationMinutes: Int,
    val realTempC: Double,
    val referenceTempC: Double,
    val windKmh: Double,
    val rainMm: Double,
    val rain72hMm: Double,
    val mudLevel: MudLevel,
    val maxUvIndex: Double,
    val outfitWorn: String,
    val shoeId: Long?,
    val shoeName: String,
    val shoeType: ShoeType,
    val gelsCarried: Int,
    val carbRateUsed: Int,
    val thermalFeeling: ThermalFeeling,
    val gelFeedback: GelFeedback,
    val notes: String = ""
)
