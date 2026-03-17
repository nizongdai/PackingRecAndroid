package com.packingrec.android

import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class BarcodeAnalyzer(
    private val onBarcodeResult: (String, Long, Boolean) -> Unit,
    private val loadSettings: () -> DetectionSettings
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E
            )
            .build()
    )
    private val busy = AtomicBoolean(false)
    private var lastAnalyzedAt = 0L
    private var lastSampledLuma: IntArray? = null

    override fun analyze(image: ImageProxy) {
        val settings = loadSettings()
        val now = System.currentTimeMillis()
        if (now - lastAnalyzedAt < settings.scanIntervalMs || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastAnalyzedAt = now

        val mediaImage = image.image
        if (mediaImage == null) {
            busy.set(false)
            image.close()
            return
        }

        val cropRect = cropCenter(
            image.width,
            image.height,
            settings.regionWidthRatio,
            settings.regionHeightRatio
        )
        image.setCropRect(cropRect)
        val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)

        val hasMotion = detectMotion(image, cropRect)
        scanner.process(inputImage)
            .addOnSuccessListener { result ->
                val values = result.mapNotNull { it.rawValue }
                val merged = values.joinToString(",")
                onBarcodeResult(merged, now, hasMotion)
            }
            .addOnFailureListener {
                onBarcodeResult("", now, hasMotion)
            }
            .addOnCompleteListener {
                busy.set(false)
                image.close()
            }
    }

    private fun detectMotion(image: ImageProxy, cropRect: Rect): Boolean {
        val plane = image.planes.firstOrNull() ?: return true
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val step = 16
        val samples = ArrayList<Int>()
        var y = cropRect.top
        while (y < cropRect.bottom) {
            val rowStart = y * rowStride
            var x = cropRect.left
            while (x < cropRect.right) {
                val index = rowStart + x * pixelStride
                if (index >= 0 && index < buffer.limit()) {
                    samples.add(buffer.get(index).toInt() and 0xFF)
                }
                x += step
            }
            y += step
        }
        val current = samples.toIntArray()
        val previous = lastSampledLuma
        lastSampledLuma = current
        if (previous == null || previous.size != current.size || current.isEmpty()) {
            return true
        }
        var diffSum = 0
        for (i in current.indices) {
            diffSum += kotlin.math.abs(current[i] - previous[i])
        }
        val avgDiff = diffSum / current.size.toFloat()
        return avgDiff >= 8f
    }

    private fun cropCenter(
        width: Int,
        height: Int,
        widthRatio: Float,
        heightRatio: Float
    ): Rect {
        val cropWidth = (width * widthRatio).toInt().coerceAtLeast(1)
        val cropHeight = (height * heightRatio).toInt().coerceAtLeast(1)
        val left = ((width - cropWidth) / 2).coerceAtLeast(0)
        val top = ((height - cropHeight) / 2).coerceAtLeast(0)
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }
}
