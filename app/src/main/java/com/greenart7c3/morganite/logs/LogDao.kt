package com.greenart7c3.morganite.logs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntry)

    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<LogEntry>>

    @Query("DELETE FROM logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
