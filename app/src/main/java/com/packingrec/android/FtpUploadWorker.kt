package com.packingrec.android

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File

class FtpUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val folderName = inputData.getString(KEY_FOLDER_NAME).orEmpty()
        val file = File(filePath)
        if (!file.exists()) {
            return Result.failure()
        }
        val settings = AppSettings(applicationContext).load()
        if (!settings.ftpEnabled || settings.ftpHost.isBlank()) {
            return Result.success()
        }
        val ftp = FTPClient()
        try {
            val host = settings.ftpHost.trim()
            val (hostname, port) = splitHost(host)
            ftp.connect(hostname, port)
            if (!ftp.login(settings.ftpUsername.ifBlank { "anonymous" }, settings.ftpPassword)) {
                return Result.retry()
            }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ensureRemoteDir(ftp, settings.ftpRemoteDir)
            val targetDir = folderName.ifBlank { file.nameWithoutExtension }
            ensureRemoteDir(ftp, targetDir)
            val remoteName = resolveRemoteName(ftp, file.name)
            file.inputStream().use { input ->
                if (!ftp.storeFile(remoteName, input)) {
                    return Result.retry()
                }
            }
            file.delete()
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        } finally {
            try {
                ftp.logout()
                ftp.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun splitHost(host: String): Pair<String, Int> {
        val clean = host.removePrefix("ftp://").removePrefix("ftps://")
        val parts = clean.split(":")
        return if (parts.size >= 2) {
            parts[0] to (parts[1].toIntOrNull() ?: 21)
        } else {
            clean to 21
        }
    }

    private fun ensureRemoteDir(ftp: FTPClient, dir: String) {
        val target = dir.ifBlank { "/" }
        if (target == "/") {
            ftp.changeWorkingDirectory("/")
            return
        }
        val absolute = target.startsWith("/")
        val parts = target.split("/").filter { it.isNotBlank() }
        if (absolute) {
            ftp.changeWorkingDirectory("/")
        }
        for (part in parts) {
            if (!ftp.changeWorkingDirectory(part)) {
                ftp.makeDirectory(part)
                ftp.changeWorkingDirectory(part)
            }
        }
    }

    private fun resolveRemoteName(ftp: FTPClient, originalName: String): String {
        if (remoteFileExists(ftp, originalName)) {
            val base = originalName.substringBeforeLast(".")
            val ext = originalName.substringAfterLast(".", "")
            var index = 1
            while (true) {
                val candidate = if (ext.isNotEmpty()) {
                    "${base}_$index.$ext"
                } else {
                    "${base}_$index"
                }
                if (!remoteFileExists(ftp, candidate)) {
                    return candidate
                }
                index += 1
            }
        }
        return originalName
    }

    private fun remoteFileExists(ftp: FTPClient, name: String): Boolean {
        val entries = ftp.listFiles(name)
        if (entries == null || entries.isEmpty()) {
            return false
        }
        return entries.any { it.type == FTPFile.FILE_TYPE }
    }

    companion object {
        const val KEY_FILE_PATH = "file_path"
        const val KEY_FOLDER_NAME = "folder_name"
    }
}
