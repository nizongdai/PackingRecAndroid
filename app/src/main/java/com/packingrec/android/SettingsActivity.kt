package com.packingrec.android

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.packingrec.android.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettings(this)
        val settings = appSettings.load()

        binding.startHoldInput.setText(formatSeconds(settings.startHoldSeconds))
        binding.stopEmptyInput.setText(formatSeconds(settings.stopEmptySeconds))
        binding.scanIntervalInput.setText(settings.scanIntervalMs.toString())
        binding.minBarcodeInput.setText(settings.minBarcodeLength.toString())
        binding.widthRatioInput.setText(settings.regionWidthRatio.toString())
        binding.heightRatioInput.setText(settings.regionHeightRatio.toString())
        binding.barcodeTextSizeInput.setText(settings.barcodeTextSizeSp.toString())
        binding.outputDirInput.setText(settings.outputSubdir)
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

        binding.saveButton.setOnClickListener {
            val newSettings = DetectionSettings(
                startHoldSeconds = binding.startHoldInput.text.toString().toFloatOrNull() ?: 2f,
                stopEmptySeconds = binding.stopEmptyInput.text.toString().toFloatOrNull() ?: 2f,
                scanIntervalMs = binding.scanIntervalInput.text.toString().toLongOrNull() ?: 500L,
                minBarcodeLength = binding.minBarcodeInput.text.toString().toIntOrNull() ?: 6,
                regionWidthRatio = binding.widthRatioInput.text.toString().toFloatOrNull() ?: 0.6f,
                regionHeightRatio = binding.heightRatioInput.text.toString().toFloatOrNull() ?: 0.35f,
                barcodeTextSizeSp = binding.barcodeTextSizeInput.text.toString().toFloatOrNull() ?: 48f,
                outputSubdir = binding.outputDirInput.text.toString().ifBlank { "PackingRec" },
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
    }

    private fun formatSeconds(value: Float): String {
        return if (value % 1f == 0f) {
            value.toInt().toString()
        } else {
            String.format("%.1f", value)
        }
    }
}
