package com.packingrec.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.packingrec.android.databinding.ActivityMainBinding
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var appSettings: AppSettings
    private lateinit var tts: TextToSpeech

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentRecordingFile: File? = null

    private var hasBarcodeState = false
    private var stateStartAt = 0L
    private var lastMotionAt = 0L
    private var isRecording = false
    private var lastResultText = ""
    private var currentRecordingBarcodeName: String? = null
    private var restartAfterStop = false
    private var pendingRestartBarcodeText: String? = null
    private var lastDisplayedBarcodeText: String = ""
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            if (granted) {
                startCamera()
            } else {
                binding.recognitionStatusText.text = getString(R.string.permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        appSettings = AppSettings(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this) {}
        applyRegionRatio()
        applyBarcodeTextSize()
        updateRecognitionStatus(false)
        updateRecordingStatus()
        setupFtpStatusObserver()

        binding.switchCameraButton.setOnClickListener {
            cameraSelector =
                if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
            startCamera()
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.pauseButton.setOnClickListener {
            enqueuePendingUploads()
        }

        binding.stopRecordingButton.setOnClickListener {
            stopRecording(true)
        }

        requestPermissionsIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts.shutdown()
    }

    override fun onResume() {
        super.onResume()
        applyRegionRatio()
        applyBarcodeTextSize()
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val granted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            startCamera()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val rotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
            val overlayEffect = createTimestampOverlayEffect()
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
            preview.setSurfaceProvider(binding.previewView.surfaceProvider)

            val recorder = Recorder.Builder()
                .setQualitySelector(buildQualitySelector())
                .build()
            videoCapture = VideoCapture.withOutput(recorder).also { capture ->
                capture.targetRotation = rotation
            }

            val analyzer = BarcodeAnalyzer(
                onBarcodeResult = { text, ts, hasMotion -> handleBarcodeResult(text, ts, hasMotion) },
                loadSettings = { appSettings.load() }
            )
            val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .build()
            imageAnalysis.setAnalyzer(cameraExecutor, analyzer)

            cameraProvider.unbindAll()
            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageAnalysis)
                .addUseCase(videoCapture!!)
                .addEffect(overlayEffect)
                .build()
            cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroup)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createTimestampOverlayEffect(): OverlayEffect {
        val paint = Paint().apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 12f * resources.displayMetrics.scaledDensity
            isAntiAlias = true
            isFakeBoldText = false
        }
        val handler = Handler(Looper.getMainLooper())
        val effect = OverlayEffect(
            OverlayEffect.PREVIEW or OverlayEffect.VIDEO_CAPTURE,
            1,
            handler
        ) { }
        effect.setOnDrawListener { frame ->
            val canvas = frame.getOverlayCanvas() ?: return@setOnDrawListener false
            canvas.drawColor(0x00000000, android.graphics.PorterDuff.Mode.CLEAR)
            val text = timestampFormat.format(Date())
            val textWidth = paint.measureText(text)
            val x = (canvas.width - textWidth) / 2f
            val y = canvas.height / 2f
            canvas.drawText(text, x, y, paint)
            true
        }
        return effect
    }

    private fun buildQualitySelector(): QualitySelector {
        val settings = appSettings.load()
        val desired = when (settings.videoQuality) {
            "SD" -> Quality.SD
            "FHD" -> Quality.FHD
            "UHD" -> Quality.UHD
            else -> Quality.HD
        }
        val ordered = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
        return QualitySelector.fromOrderedList(
            listOf(desired) + ordered.filter { it != desired },
            FallbackStrategy.lowerQualityOrHigherThan(desired)
        )
    }

    private fun applyRegionRatio() {
        val settings = appSettings.load()
        val params = binding.centerRegion.layoutParams as ConstraintLayout.LayoutParams
        params.matchConstraintPercentWidth = settings.regionWidthRatio
        params.matchConstraintPercentHeight = settings.regionHeightRatio
        binding.centerRegion.layoutParams = params
    }

    private fun applyBarcodeTextSize() {
        val settings = appSettings.load()
        binding.barcodeText.textSize = settings.barcodeTextSizeSp
    }

    private fun handleBarcodeResult(text: String, timestamp: Long, hasMotion: Boolean) {
        val settings = appSettings.load()
        val normalizedText = text.trim()
        val hasText = normalizedText.length >= settings.minBarcodeLength
        lastResultText = normalizedText

        // 条码出现/消失状态切换时，记录切换时间，用于计算持续时长
        if (hasText != hasBarcodeState) {
            hasBarcodeState = hasText
            stateStartAt = timestamp
        }
        if (hasMotion || lastMotionAt == 0L) {
            lastMotionAt = timestamp
        }

        val elapsed = timestamp - stateStartAt
        // 自动开始：中央区域持续识别到条码达到开始停留秒数，且当前不在录制
        if (hasText && !isRecording && elapsed >= (settings.startHoldSeconds * 1000).toLong()) {
            startRecording()
        } else if (hasText && isRecording) {
            val currentName = sanitizeBarcodeName(normalizedText)
            val activeName = currentRecordingBarcodeName.orEmpty()
            if (currentName.isNotBlank() && currentName != activeName) {
                requestRecordingSwitch(normalizedText)
            }
        // 自动停止：中央区域画面持续无变化达到停止空置秒数，且当前正在录制
        } else if (isRecording && timestamp - lastMotionAt >= (settings.stopEmptySeconds * 1000).toLong()) {
            stopRecording(false)
        }

        runOnUiThread {
            updateRecognitionStatus(hasText)
            updateBarcodeDisplay(hasText, normalizedText)
        }
    }

    private fun updateRecognitionStatus(hasText: Boolean) {
        val stateLabel = if (hasText) "条码" else "无"
        binding.recognitionStatusText.text =
            getString(R.string.recognition_status_format, stateLabel)
    }

    private fun startRecording(overrideBarcodeText: String? = null) {
        val currentVideoCapture = videoCapture ?: return
        if (recording != null) {
            return
        }
        val barcodeSource = overrideBarcodeText ?: lastResultText
        val outputDir = createOutputDir()
        val barcodeName = sanitizeBarcodeName(barcodeSource)
        val fileBaseName = if (barcodeName.isNotBlank()) barcodeName else
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = uniqueFile(outputDir, "$fileBaseName.mp4")
        currentRecordingFile = file
        currentRecordingBarcodeName = barcodeName.ifBlank { fileBaseName }
        val outputOptions = FileOutputOptions.Builder(file).build()
        recording = currentVideoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    isRecording = false
                    recording = null
                    currentRecordingFile?.let { enqueueFtpUploadIfEnabled(it, fileBaseName) }
                    speak(getString(R.string.stop_recording))
                    updateRecordingStatus()
                    val pending = if (restartAfterStop) {
                        restartAfterStop = false
                        pendingRestartBarcodeText.also { pendingRestartBarcodeText = null }
                    } else {
                        null
                    }
                    if (pending != null) {
                        startRecording(pending)
                    }
                }
            }
        isRecording = true
        speak(buildStartRecordingSpeech(barcodeSource))
        updateRecordingStatus()
    }

    private fun stopRecording(userInitiated: Boolean) {
        if (recording == null) {
            return
        }
        recording?.stop()
        recording = null
        isRecording = false
        if (userInitiated) {
            hasBarcodeState = false
            stateStartAt = System.currentTimeMillis()
        }
        updateRecordingStatus()
    }

    private fun enqueueFtpUploadIfEnabled(file: File, folderName: String) {
        val settings = appSettings.load()
        if (!settings.ftpEnabled || settings.ftpHost.isBlank()) {
            return
        }
        if (!file.exists()) {
            return
        }
        val data = workDataOf(
            FtpUploadWorker.KEY_FILE_PATH to file.absolutePath,
            FtpUploadWorker.KEY_FOLDER_NAME to folderName
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<FtpUploadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork("ftp_upload_queue", ExistingWorkPolicy.APPEND, request)
    }

    private fun createOutputDir(): File {
        val settings = appSettings.load()
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: filesDir
        val outputDir = File(baseDir, settings.outputSubdir)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        return outputDir
    }

    private fun enqueuePendingUploads() {
        val outputDir = createOutputDir()
        val files = outputDir.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true)
        }?.sortedBy { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, getString(R.string.all_uploaded), Toast.LENGTH_SHORT).show()
            return
        }
        files.forEach { file ->
            enqueueFtpUploadIfEnabled(file, file.nameWithoutExtension)
        }
    }

    private fun updateRecordingStatus() {
        val recordingLabel = if (isRecording) getString(R.string.recording) else getString(R.string.idle)
        binding.recordingStatusText.text =
            getString(R.string.recording_status_format, recordingLabel)
    }

    private fun displayBarcodeText(raw: String): String {
        return raw.split(",").firstOrNull()?.trim().orEmpty()
    }

    private fun updateBarcodeDisplay(hasText: Boolean, normalizedText: String) {
        if (hasText) {
            lastDisplayedBarcodeText = displayBarcodeText(normalizedText)
            binding.barcodeText.text = lastDisplayedBarcodeText
            return
        }
        if (isRecording && lastDisplayedBarcodeText.isNotBlank()) {
            binding.barcodeText.text = lastDisplayedBarcodeText
        } else {
            binding.barcodeText.text = ""
        }
    }

    private fun requestRecordingSwitch(newBarcodeText: String) {
        if (restartAfterStop) {
            return
        }
        pendingRestartBarcodeText = newBarcodeText
        restartAfterStop = true
        stopRecording(false)
    }

    private fun buildStartRecordingSpeech(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val lastFour = if (digits.length >= 4) digits.takeLast(4) else digits
        if (lastFour.isBlank()) {
            return getString(R.string.start_recording)
        }
        val spoken = lastFour.map { digitToChinese(it) }.joinToString("")
        return getString(R.string.start_recording_with_tail, spoken)
    }

    private fun digitToChinese(ch: Char): String {
        return when (ch) {
            '0' -> "零"
            '1' -> "一"
            '2' -> "二"
            '3' -> "三"
            '4' -> "四"
            '5' -> "五"
            '6' -> "六"
            '7' -> "七"
            '8' -> "八"
            '9' -> "九"
            else -> ""
        }
    }

    private fun setupFtpStatusObserver() {
        val workManager = WorkManager.getInstance(this)
        workManager.getWorkInfosForUniqueWorkLiveData("ftp_upload_queue")
            .observe(this) { infos ->
                val label = when {
                    infos.isNullOrEmpty() -> getString(R.string.ftp_status_idle)
                    infos.any { it.state == WorkInfo.State.RUNNING } -> getString(R.string.ftp_status_uploading)
                    infos.any { it.state == WorkInfo.State.ENQUEUED } -> getString(R.string.ftp_status_queued)
                    infos.any { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED } ->
                        getString(R.string.ftp_status_failed)
                    infos.all { it.state == WorkInfo.State.SUCCEEDED } -> getString(R.string.ftp_status_done)
                    else -> getString(R.string.ftp_status_idle)
                }
                binding.ftpStatusText.text = getString(R.string.ftp_status_format, label)
            }
    }

    private fun sanitizeBarcodeName(raw: String): String {
        val first = raw.split(",").firstOrNull()?.trim().orEmpty()
        val sanitized = first.replace(Regex("[^A-Za-z0-9_-]"), "")
        return sanitized.take(64)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) {
            return file
        }
        val base = name.substringBeforeLast(".")
        val ext = name.substringAfterLast(".", "")
        var index = 1
        while (file.exists()) {
            val candidate = if (ext.isNotEmpty()) {
                "${base}_$index.$ext"
            } else {
                "${base}_$index"
            }
            file = File(dir, candidate)
            index += 1
        }
        return file
    }

    private fun speak(message: String) {
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "packing_rec_tts")
    }
}
