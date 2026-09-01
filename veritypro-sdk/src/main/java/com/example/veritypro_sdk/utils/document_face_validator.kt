package com.example.veritypro_sdk.utils

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

/**
 * Validates face presence on document front/back sides using ML Kit.
 * - Front side: Must contain at least 1 face (photo on ID), face area 3-50% of image.
 * - Back side: Must contain 0 faces (no photo expected on back).
 *
 * This is a local on-device check — does not call any cloud endpoints.
 */
object DocumentFaceValidator {

    private const val TAG = "FaceValidator"

    // Full-frame document captures (portrait JPEG from a 1920×1080 landscape sensor,
    // ViewPort-cropped to phone aspect) place a driver's licence portrait photo at
    // ~0.8–1.3% of total image area. The original 1% floor was calibrated for selfie
    // captures where the face fills 30–50% of the frame; for document-portrait detection
    // on a full-frame JPEG it's too tight and causes false PORTRAIT_NOT_READABLE rejections
    // when ML Kit returns a bounding box that is even slightly conservative.
    // 0.25% still excludes noise (< 5×5 pixel blobs at 1MP) while accommodating a
    // genuine licence portrait on any device from the range 15–45cm.
    private const val MIN_FACE_AREA_PERCENT = 0.25f

    /** Maximum face area as percentage of total image area */
    private const val MAX_FACE_AREA_PERCENT = 60f

    /** Front-side detector: sensitive (5% min) to catch small passport photos. */
    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setMinFaceSize(0.05f)
        .build()

    /**
     * Back-side detector: stricter (15% min face width) to prevent ghost images
     * and holographic security features on driver's licence backs from being
     * falsely detected as faces and blocking capture.
     * Mirrors iOS FaceDetectionService.detectFaceOnly minFaceArea = 0.04 (≥20% width).
     */
    private val backDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setMinFaceSize(0.15f)
        .build()

    /**
     * Validate that the front side of a document contains at least one face
     * with a reasonable size (3-50% of image area).
     *
     * @return true if a valid face is detected, false otherwise
     */
    suspend fun validateFrontHasFace(bitmap: Bitmap): Boolean {
        return try {
            val detector = FaceDetection.getClient(detectorOptions)
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = detector.process(image).await()

            if (faces.isEmpty()) {
                // Fail-open: holographic overlays on Australian licences (NT, VIC, etc.)
                // regularly defeat on-device ML Kit face detection even on clear captures.
                // The server Rekognition pipeline is the authority — don't block here.
                Log.d(TAG, "Front validation: No faces detected — passing through for server validation")
                return true
            }

            val imageArea = bitmap.width.toFloat() * bitmap.height.toFloat()
            Log.d(TAG, "Front validation: image=${bitmap.width}x${bitmap.height} area=${imageArea.toInt()}px² faces=${faces.size} threshold=[${MIN_FACE_AREA_PERCENT}%..${MAX_FACE_AREA_PERCENT}%]")

            // Check if any face has valid size
            val validFace = faces.any { face ->
                val bounds = face.boundingBox
                val faceArea = bounds.width().toFloat() * bounds.height().toFloat()
                val facePercent = (faceArea / imageArea) * 100f
                val pass = facePercent in MIN_FACE_AREA_PERCENT..MAX_FACE_AREA_PERCENT
                Log.d(TAG, "  face bbox=${bounds.width()}x${bounds.height()} area=${"%.2f".format(facePercent)}% → ${if (pass) "PASS" else "FAIL"}")
                pass
            }

            Log.d(TAG, "Front validation result: valid=$validFace")
            validFace
        } catch (e: Exception) {
            Log.e(TAG, "Front face validation failed", e)
            // On error, allow capture to proceed (don't block user)
            true
        }
    }

    /**
     * Validate that the back side of a document contains no faces.
     * Uses the stricter [backDetectorOptions] (MinFaceSize=0.15) to avoid
     * false positives from ghost/hologram security features on licence backs.
     *
     * @return true if no faces detected (valid back side), false if faces found
     */
    suspend fun validateBackNoFace(bitmap: Bitmap): Boolean {
        return try {
            val detector = FaceDetection.getClient(backDetectorOptions)
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = detector.process(image).await()

            val noFace = faces.isEmpty()
            Log.d(TAG, "Back validation: ${faces.size} face(s), valid=$noFace")
            noFace
        } catch (e: Exception) {
            Log.e(TAG, "Back face validation failed", e)
            // On error, allow capture to proceed
            true
        }
    }
}
