package dev.tilesolver

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as SolverApplication).graph.retention.enforce()
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
