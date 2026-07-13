package com.routedns.routebot.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.routedns.routebot.data.local.dao.QueuedEventDao
import com.routedns.routebot.data.local.entity.QueuedEventEntity

@Database(
    entities = [QueuedEventEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RouteBotDatabase : RoomDatabase() {
    abstract fun queuedEventDao(): QueuedEventDao
}
