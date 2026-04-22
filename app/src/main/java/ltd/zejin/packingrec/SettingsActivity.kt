package ltd.zejin.packingrec

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import ltd.zejin.packingrec.databinding.ActivitySettingsBinding
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var appSettings: AppSettings
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private var pendingApkUrl: String? = null
    private var downloadId: Long = -1L
    private var updateReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettings(this)
        val settings = appSettings.load()

        binding.startHoldInput.setText(formatSeconds(settings.startHoldSeconds))
        binding.stopEmptyInput.setText(formatSeconds(settings.stopEmptySeconds))
        binding.maxRecordingInput.setText(settings.maxRecordingSeconds.toString())
        binding.scanIntervalInput.setText(settings.scanIntervalMs.toString())
        binding.minBarcodeInput.setText(settings.minBarcodeLength.toString())
        binding.widthRatioInput.setText(settings.regionWidthRatio.toString())
        binding.heightRatioInput.setText(settings.regionHeightRatio.toString())
        binding.barcodeTextSizeInput.setText(settings.barcodeTextSizeSp.toString())
        binding.outputDirInput.setText(settings.outputSubdir)
        binding.localVideoRetentionInput.setText(settings.localVideoRetentionDays.toString())
        binding.ftpEnabledSwitch.isChecked = settings.ftpEnabled
        binding.ftpHostInput.setText(settings.ftpHost)
        binding.ftpUserInput.setText(settings.ftpUsername)
        binding.ftpPasswordInput.setText(settings.ftpPassword)
        binding.ftpRemoteDirInput.setText(settings.ftpRemoteDir)

        val qualityOptions = resources.getStringArray(R.array.quality_options)
        val qualityAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualityOptions)
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.qualitySpinner.adapter = qualityAdapter
        val qualityIndex = qualityOptions.indexOf(settings.videoQuality).coerceAtLeast(0)
        binding.qualitySpinner.setSelection(qualityIndex)

        binding.checkUpdateButton.setOnClickListener {
            checkForUpdate()
        }

        binding.saveButton.setOnClickListener {
            val newSettings = DetectionSettings(
                startHoldSeconds = binding.startHoldInput.text.toString().toFloatOrNull() ?: 2f,
                stopEmptySeconds = binding.stopEmptyInput.text.toString().toFloatOrNull() ?: 2f,
                maxRecordingSeconds = binding.maxRecordingInput.text.toString().toIntOrNull() ?: 180,
                scanIntervalMs = binding.scanIntervalInput.text.toString().toLongOrNull() ?: 500L,
                minBarcodeLength = binding.minBarcodeInput.text.toString().toIntOrNull() ?: 6,
                regionWidthRatio = binding.widthRatioInput.text.toString().toFloatOrNull() ?: 0.6f,
                regionHeightRatio = binding.heightRatioInput.text.toString().toFloatOrNull() ?: 0.35f,
                barcodeTextSizeSp = binding.barcodeTextSizeInput.text.toString().toFloatOrNull() ?: 48f,
                outputSubdir = binding.outputDirInput.text.toString().ifBlank { "PackingRec" },
                localVideoRetentionDays = binding.localVideoRetentionInput.text.toString().toIntOrNull()
                    ?.coerceAtLeast(1) ?: 10,
                videoQuality = binding.qualitySpinner.selectedItem?.toString() ?: "HD",
                ftpEnabled = binding.ftpEnabledSwitch.isChecked,
                ftpHost = binding.ftpHostInput.text.toString().trim(),
                ftpUsername = binding.ftpUserInput.text.toString().trim(),
                ftpPassword = binding.ftpPasswordInput.text.toString(),
                ftpRemoteDir = binding.ftpRemoteDirInput.text.toString().ifBlank { "/" }
            )
            appSettings.save(newSettings)
            finish()
        }

        binding.cancelButton.setOnClickListener {
            finish()
        }

        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            binding.versionText.text = "v${packageInfo.versionName}"
        } catch (e: Exception) {
            binding.versionText.text = "v1.0"
        }

        registerUpdateReceiver()
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingApkUrl
        if (pending != null && canInstallPackages()) {
            pendingApkUrl = null
            startUpdateDownload(pending)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateReceiver?.let { unregisterReceiver(it) }
        updateExecutor.shutdown()
    }

    private fun formatSeconds(value: Float): String {
        return if (value % 1f == 0f) {
            value.toInt().toString()
        } else {
            String.format("%.1f", value)
        }
    }

    private fun checkForUpdate() {
        updateExecutor.execute {
            val result = fetchUpdateInfo()
            runOnUiThread {
                if (result == null) {
                    Toast.makeText(this, getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val (remoteVersionName, remoteVersionCode, apkUrl) = result
                val local = packageManager.getPackageInfo(packageName, 0)
                val localVersionName = local.versionName ?: "0"
                val localVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    local.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    local.versionCode.toLong()
                }
                val hasUpdate = if (remoteVersionCode != null) {
                    remoteVersionCode > localVersionCode
                } else {
                    compareVersions(remoteVersionName, localVersionName) > 0
                }
                if (!hasUpdate) {
                    Toast.makeText(this, getString(R.string.update_not_found), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.update_available_title))
                    .setMessage(getString(R.string.update_available_message, localVersionName, remoteVersionName))
                    .setPositiveButton(getString(R.string.update_now)) { _, _ ->
                        startUpdateDownload(apkUrl)
                    }
                    .setNegativeButton(getString(R.string.update_cancel), null)
                    .show()
            }
        }
    }

    private fun fetchUpdateInfo(): Triple<String, Long?, String>? {
        val url = URL("https://zejin.ltd/soft/packingRecAndroid/latest.json")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        return try {
            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(jsonText)
            val versionName = obj.optString("versionName")
                .ifBlank { obj.optString("version") }
                .ifBlank { obj.optString("version_name") }
            val versionCode = obj.optLong("versionCode", -1).takeIf { it >= 0 }
                ?: obj.optLong("version_code", -1).takeIf { it >= 0 }
            val apkUrl = obj.optString("apkUrl")
                .ifBlank { obj.optString("apk") }
                .ifBlank { obj.optString("url") }
                .ifBlank { obj.optString("downloadUrl") }
            if (versionName.isBlank() || apkUrl.isBlank()) {
                null
            } else {
                Triple(versionName, versionCode, apkUrl)
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun startUpdateDownload(apkUrl: String) {
        if (!canInstallPackages()) {
            pendingApkUrl = apkUrl
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_install_prompt))
                .setMessage(getString(R.string.update_install_prompt))
                .setPositiveButton(getString(R.string.update_install_action)) { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.update_cancel), null)
                .show()
            return
        }
        Toast.makeText(this, getString(R.string.update_downloading), Toast.LENGTH_SHORT).show()
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "packingrec_update.apk")
        if (file.exists()) {
            file.delete()
        }
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
        downloadId = downloadManager.enqueue(request)
    }

    private fun registerUpdateReceiver() {
        if (updateReceiver != null) {
            return
        }
        updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId || id == -1L) {
                    return
                }
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "packingrec_update.apk")
                if (!file.exists()) {
                    Toast.makeText(context, getString(R.string.update_download_failed), Toast.LENGTH_SHORT).show()
                    return
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${packageName}.fileprovider",
                    file
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(installIntent)
            }
        }
        registerReceiver(updateReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(Regex("[^0-9]+")).filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(Regex("[^0-9]+")).filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
        val max = maxOf(aParts.size, bParts.size)
        for (i in 0 until max) {
            val ai = aParts.getOrElse(i) { 0 }
            val bi = bParts.getOrElse(i) { 0 }
            if (ai != bi) {
                return ai.compareTo(bi)
            }
        }
        return 0
    }
}
