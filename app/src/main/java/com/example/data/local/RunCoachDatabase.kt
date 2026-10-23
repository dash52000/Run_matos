package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.model.ChecklistType
import com.example.data.model.GearCategory
import com.example.data.model.GelFeedback
import com.example.data.model.MudLevel
import com.example.data.model.RunSessionType
import com.example.data.model.ShoeType
import com.example.data.model.TerrainType
import com.example.data.model.ThermalFeeling

class Converters {
    @TypeConverter fun fromShoeType(v: ShoeType): String = v.name
    @TypeConverter fun toShoeType(v: String): ShoeType = ShoeType.valueOf(v)

    @TypeConverter fun fromGearCategory(v: GearCategory): String = v.name
    @TypeConverter fun toGearCategory(v: String): GearCategory = GearCategory.valueOf(v)

    @TypeConverter fun fromChecklistType(v: ChecklistType): String = v.name
    @TypeConverter fun toChecklistType(v: String): ChecklistType = ChecklistType.valueOf(v)

    @TypeConverter fun fromRunSessionType(v: RunSessionType): String = v.name
    @TypeConverter fun toRunSessionType(v: String): RunSessionType = RunSessionType.valueOf(v)

    @TypeConverter fun fromTerrainType(v: TerrainType): String = v.name
    @TypeConverter fun toTerrainType(v: String): TerrainType = TerrainType.valueOf(v)

    @TypeConverter fun fromMudLevel(v: MudLevel): String = v.name
    @TypeConverter fun toMudLevel(v: String): MudLevel = MudLevel.valueOf(v)

    @TypeConverter fun fromThermalFeeling(v: ThermalFeeling): String = v.name
    @TypeConverter fun toThermalFeeling(v: String): ThermalFeeling = ThermalFeeling.valueOf(v)

    @TypeConverter fun fromGelFeedback(v: GelFeedback): String = v.name
    @TypeConverter fun toGelFeedback(v: String): GelFeedback = GelFeedback.valueOf(v)
}

@Database(
    entities = [
        ShoeEntity::class,
        GearItemEntity::class,
        ChecklistItemEntity::class,
        RunHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class RunCoachDatabase : RoomDatabase() {
    abstract fun dao(): RunCoachDao

    companion object {
        @Volatile
        private var INSTANCE: RunCoachDatabase? = null

        fun getInstance(context: Context): RunCoachDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RunCoachDatabase::class.java,
                    "runcoach_matos.db"
                ).build()
                // Pas de fallbackToDestructiveMigration : un changement de schéma sans migration
                // doit échouer bruyamment plutôt qu'effacer chaussures et historique.
                // Toute évolution du schéma doit fournir une Migration explicite (addMigrations).
                INSTANCE = instance
                instance
            }
        }
    }
}
