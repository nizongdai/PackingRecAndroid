package com.packingrec.android

import android.content.Context
import android.content.SharedPreferences

data class DetectionSettings(
    val startHoldSeconds: Float,
    val stopEmptySeconds: Float,
    val scanIntervalMs: Long,
    val minBarcodeLength: Int,
    val regionWidthRatio: Float,
    val regionHeightRatio: Float,
    val barcodeTextSizeSp: Float,
    val outputSubdir: String,
    val videoQuality: String,
    val ftpEnabled: Boolean,
    val ftpHost: String,
    val ftpUsername: String,
    val ftpPassword: String,
    val ftpRemoteDir: String
)

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("packing_rec_settings", Context.MODE_PRIVATE)

    fun load(): DetectionSettings {
        val startHoldSeconds = if (prefs.contains("startHoldSecondsFloat")) {
            prefs.getFloat("startHoldSecondsFloat", 2f)
        } else {
            prefs.getInt("startHoldSeconds", 2).toFloat()
        }
        val stopEmptySeconds = if (prefs.contains("stopEmptySecondsFloat")) {
            prefs.getFloat("stopEmptySecondsFloat", 2f)
        } else {
            prefs.getInt("stopEmptySeconds", 2).toFloat()
        }
        val scanIntervalMs = if (prefs.contains("scanIntervalMs")) {
            prefs.getLong("scanIntervalMs", 500L)
        } else {
            prefs.getLong("ocrIntervalMs", 500L)
        }
        val minBarcodeLength = if (prefs.contains("minBarcodeLength")) {
            prefs.getInt("minBarcodeLength", 6)
        } else {
            prefs.getInt("minTextLength", 6)
        }
        val regionWidthRatio = prefs.getFloat("regionWidthRatio", 0.6f)
        val regionHeightRatio = prefs.getFloat("regionHeightRatio", 0.35f)
        val barcodeTextSizeSp = prefs.getFloat("barcodeTextSizeSp", 48f)
        val outputSubdir = prefs.getString("outputSubdir", "PackingRec") ?: "PackingRec"
        val videoQuality = prefs.getString("videoQuality", "SD") ?: "SD"
        val ftpEnabled = prefs.getBoolean("ftpEnabled", false)
        val ftpHost = prefs.getString("ftpHost", "") ?: ""
        val ftpUsername = prefs.getString("ftpUsername", "") ?: ""
        val ftpPassword = prefs.getString("ftpPassword", "") ?: ""
        val ftpRemoteDir = prefs.getString("ftpRemoteDir", "/") ?: "/"
        return DetectionSettings(
            startHoldSeconds,
            stopEmptySeconds,
            scanIntervalMs,
            minBarcodeLength,
            regionWidthRatio,
            regionHeightRatio,
            barcodeTextSizeSp,
            outputSubdir,
            videoQuality,
            ftpEnabled,
            ftpHost,
            ftpUsername,
            ftpPassword,
            ftpRemoteDir
        )
    }

    fun save(settings: DetectionSettings) {
        prefs.edit()
            .putFloat("startHoldSecondsFloat", settings.startHoldSeconds)
            .putFloat("stopEmptySecondsFloat", settings.stopEmptySeconds)
            .putLong("scanIntervalMs", settings.scanIntervalMs)
            .putInt("minBarcodeLength", settings.minBarcodeLength)
            .putFloat("regionWidthRatio", settings.regionWidthRatio)
            .putFloat("regionHeightRatio", settings.regionHeightRatio)
            .putFloat("barcodeTextSizeSp", settings.barcodeTextSizeSp)
            .putString("outputSubdir", settings.outputSubdir)
            .putString("videoQuality", settings.videoQuality)
            .putBoolean("ftpEnabled", settings.ftpEnabled)
            .putString("ftpHost", settings.ftpHost)
            .putString("ftpUsername", settings.ftpUsername)
            .putString("ftpPassword", settings.ftpPassword)
            .putString("ftpRemoteDir", settings.ftpRemoteDir)
            .apply()
    }
}
