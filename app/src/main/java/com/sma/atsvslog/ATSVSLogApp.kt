package com.sma.atsvslog

import android.app.Application
import android.content.pm.ApplicationInfo
import com.sma.atsvslog.di.DatabaseProvider
import com.sma.atsvslog.repository.LocalSalesRepository
import com.sma.atsvslog.sync.SyncScheduler
import kotlinx.coroutines.runBlocking
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ATSVSLogApp : Application() {
    private fun testBetaNetwork() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = BetaNetwork.client.api.health()

                if (response.isSuccessful) {
                    val body = response.body()

                    Log.i(
                        "ATSVS_BETA",
                        "BETA HEALTH SUCCESS: ${body?.message}"
                    )
                } else {
                    Log.e(
                        "ATSVS_BETA",
                        "BETA HEALTH HTTP ERROR: ${response.code()}"
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "ATSVS_BETA",
                    "BETA HEALTH FAILED",
                    e
                )
            }
        }
    }
    override fun onCreate() {
        super.onCreate()

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            val preferences = getSharedPreferences(
                "atsvslog_dev",
                MODE_PRIVATE
            )
            val cleanupKey = "milestone4_master_variant_reset_20260820"

            if (!preferences.getBoolean(cleanupKey, false)) {
                runCatching {
                    runBlocking(Dispatchers.IO) {
                        LocalSalesRepository(
                            DatabaseProvider.get(applicationContext)
                        ).resetDevelopmentMasterVariants()
                    }
                }.onSuccess {
                    preferences.edit()
                        .putBoolean(cleanupKey, true)
                        .apply()
                }
            }
        }

        println("ATSVSLog Application Started")

        SyncScheduler.ensurePeriodic(applicationContext)

        testBetaNetwork()
    }
}
