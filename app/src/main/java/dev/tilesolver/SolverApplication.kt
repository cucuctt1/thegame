package dev.tilesolver

import android.app.Application
import androidx.work.*
import dev.tilesolver.data.AppDatabase
import dev.tilesolver.data.RetentionManagerImpl
import java.util.concurrent.TimeUnit
import org.opencv.android.OpenCVLoader

class SolverApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        check(OpenCVLoader.initLocal()) { "Bundled OpenCV could not be initialized" }
        graph = AppGraph(this)
        graph.appScope.launchSafely { graph.retention.enforce() }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "diagnostic-retention",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RetentionWorker>(24, TimeUnit.HOURS).build(),
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) graph.boardMapper.clearCaches()
    }
}
