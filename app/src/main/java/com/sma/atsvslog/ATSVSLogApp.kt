package com.sma.atsvslog

import android.app.Application
import com.sma.atsvslog.di.DatabaseProvider
import com.sma.atsvslog.repository.MasterBootstrapRepository
import com.sma.atsvslog.sync.SyncScheduler
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

        println("ATSVSLog Application Started")

        SyncScheduler.ensurePeriodic(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                MasterBootstrapRepository(
                    database = DatabaseProvider.get(applicationContext),
                    api = BetaNetwork.client.api
                ).bootstrap()
            }.onSuccess { result ->
                Log.i(
                    "ATSVS_MASTER",
                    "Master bootstrap complete: fetched=${result.fetched}, " +
                        "inserted=${result.inserted}, alreadyPresent=${result.alreadyPresent}, " +
                        "preservedLocalConflicts=${result.preservedLocalConflicts}"
                )
            }.onFailure { error ->
                Log.w(
                    "ATSVS_MASTER",
                    "Master bootstrap unavailable; continuing with local cache",
                    error
                )
            }
        }

        testBetaNetwork()
    }
}
