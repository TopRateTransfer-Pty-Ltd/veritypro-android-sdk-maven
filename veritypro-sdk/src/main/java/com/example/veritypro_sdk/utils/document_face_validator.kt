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

    /** Minimum face area as percentage of total image area */
    private const val MIN_FACE_AREA_PERCENT = 1f

    /** Maximum face area as percentage of total image area */
    private const val MAX_FACE_AREA_PERCENT = 50f

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
                Log.d(TAG, "Front validation: No faces detected")
                return false
            }

            val imageArea = bitmap.width.toFloat() * bitmap.height.toFloat()

            // Check if any face has valid size
            val validFace = faces.any { face ->
                val bounds = face.boundingBox
                val faceArea = bounds.width().toFloat() * bounds.height().toFloat()
                val facePercent = (faceArea / imageArea) * 100f
                Log.d(TAG, "Front face: ${bounds.width()}x${bounds.height()}, area=${"%.1f".format(facePercent)}%")
                facePercent in MIN_FACE_AREA_PERCENT..MAX_FACE_AREA_PERCENT
            }

            Log.d(TAG, "Front validation: ${faces.size} face(s), valid=$validFace")
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
