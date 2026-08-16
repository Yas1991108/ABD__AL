package com.lamdaai.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionAuditDao {

    @Insert
    suspend fun insert(entry: ActionAuditEntity)

    /** Most recent first — for a future "recent actions" screen in Settings. */
    @Query("SELECT * FROM action_audit ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<ActionAuditEntity>>

    /** Cheap housekeeping — call occasionally so the log doesn't grow forever. */
    @Query("DELETE FROM action_audit WHERE timestampMs < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long)
}
