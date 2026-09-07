package com.sma.atsvslog.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sma.atsvslog.database.ATSVSLogDatabase

object DatabaseProvider {
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS master_conflicts (
                    localId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    conflictUuid TEXT NOT NULL,
                    transactionUuid TEXT NOT NULL,
                    model TEXT NOT NULL,
                    requestedType TEXT NOT NULL,
                    requestedBrand TEXT NOT NULL,
                    canonicalType TEXT NOT NULL,
                    canonicalBrand TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    blockedQueueLocalId INTEGER,
                    notifiedAt INTEGER,
                    resolvedAt INTEGER,
                    status TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_master_conflicts_transactionUuid_model_requestedType_requestedBrand_status " +
                    "ON master_conflicts(transactionUuid, model, requestedType, requestedBrand, status)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_master_conflicts_status_createdAt " +
                    "ON master_conflicts(status, createdAt)"
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE master_conflicts ADD COLUMN itemUuid TEXT"
            )
            database.execSQL(
                """
                UPDATE master_conflicts
                SET itemUuid = (
                    SELECT ti.itemUuid
                    FROM transaction_items ti
                    WHERE ti.transactionUuid = master_conflicts.transactionUuid
                      AND lower(trim(ti.model)) = lower(trim(master_conflicts.model))
                      AND lower(trim(ti.type)) = lower(trim(master_conflicts.requestedType))
                      AND lower(trim(ti.brand)) = lower(trim(master_conflicts.requestedBrand))
                    ORDER BY ti.localId ASC
                    LIMIT 1
                )
                WHERE itemUuid IS NULL
                """.trimIndent()
            )
        }
    }

    @Volatile
    private var INSTANCE: ATSVSLogDatabase? = null

    fun get(context: Context): ATSVSLogDatabase =
        INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                ATSVSLogDatabase::class.java,
                ATSVSLogDatabase.DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
}
