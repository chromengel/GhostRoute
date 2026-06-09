package com.ghostroute.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghostroute.app.data.CameraRepository
import com.ghostroute.app.data.SyncOutcome
import com.ghostroute.app.location.LocationManagerSource
import java.util.concurrent.TimeUnit

/**
 * Periodic (weekly) refresh of camera data from OSM/Overpass.
 *
 * This is the *only* place GhostRoute reaches the network on its own, and only
 * when connectivity is available. Navigation never depends on it.
 */
class CameraSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = CameraRepository.get(applicationContext)
        // Refresh the area around the user (where they actually drive), not a fixed
        // region. Falls back to the default region if no location fix is available.
        val loc = LocationManagerSource(applicationContext).lastKnownLocation()
        val outcome = if (loc != null) {
            repository.ensureCoverage(loc.latitude, loc.longitude, force = true)
        } else {
            repository.refresh()
        }
        return when (outcome) {
            is SyncOutcome.Success -> Result.success()
            is SyncOutcome.Error -> Result.retry() // transient (e.g. offline) → backoff
            null -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "ghostroute-camera-sync"

        /** Enqueues the weekly sync once; safe to call on every app start. */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<CameraSyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
