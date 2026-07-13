package com.routedns.routebot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.routedns.routebot.data.local.entity.QueuedEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QueuedEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: QueuedEventEntity): Long

    @Query("SELECT * FROM queued_events ORDER BY createdAt ASC LIMIT :limit")
    suspend fun peek(limit: Int): List<QueuedEventEntity>

    @Query("DELETE FROM queued_events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE queued_events SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("SELECT COUNT(*) FROM queued_events")
    fun observeCount(): Flow<Int>
}
