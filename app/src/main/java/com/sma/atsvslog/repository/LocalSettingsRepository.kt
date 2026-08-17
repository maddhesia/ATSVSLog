package com.sma.atsvslog.repository

import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.database.entity.SettingEntity

class LocalSettingsRepository(
    private val database: ATSVSLogDatabase
) {
    private val dao = database.settingDao()

    suspend fun get(key: String): String? = dao.find(key)?.value

    suspend fun put(
        key: String,
        value: String?,
        now: Long = System.currentTimeMillis()
    ) {
        dao.upsert(
            SettingEntity(
                key = key,
                value = value,
                updatedAt = now
            )
        )
    }
}
