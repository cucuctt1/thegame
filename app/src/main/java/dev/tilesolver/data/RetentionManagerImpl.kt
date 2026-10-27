package dev.tilesolver.data

import android.content.Context
import dev.tilesolver.core.RetentionManager
import java.io.File

class RetentionManagerImpl(private val context: Context, private val dao: DiagnosticDao) : RetentionManager {
    override suspend fun enforce() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        var retainedBytes = 0L
        dao.all().forEach { item ->
            val keep = item.latestFailure || (item.createdAt >= cutoff && retainedBytes + item.bytes <= MAX_BYTES)
            if (keep) retainedBytes += item.bytes else {
                val file = File(item.path)
                if (isInsideDiagnostics(file)) file.delete()
                dao.delete(item.id)
            }
        }
        context.cacheDir.resolve("diagnostics").apply { if (!exists()) mkdirs() }
    }

    private fun isInsideDiagnostics(file: File): Boolean = try {
        file.canonicalPath.startsWith(context.cacheDir.resolve("diagnostics").canonicalPath + File.separator)
    } catch (_: Exception) { false }

    companion object {
        private const val MAX_BYTES = 50L * 1024 * 1024
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
