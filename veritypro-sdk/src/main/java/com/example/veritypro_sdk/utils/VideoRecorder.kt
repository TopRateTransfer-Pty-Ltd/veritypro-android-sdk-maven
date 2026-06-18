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

class VerityVideoRecorder(private val context: Context) {

    private var activeRecording: Recording? = null
    private val maxDurationMs = TimeUnit.MINUTES.toMillis(3)
    private val maxFileSizeBytes = 30L * 1024 * 1024

    fun buildVideoCapture(): VideoCapture<Recorder> {
        val qualitySelector = QualitySelector.from(
            Quality.HD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        return VideoCapture.withOutput(recorder)
    }

    fun startRecording(
        videoCapture: VideoCapture<Recorder>,
        module: VerityVideoModule,
        sessionId: String,
        onStopped: (File?) -> Unit
    ) {
        val fileName = "verity_${module.tag}_${sessionId}_${System.currentTimeMillis()}.mp4"
        val file = File(context.cacheDir, fileName)

        val outputOptions = FileOutputOptions.Builder(file)
            .setFileSizeLimit(maxFileSizeBytes)
            .build()

        try {
            activeRecording = videoCapture.output
                .prepareRecording(context, outputOptions)
                .start(ContextCompat.getMainExecutor(context)) { event: VideoRecordEvent ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.d("VerityVideoRecorder", "Recording started: ${file.name}")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                Log.w("VerityVideoRecorder", "Recording error: code=${event.error}, fileSize=${file.length()}")
                                onStopped(if (file.length() > 0L) file else null)
                            } else {
                                Log.d("VerityVideoRecorder", "Recording finalized OK: ${file.name} (${file.length()} bytes)")
                                onStopped(file)
                            }
                        }
                        is VideoRecordEvent.Status -> {
                            val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                            if (durationMs >= maxDurationMs) {
                                activeRecording?.stop()
                            }
                        }
                        else -> {}
                    }
                }
            Log.d("VerityVideoRecorder", "prepareRecording().start() succeeded — recording pending/active")
        } catch (e: Exception) {
            Log.e("VerityVideoRecorder", "startRecording failed: ${e.javaClass.simpleName}: ${e.message}", e)
            activeRecording = null
        }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }
}
