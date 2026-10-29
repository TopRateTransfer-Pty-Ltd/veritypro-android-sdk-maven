package com.example.veritypro_sdk.utils

import android.content.Context
import android.util.Log
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.TimeUnit

enum class VerityVideoModule(val tag: String) {
    DOCUMENT("doc"),
    BIOMETRIC("selfie"),
    ADDRESS("address"),
    EDD("edd")
}

/**
 * Recording quality tier selected by device hardware level.
 * SD — Camera2 LIMITED: 480p, 800 kbps, 8 MiB cap, 90 s max.
 * HD — Camera2 FULL / LEVEL_3: 720p, 700 kbps, 15 MiB cap, 3 min max (the iOS SDK's policy:
 *      VideoRecorder.swift targetBitrate 700_000, 1280 px long edge, maxRecordedFileSize 15 MiB, 180 s).
 *
 * HD used to set no bitrate and a 30 MiB cap. CameraX then encoded 720p at its default
 * (several Mbit/s), so a document clip of a few dozen seconds passed the KYC engine's 20 MB
 * per-file limit (MAX_UPLOAD_MB, kyc_gateway app/api/upload.py:674) and the whole upload was
 * rejected with 413 "document_video exceeds 20MB" (prod 2026-10-28/29); every Android 413 came
 * from this SDK (okhttp UA). Big clips also made the multipart body 20-55 MB, which slow mobile
 * links cut off mid-upload (400 "Unexpected end of request content", 408, 499). And the cap was
 * reached after roughly half a minute, so a customer who took longer than that to press the
 * shutter got ENDED_BEFORE_STILL ([com.example.veritypro_sdk.capture.VideoEvidenceResolver]).
 */
enum class DocumentVideoTier(
    val targetBitRate: Int,
    val maxFileSizeBytes: Long,
    val maxDurationMs: Long,
) {
    SD(800_000, 8L * 1024 * 1024, TimeUnit.SECONDS.toMillis(90)),
    HD(700_000, 15L * 1024 * 1024, TimeUnit.MINUTES.toMillis(3)),
}

class VerityVideoRecorder(private val context: Context) {

    private var activeRecording: Recording? = null

    fun buildVideoCapture(tier: DocumentVideoTier = DocumentVideoTier.HD): VideoCapture<Recorder> {
        val qualitySelector = when (tier) {
            DocumentVideoTier.SD -> QualitySelector.from(
                Quality.SD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
            DocumentVideoTier.HD -> QualitySelector.from(
                Quality.HD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )
        }
        // Every tier sets its bitrate: without one CameraX picks the quality's default, which is
        // what made HD clips outgrow the engine's per-file limit (see [DocumentVideoTier]).
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setTargetVideoEncodingBitRate(tier.targetBitRate)
            .build()
        return VideoCapture.withOutput(recorder)
    }

    /**
     * Raw recording events with wall-clock times, so the caller can tell whether the clip really
     * covered the shutter. Exactly one of [onStartFailed] or [onFinalized] ends a recording.
     */
    interface Listener {
        /** VideoRecordEvent.Start: frames are now being written. */
        fun onStarted(atMs: Long)
        /** VideoRecordEvent.Finalize. [error] is the CameraX error name, null when none. */
        fun onFinalized(file: File, atMs: Long, error: String?)
        /** prepareRecording().start() threw; nothing was recorded and no Finalize will follow. */
        fun onStartFailed(reason: String, permissionDenied: Boolean)
    }

    fun startRecording(
        videoCapture: VideoCapture<Recorder>,
        module: VerityVideoModule,
        sessionId: String,
        tier: DocumentVideoTier = DocumentVideoTier.HD,
        listener: Listener,
    ) {
        val maxFileSizeBytes = tier.maxFileSizeBytes
        val maxDurationMs = tier.maxDurationMs

        val fileName = "verity_${module.tag}_${sessionId}_${System.currentTimeMillis()}.mp4"
        val file = File(context.cacheDir, fileName)
        val outputOptions = FileOutputOptions.Builder(file)
            .setFileSizeLimit(maxFileSizeBytes)
            .build()

        Log.d("VerityVideoRecorder", "startRecording: tier=$tier, maxSize=${maxFileSizeBytes / 1024 / 1024}MiB, maxDuration=${maxDurationMs / 1000}s")

        try {
            activeRecording = videoCapture.output
                .prepareRecording(context, outputOptions)
                .start(ContextCompat.getMainExecutor(context)) { event: VideoRecordEvent ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.i("VerityVideoRecorder", "Recording started: ${file.name} (tier=$tier, module=$module)")
                            listener.onStarted(System.currentTimeMillis())
                        }
                        is VideoRecordEvent.Finalize -> {
                            val at = System.currentTimeMillis()
                            val error = if (event.hasError()) finalizeErrorName(event.error) else null
                            if (error != null) {
                                Log.w("VerityVideoRecorder", "Recording finalized with $error, fileSize=${file.length()} (tier=$tier, module=$module)")
                            } else {
                                Log.i("VerityVideoRecorder", "Recording finalized OK: ${file.name} (${file.length()} bytes, tier=$tier)")
                            }
                            listener.onFinalized(file, at, error)
                        }
                        is VideoRecordEvent.Status -> {
                            val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                            if (durationMs >= maxDurationMs) {
                                Log.w("VerityVideoRecorder", "Recording hit the ${maxDurationMs / 1000}s cap — stopping")
                                activeRecording?.stop()
                            }
                        }
                        else -> {}
                    }
                }
            Log.d("VerityVideoRecorder", "prepareRecording().start() succeeded — recording pending/active")
        } catch (e: SecurityException) {
            Log.e("VerityVideoRecorder", "startRecording refused — permission (tier=$tier, module=$module): ${e.message}", e)
            activeRecording = null
            listener.onStartFailed("SecurityException", permissionDenied = true)
        } catch (e: IllegalStateException) {
            // Recorder not in IDLING state — VideoCapture was not bound to a camera session.
            Log.e("VerityVideoRecorder", "startRecording failed — Recorder not IDLING (tier=$tier, module=$module): ${e.message}", e)
            activeRecording = null
            listener.onStartFailed("RecorderNotIdle", permissionDenied = false)
        } catch (e: Exception) {
            Log.e("VerityVideoRecorder", "startRecording failed: ${e.javaClass.simpleName}: ${e.message} (tier=$tier, module=$module)", e)
            activeRecording = null
            listener.onStartFailed(e.javaClass.simpleName, permissionDenied = false)
        }
    }

    private fun finalizeErrorName(code: Int): String = when (code) {
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "ERROR_FILE_SIZE_LIMIT_REACHED"
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "ERROR_INSUFFICIENT_STORAGE"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "ERROR_SOURCE_INACTIVE"
        VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "ERROR_INVALID_OUTPUT_OPTIONS"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "ERROR_ENCODING_FAILED"
        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "ERROR_RECORDER_ERROR"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "ERROR_NO_VALID_DATA"
        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "ERROR_DURATION_LIMIT_REACHED"
        VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "ERROR_RECORDING_GARBAGE_COLLECTED"
        else -> "ERROR_$code"
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }
}
