package com.aijec.izmirtransit.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aijec.izmirtransit.core.data.importer.StaticDataRefresher
import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.routing.NetworkRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Weekly refresh of lines, stops, shapes, timetables and announcements from the open-data
 * portal, on Wi-Fi only. The shipped database keeps the app fully usable if this never runs.
 */
class StaticDataRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun refresher(): StaticDataRefresher
        fun lineRepository(): LineRepository
        fun networkRepository(): NetworkRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        return try {
            when (val outcome = deps.refresher().refresh()) {
                is StaticDataRefresher.Outcome.Updated -> {
                    deps.lineRepository().clearCaches()
                    // The trip planner's network is built from the old data; rebuild on next use.
                    deps.networkRepository().clear()
                    Log.i(TAG, "Static data refreshed:\n${outcome.report}")
                    Result.success()
                }
                // Bad upstream data: keep what we have and try again next week.
                is StaticDataRefresher.Outcome.Rejected -> {
                    Log.w(TAG, "Refresh rejected: ${outcome.reason}")
                    Result.success()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Refresh download failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "StaticDataRefresh"
        private const val MAX_ATTEMPTS = 3
        const val UNIQUE_NAME = "static-data-refresh"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<StaticDataRefreshWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                // The bundled data is fresh at install; no need to download straight away.
                .setInitialDelay(1, TimeUnit.DAYS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
