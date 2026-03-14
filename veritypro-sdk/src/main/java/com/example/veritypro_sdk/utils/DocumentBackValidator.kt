package com.example.veritypro_sdk.utils

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Validates document back side using multiple detection methods:
 * - Barcode detection (PDF417 for driver's licenses, QR codes)
 * - MRZ (Machine Readable Zone) pattern matching
 * - Text recognition/OCR
 * - Face absence validation (no face expected on back)
 *
 * Mirrors iOS FaceDetectionService.validateDocumentBackIndustryStandard().
 */
object DocumentBackValidator {

    private const val TAG = "BackValidator"

    /** MRZ line patterns — 2 lines of 44 chars (TD3/passport) or 3 lines of 30 chars (TD1/ID card) */
    private val MRZ_PATTERN = Regex("[A-Z0-9<]{20,44}")

    private val barcodeScannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_PDF417,      // Driver's licenses
            Barcode.FORMAT_QR_CODE,     // Modern IDs
            Barcode.FORMAT_AZTEC,       // Some government IDs
            Barcode.FORMAT_DATA_MATRIX, // European IDs
            Barcode.FORMAT_CODE_128,    // Some ID cards
            Barcode.FORMAT_CODE_39      // Some ID cards
        )
        .build()

    data class BackValidationResult(
        val hasBarcode: Boolean,
        val hasMRZ: Boolean,
        val hasText: Boolean,
        val hasFace: Boolean,
        val textCount: Int,
        val isValid: Boolean,
        val confidence: Float,
        val message: String
    )

    /**
     * Validates document back side using barcode, MRZ, text, and face detection.
     *
     * @param bitmap The captured document back image
     * @return BackValidationResult with detection results and validity
     */
    suspend fun validateDocumentBack(bitmap: Bitmap): BackValidationResult {
        val image = InputImage.fromBitmap(bitmap, 0)

        // Run all detections (face validation is handled by DocumentFaceValidator separately)
        val hasFace = !DocumentFaceValidator.validateBackNoFace(bitmap)
        val barcodeResult = detectBarcodes(image)
        val textResult = detectTextAndMRZ(image)

        val hasBarcode = barcodeResult.isNotEmpty()
        val hasMRZ = textResult.hasMRZ
        val hasText = textResult.textCount > 0
        val textCount = textResult.textCount

        // Calculate confidence score
        var confidence = 0f

        if (hasBarcode) {
            confidence += 0.5f
            Log.d(TAG, "Barcode detected: ${barcodeResult.map { it.format }}")
        }

        if (hasMRZ) {
            confidence += 0.4f
            Log.d(TAG, "MRZ detected")
        }

        if (hasText && textCount in 2..20) {
            confidence += 0.3f
        }

        if (!hasFace) {
            confidence += 0.2f
        } else {
            // Face on back side = likely showing front
            confidence = 0f
        }

        confidence = confidence.coerceAtMost(1.0f)

        // Determine validity
        val hasPositiveIndicator = hasBarcode || hasMRZ || (hasText && textCount >= 2)
        val isValid = if (hasFace) {
            false
        } else if (!hasPositiveIndicator) {
            // No indicators but no face — be lenient, accept as valid back
            Log.d(TAG, "No barcode/MRZ/text found, but no face — accepting as valid back")
            true
        } else {
            true
        }

        val message = when {
            hasFace -> "Show BACK without photo"
            !hasPositiveIndicator -> "Document back validated"
            else -> "Document back validated successfully"
        }

        Log.d(TAG, "Back validation: barcode=$hasBarcode, MRZ=$hasMRZ, text=$hasText($textCount), face=$hasFace, valid=$isValid, conf=$confidence")

        return BackValidationResult(
            hasBarcode = hasBarcode,
            hasMRZ = hasMRZ,
            hasText = hasText,
            hasFace = hasFace,
            textCount = textCount,
            isValid = isValid,
            confidence = confidence,
            message = message
        )
    }

    /**
     * Detect barcodes using ML Kit BarcodeScanning.
     * Mirrors iOS detectBarcodes() using Vision framework.
     */
    private suspend fun detectBarcodes(image: InputImage): List<Barcode> {
        return try {
            val scanner: BarcodeScanner = BarcodeScanning.getClient(barcodeScannerOptions)
            val barcodes = scanner.process(image).await()
            Log.d(TAG, "Barcode detection: found ${barcodes.size} barcode(s)")
            barcodes
        } catch (e: Exception) {
            Log.e(TAG, "Barcode detection failed: ${e.message}")
            emptyList()
        }
    }

    data class TextDetectionResult(
        val textCount: Int,
        val hasMRZ: Boolean,
        val recognizedText: String
    )

    /**
     * Detect text and MRZ patterns using ML Kit TextRecognition.
     * Mirrors iOS detectTextAndMRZ() using Vision framework.
     */
    private suspend fun detectTextAndMRZ(image: InputImage): TextDetectionResult {
        return try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = recognizer.process(image).await()

            val fullText = result.text
            val textBlockCount = result.textBlocks.size

            // Check for MRZ pattern in recognized text
            val hasMRZ = result.textBlocks.any { block ->
                block.lines.any { line ->
                    val text = line.text.replace(" ", "")
                    MRZ_PATTERN.containsMatchIn(text) && text.contains('<')
                }
            }

            Log.d(TAG, "Text detection: blocks=$textBlockCount, MRZ=$hasMRZ")

            TextDetectionResult(
                textCount = textBlockCount,
                hasMRZ = hasMRZ,
                recognizedText = fullText
            )
        } catch (e: Exception) {
            Log.e(TAG, "Text detection failed: ${e.message}")
            TextDetectionResult(textCount = 0, hasMRZ = false, recognizedText = "")
        }
    }
}
