package com.example.veritypro_sdk.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Brings a captured document still to the size that is uploaded.
 *
 * The camera saves ~8 MP JPEGs of 2–3 MB (device test 2026-10-27: 2.5 MB + 2.2 MB per licence, ~14 s
 * of a 41 s submit spent uploading). The engine forwards the image unchanged to the vision model,
 * which down-samples high-detail input to fit 2048 px, so pixels beyond ~2560 on the long edge are
 * uploaded and then discarded. AWS Rekognition also caps image bytes at 5 MB.
 *
 * Runs once, before [CaptureFiles.describeStill] hashes the file, so the sha256 in the capture
 * metadata is over the bytes actually uploaded. Rotation from EXIF is baked into the pixels and the
 * tag reset, because re-encoding through a Bitmap does not carry the orientation tag.
 */
object StillUploadSizer {
    const val MAX_LONG_EDGE_PX = 2560
    const val JPEG_QUALITY = 90
    private const val TAG = "StillUploadSizer"

    /** Upright target size for a [width]x[height] image so its long edge is at most [maxEdge]. */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_LONG_EDGE_PX): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid image size ${width}x$height" }
        val long = max(width, height)
        if (long <= maxEdge) return width to height
        val s = maxEdge.toDouble() / long
        return (width * s).roundToInt().coerceAtLeast(1) to (height * s).roundToInt().coerceAtLeast(1)
    }

    /** Largest power-of-two decode sample that still leaves the long edge >= [maxEdge]. */
    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int = MAX_LONG_EDGE_PX): Int {
        var sample = 1
        val long = max(width, height)
        while (long / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /**
     * Re-encodes the still at [path] in place when its long edge exceeds [MAX_LONG_EDGE_PX].
     * Returns true when the file was rewritten. Throws when the image cannot be read or written:
     * an unreadable still must fail the side (the caller retakes it), never be uploaded as-is.
     */
    fun fitForUpload(path: String): Boolean {
        val file = File(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Captured still is not a decodable image" }
        if (max(bounds.outWidth, bounds.outHeight) <= MAX_LONG_EDGE_PX) return false

        val before = file.length()
        val degrees = ExifOrientation.degrees(path)
        val decoded = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) },
        ) ?: throw IllegalStateException("Captured still could not be decoded for resizing")

        val (uw, uh) = ExifOrientation.uprightSize(bounds.outWidth, bounds.outHeight, degrees)
        val (tw, th) = targetSize(uw, uh)
        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
            // Scale relative to the decoded (possibly sub-sampled) bitmap's upright size.
            val (dw, dh) = ExifOrientation.uprightSize(decoded.width, decoded.height, degrees)
            postScale(tw.toFloat() / dw, th.toFloat() / dh)
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val outW = out.width
        val outH = out.height
        try {
            val tmp = File(file.parentFile, file.name + ".sizing")
            tmp.outputStream().use { os ->
                check(out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)) { "JPEG encode failed" }
            }
            ExifInterface(tmp.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
            check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) { "Could not replace captured still" }
        } finally {
            if (out !== decoded) out.recycle()
            decoded.recycle()
        }
        Log.i(
            TAG,
            "Still resized for upload: ${uw}x$uh ${before}B -> ${outW}x$outH ${file.length()}B (q=$JPEG_QUALITY)",
        )
        return true
    }
}
