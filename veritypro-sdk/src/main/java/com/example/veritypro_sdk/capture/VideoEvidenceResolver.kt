package com.example.veritypro_sdk.capture

import java.io.File

/** How the video use case ended up for one shot, decided before the shutter. */
enum class VideoBinding {
    /** Video was never asked for on this shot. */
    NOT_REQUESTED,
    /** VideoCapture bound; a recording was (to be) started. */
    BOUND,
    /** The device cannot record alongside a full-quality still (IMAGE_QUALITY_GUARD dropped it). */
    UNSUPPORTED,
    /** Binding the video use case failed. */
    BIND_FAILED,
    /** Recording was refused for lack of a runtime permission. */
    PERMISSION_DENIED,
}

/**
 * Everything the capture screen observed about one side's recording, as raw facts.
 * Timestamps are wall-clock epoch milliseconds.
 */
data class RecordingTimeline(
    val binding: VideoBinding,
    val filePath: String? = null,
    /** VideoRecordEvent.Start arrived at this time. Null = the recording never started. */
    val startedAtMs: Long? = null,
    /** Set when prepareRecording().start() threw or reported failure. */
    val startFailure: String? = null,
    /** VideoRecordEvent.Finalize arrived at this time. */
    val endedAtMs: Long? = null,
    /** Finalize error name (CameraX VideoRecordEvent.Finalize.error), null when none. */
    val finalizeError: String? = null,
    val bindFailureReason: String? = null,
    /**
     * LIMITED/LEGACY (SEQUENTIAL) capture: the shutter stops the clip and the still is taken after
     * it, so the clip does not contain the still. Reported, not hidden (review #61).
     */
    val sequentialStillAfterClip: Boolean = false,
    /**
     * The shutter was pressed before this side's recording had been started. The side is delivered
     * without a clip and no recording may start afterwards (review #61).
     */
    val shutterBeforeRecording: Boolean = false,
)

/** Raw output of one document camera session, before hashing and advisory. */
data class RawSideCapture(
    val tag: CaptureTag,
    val stillPath: String,
    /** Shutter pressed. The clip must still be running at this moment to count. */
    val shutterAtMs: Long,
    /** ImageCapture reported the JPEG saved. */
    val stillSavedAtMs: Long,
    val recording: RecordingTimeline,
    val captureSource: CaptureSource = CaptureSource.MANUAL,
)

/**
 * Turns a [RecordingTimeline] into the contract's video status. A clip counts as RECORDED only when
 * it actually started before the shutter, was still running at the shutter, and left bytes on disk.
 */
object VideoEvidenceResolver {
    const val MIME_MP4 = "video/mp4"
    const val REASON_NOT_RECORDING_AT_SHUTTER = "NOT_RECORDING_AT_SHUTTER"
    /** RECORDED clip from the sequential path: real, but stopped before the still was taken. */
    const val REASON_SEQUENTIAL_STILL_AFTER_CLIP = "SEQUENTIAL_STILL_AFTER_CLIP"
    const val REASON_SHUTTER_BEFORE_RECORDING = "SHUTTER_BEFORE_RECORDING"

    fun resolve(
        t: RecordingTimeline,
        shutterAtMs: Long,
        fileSize: (String) -> Long = { File(it).let { f -> if (f.exists()) f.length() else 0L } },
        sha256: (String) -> String = { CaptureFiles.sha256(File(it)) },
    ): VideoEvidence {
        // Measured from the shutter press, never from the JPEG save: on the sequential path the save
        // lands after the clip has already ended, which would place the still outside the clip.
        val offset = t.startedAtMs?.let { shutterAtMs - it }
        fun failed(reason: String) = VideoEvidence(
            status = VideoStatus.FAILED, startedAtMs = t.startedAtMs, endedAtMs = t.endedAtMs,
            stillCapturedAtOffsetMs = offset, failureReason = reason,
        )
        if (t.shutterBeforeRecording) return failed(REASON_SHUTTER_BEFORE_RECORDING)
        return when (t.binding) {
            VideoBinding.NOT_REQUESTED -> VideoEvidence(VideoStatus.NOT_REQUESTED)
            VideoBinding.PERMISSION_DENIED -> VideoEvidence(VideoStatus.PERMISSION_DENIED, failureReason = t.bindFailureReason)
            VideoBinding.UNSUPPORTED -> VideoEvidence(
                VideoStatus.UNSUPPORTED, failureReason = t.bindFailureReason ?: VideoEvidence.REASON_IMAGE_QUALITY_GUARD,
            )
            VideoBinding.BIND_FAILED -> failed(t.bindFailureReason ?: VideoEvidence.REASON_BIND_FAILED)
            VideoBinding.BOUND -> when {
                t.startFailure != null -> failed("${VideoEvidence.REASON_START_FAILED}: ${t.startFailure}")
                t.startedAtMs == null -> failed(VideoEvidence.REASON_START_FAILED)
                t.startedAtMs > shutterAtMs -> failed(REASON_NOT_RECORDING_AT_SHUTTER)
                t.endedAtMs == null -> failed(VideoEvidence.REASON_FINALIZE_ERROR)
                t.endedAtMs < shutterAtMs -> failed(VideoEvidence.REASON_ENDED_BEFORE_STILL)
                t.filePath == null || fileSize(t.filePath) <= 0L ->
                    failed(t.finalizeError?.let { "${VideoEvidence.REASON_FINALIZE_ERROR}: $it" } ?: VideoEvidence.REASON_EMPTY_FILE)
                else -> VideoEvidence(
                    status = VideoStatus.RECORDED,
                    path = t.filePath,
                    mimeType = MIME_MP4,
                    startedAtMs = t.startedAtMs,
                    endedAtMs = t.endedAtMs,
                    stillCapturedAtOffsetMs = offset,
                    sha256 = sha256(t.filePath),
                    // A finalize error with a playable file (e.g. size limit reached after the
                    // shutter) is still a real clip; the error is reported, not hidden. The
                    // sequential marker says the still was taken after this clip stopped.
                    failureReason = listOfNotNull(
                        REASON_SEQUENTIAL_STILL_AFTER_CLIP.takeIf { t.sequentialStillAfterClip },
                        t.finalizeError,
                    ).joinToString("; ").ifEmpty { null },
                )
            }
        }
    }
}

/** Builds the stored [SideCapture]: hashes the still and clip and runs the advisory. Does file I/O. */
object SideCaptureAssembler {
    fun assemble(raw: RawSideCapture): SideCapture {
        val still = try {
            CaptureFiles.describeStill(raw.stillPath, raw.stillSavedAtMs)
        } catch (e: Exception) {
            // The side is lost; its clip will never be uploaded, so it must not stay on disk (review #61).
            raw.recording.filePath?.let { runCatching { File(it).delete() } }
            throw e
        }
        val video = VideoEvidenceResolver.resolve(raw.recording, raw.shutterAtMs)
        if (video.status != VideoStatus.RECORDED) {
            // Never leave a clip on disk that we are not going to upload or account for.
            raw.recording.filePath?.let { runCatching { File(it).delete() } }
        }
        val advisory = ImageQualityAdvisor.assessFile(raw.stillPath)
        return SideCapture(raw.tag, raw.captureSource, still, video, advisory)
    }
}
