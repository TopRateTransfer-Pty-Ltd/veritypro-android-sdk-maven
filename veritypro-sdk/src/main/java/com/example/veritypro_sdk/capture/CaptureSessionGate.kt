package com.example.veritypro_sdk.capture

import java.io.File

/**
 * Decides whether a capture result may still be used (review #61).
 *
 * Each time the camera opens for a side it takes a token with [open]; opening again supersedes it.
 * Closing the camera calls [close], which invalidates that token. A photo that is still being saved or
 * processed when the camera closes then fails [isCurrent] and must be discarded instead of
 * navigating to "Check your photo". Not thread-safe; used from the main thread.
 */
class CaptureSessionGate(
    private val deleteFile: (String) -> Unit = { path -> runCatching { File(path).delete() } },
) {
    private var epoch = 0
    private var openEpoch: Int? = null

    /** Camera opened: returns the token this session's results carry. */
    fun open(): Int {
        epoch++
        openEpoch = epoch
        return epoch
    }

    /**
     * The camera session [token] ended (closed by the user, or the screen left). Its results are no
     * longer wanted. A token that is no longer the open one is ignored, so a late dispose of an old
     * session can never invalidate a newer one.
     */
    fun close(token: Int) {
        if (openEpoch == token) openEpoch = null
    }

    fun isCurrent(token: Int): Boolean = openEpoch == token

    /** Delete the files of a result nobody will use (it is never recorded or uploaded). */
    fun discard(raw: RawSideCapture) {
        deleteFile(raw.stillPath)
        raw.recording.filePath?.let(deleteFile)
    }

    fun discard(capture: SideCapture) {
        deleteFile(capture.still.path)
        capture.video.path?.let(deleteFile)
    }
}
