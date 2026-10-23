package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ChecklistType
import kotlinx.coroutines.flow.Flow

@Dao
interface RunCoachDao {

    // Shoes
    @Query("SELECT * FROM shoes ORDER BY currentKm ASC")
    fun observeShoes(): Flow<List<ShoeEntity>>

    @Query("SELECT * FROM shoes")
    suspend fun getAllShoesOnce(): List<ShoeEntity>

    @Query("SELECT * FROM shoes WHERE id = :id LIMIT 1")
    suspend fun getShoeById(id: Long): ShoeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShoe(shoe: ShoeEntity): Long

    @Update
    suspend fun updateShoe(shoe: ShoeEntity)

    @Delete
    suspend fun deleteShoe(shoe: ShoeEntity)

    @Query("UPDATE shoes SET currentKm = currentKm + :addedKm WHERE id = :shoeId")
    suspend fun addKmToShoe(shoeId: Long, addedKm: Double)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShoes(shoes: List<ShoeEntity>)

    @Query("DELETE FROM shoes")
    suspend fun deleteAllShoes()

    @Query("DELETE FROM gear_items")
    suspend fun deleteAllGear()

    @Query("DELETE FROM checklist_items")
    suspend fun deleteAllChecklists()

    @Query("DELETE FROM run_history")
    suspend fun deleteAllRunHistory()

    // Gear Items
    @Query("SELECT * FROM gear_items ORDER BY category ASC, name ASC")
    fun observeGearItems(): Flow<List<GearItemEntity>>

    @Query("SELECT * FROM gear_items")
    suspend fun getAllGearOnce(): List<GearItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGearItems(items: List<GearItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGearItem(item: GearItemEntity)

    @Delete
    suspend fun deleteGearItem(item: GearItemEntity)

    // Checklists
    @Query("SELECT * FROM checklist_items WHERE listType = :type ORDER BY id ASC")
    fun observeChecklist(type: ChecklistType): Flow<List<ChecklistItemEntity>>

    @Query("SELECT * FROM checklist_items")
    suspend fun getAllChecklistsOnce(): List<ChecklistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChecklistItem(item: ChecklistItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChecklistItems(items: List<ChecklistItemEntity>)

    @Update
    suspend fun updateChecklistItem(item: ChecklistItemEntity)

    @Delete
    suspend fun deleteChecklistItem(item: ChecklistItemEntity)

    @Query("UPDATE checklist_items SET checked = 0 WHERE listType = :type")
    suspend fun resetChecklist(type: ChecklistType)

    @Query("UPDATE checklist_items SET checked = 0")
    suspend fun resetAllChecklists()

    // Run History
    @Query("SELECT * FROM run_history ORDER BY dateEpochMillis DESC")
    fun observeRunHistory(): Flow<List<RunHistoryEntity>>

    @Query("SELECT * FROM run_history ORDER BY dateEpochMillis DESC")
    suspend fun getAllRunHistoryOnce(): List<RunHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRunHistory(run: RunHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRunHistoryList(runs: List<RunHistoryEntity>)

    @Delete
    suspend fun deleteRunHistory(run: RunHistoryEntity)
}
