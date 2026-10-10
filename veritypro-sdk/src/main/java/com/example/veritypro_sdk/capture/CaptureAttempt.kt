package com.example.veritypro_sdk.capture

import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Document capture attempt model (contract `veritypro.capture.v1`, CONTRACT.md section 1).
 *
 * One [CaptureAttemptStore.attemptId] covers one document submission: the front and back
 * stills plus each side's video. Every still and clip is tagged with the attempt id, its side
 * and its retake index when it is produced. The store refuses anything tagged with an older
 * attempt, so a clip from an abandoned document can never be uploaded as evidence for a new one.
 */
enum class CaptureSide { FRONT, BACK }

enum class CaptureSource { AUTO, MANUAL, UPLOAD }

enum class VideoStatus { RECORDED, NOT_REQUESTED, PERMISSION_DENIED, UNSUPPORTED, FAILED }

/** Identifies which attempt, side and retake a capture belongs to. Issued by [CaptureAttemptStore.tagFor]. */
data class CaptureTag(val attemptId: String, val side: CaptureSide, val retakeIndex: Int)

/** The still as uploaded: original camera JPEG bytes, never resized or recompressed. */
data class StillCapture(
    val path: String,
    /** EXIF-corrected (upright) width. */
    val width: Int,
    /** EXIF-corrected (upright) height. */
    val height: Int,
    /** Lowercase hex sha256 over the exact bytes that are uploaded. */
    val sha256: String,
    val mimeType: String = "image/jpeg",
    val capturedAtMs: Long,
)

/**
 * What happened to this side's document video. Only [VideoStatus.RECORDED] with a non-empty file
 * is ever uploaded. A clip that failed, never started, or ended before the still is reported with
 * its status and [failureReason], and is never uploaded while claiming coverage.
 */
data class VideoEvidence(
    val status: VideoStatus,
    val path: String? = null,
    val mimeType: String? = null,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    /** Shutter moment relative to [startedAtMs]. Null when the recording never started. */
    val stillCapturedAtOffsetMs: Long? = null,
    val sha256: String? = null,
    val failureReason: String? = null,
) {
    /** The file to upload, or null. Only a RECORDED clip with bytes on disk qualifies. */
    fun uploadableFile(): File? =
        if (status == VideoStatus.RECORDED && path != null) File(path).takeIf { it.exists() && it.length() > 0 } else null

    companion object {
        const val REASON_ENDED_BEFORE_STILL = "ENDED_BEFORE_STILL"
        const val REASON_START_FAILED = "RECORDING_START_FAILED"
        const val REASON_PHOTO_FAILED = "PHOTO_FAILED_DURING_RECORDING"
        const val REASON_FINALIZE_ERROR = "RECORDING_FINALIZE_ERROR"
        const val REASON_IMAGE_QUALITY_GUARD = "VIDEO_DROPPED_TO_PROTECT_STILL"
        const val REASON_BIND_FAILED = "VIDEO_BIND_FAILED"
        const val REASON_EMPTY_FILE = "RECORDING_EMPTY"
    }
}

data class SideCapture(
    val tag: CaptureTag,
    val captureSource: CaptureSource,
    val still: StillCapture,
    val video: VideoEvidence,
    val qualityAdvisory: QualityAdvisory = QualityAdvisory.UNKNOWN,
) {
    val side: CaptureSide get() = tag.side
    val attemptId: String get() = tag.attemptId
    val retakeIndex: Int get() = tag.retakeIndex
}

/**
 * Holds the current attempt. Not thread-safe; all calls come from the main thread (Compose).
 *
 * [startNewAttempt] is the only way to get a new id, and it drops every still and clip of the
 * previous attempt (deleting their files). Use it when document capture starts, when the user
 * chooses a different document, and when the server asks for a recapture.
 */
class CaptureAttemptStore(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val deleteFile: (String) -> Unit = { path -> runCatching { File(path).delete() } },
) {
    var attemptId: String = idFactory()
        private set
    var documentType: String? = null
        private set

    private val sides = LinkedHashMap<CaptureSide, SideCapture>()
    private val shotsIssued = HashMap<CaptureSide, Int>()

    /** New attempt id; everything captured under the old one is discarded. */
    fun startNewAttempt(documentType: String?): String {
        val old = attemptId
        sides.values.forEach { discardFiles(it) }
        sides.clear()
        shotsIssued.clear()
        attemptId = idFactory()
        this.documentType = documentType
        Log.i(TAG, "New capture attempt $attemptId (was $old), documentType=$documentType")
        return attemptId
    }

    /**
     * Tag for the next shot of [side]. Retake index = how many shots of this side were already
     * issued in this attempt (0 for the first). Each camera session for a side asks for one tag.
     */
    fun tagFor(side: CaptureSide): CaptureTag {
        val index = shotsIssued[side] ?: 0
        shotsIssued[side] = index + 1
        return CaptureTag(attemptId, side, index)
    }

    /**
     * Store a finished side, replacing any earlier shot of the same side (whose files are
     * deleted). Returns false and stores nothing when the capture belongs to another attempt.
     */
    fun record(capture: SideCapture): Boolean {
        if (capture.attemptId != attemptId) {
            Log.w(TAG, "Dropping ${capture.side} capture from stale attempt ${capture.attemptId} (current $attemptId)")
            capture.video.path?.let(deleteFile)
            deleteFile(capture.still.path) // the store never holds it, so nothing else would clean it up
            return false
        }
        sides[capture.side]?.let { previous ->
            if (previous.video.path != null && previous.video.path != capture.video.path) deleteFile(previous.video.path)
            if (previous.still.path != capture.still.path) deleteFile(previous.still.path)
        }
        sides[capture.side] = capture
        return true
    }

    fun side(side: CaptureSide): SideCapture? = sides[side]

    /** Captured sides, FRONT before BACK. */
    fun captured(): List<SideCapture> = CaptureSide.values().mapNotNull { sides[it] }

    private fun discardFiles(capture: SideCapture) {
        deleteFile(capture.still.path)
        capture.video.path?.let(deleteFile)
    }

    private companion object { const val TAG = "CaptureAttempt" }
}
