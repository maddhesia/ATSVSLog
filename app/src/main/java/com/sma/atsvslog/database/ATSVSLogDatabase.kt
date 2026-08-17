package com.sma.atsvslog.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.sma.atsvslog.database.dao.DailyCounterDao
import com.sma.atsvslog.database.dao.MasterDao
import com.sma.atsvslog.database.dao.SettingDao
import com.sma.atsvslog.database.dao.SyncQueueDao
import com.sma.atsvslog.database.dao.TransactionDao
import com.sma.atsvslog.database.dao.TransactionItemDao
import com.sma.atsvslog.database.entity.DailyCounterEntity
import com.sma.atsvslog.database.entity.MasterEntity
import com.sma.atsvslog.database.entity.SettingEntity
import com.sma.atsvslog.database.entity.SyncQueueEntity
import com.sma.atsvslog.database.entity.TransactionEntity
import com.sma.atsvslog.database.entity.TransactionItemEntity

@Database(
    entities = [
        TransactionEntity::class,
        TransactionItemEntity::class,
        DailyCounterEntity::class,
        MasterEntity::class,
        SyncQueueEntity::class,
        SettingEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ATSVSLogDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun transactionItemDao(): TransactionItemDao
    abstract fun dailyCounterDao(): DailyCounterDao
    abstract fun masterDao(): MasterDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun settingDao(): SettingDao

    companion object {
        const val DATABASE_NAME = "ATSVSLog.db"
    }
}
