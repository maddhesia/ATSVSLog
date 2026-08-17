package com.sma.atsvslog.di

import android.content.Context
import androidx.room.Room
import com.sma.atsvslog.database.ATSVSLogDatabase

object DatabaseProvider {
    @Volatile
    private var INSTANCE: ATSVSLogDatabase? = null

    fun get(context: Context): ATSVSLogDatabase =
        INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                ATSVSLogDatabase::class.java,
                ATSVSLogDatabase.DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
}
