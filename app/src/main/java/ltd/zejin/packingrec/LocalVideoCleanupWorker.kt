package ltd.zejin.packingrec

import android.content.Context
import android.os.Environment
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

class LocalVideoCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val settings = AppSettings(applicationContext).load()
        val retentionDays = settings.localVideoRetentionDays
        if (retentionDays <= 0) {
            return Result.success()
        }
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
        val baseDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: applicationContext.filesDir
        val outputDir = File(baseDir, settings.outputSubdir)
        val files = outputDir.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true)
        } ?: emptyArray()
        files.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
        return Result.success()
    }
}
