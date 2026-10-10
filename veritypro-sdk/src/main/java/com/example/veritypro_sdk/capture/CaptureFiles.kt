package com.example.veritypro_sdk.capture

import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object CaptureFiles {
    /** Lowercase hex sha256 of a stream, read to the end. */
    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** sha256 over the exact bytes of [file] — the same bytes the multipart body streams. */
    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    /**
     * Describe the still at [path] as it will be uploaded: untouched bytes, their sha256, and the
     * EXIF-corrected size. Throws when the file is missing or not a decodable image, because a
     * still we cannot describe must not be submitted as if it were fine.
     */
    fun describeStill(path: String, capturedAtMs: Long): StillCapture {
        val file = File(path)
        require(file.exists() && file.length() > 0) { "Captured still is missing or empty" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Captured still is not a decodable image" }
        val (w, h) = ExifOrientation.uprightSize(bounds.outWidth, bounds.outHeight, ExifOrientation.degrees(path))
        return StillCapture(path = path, width = w, height = h, sha256 = sha256(file), capturedAtMs = capturedAtMs)
    }
}
