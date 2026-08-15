package com.example.veritypro_sdk.services

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * TDD/regression tests for [File.toMultipartBodyPart].
 *
 * R1 (REGRESSION): MIME type must be "image/jpeg", not "image/*".
 *   image/* caused server-side 400 rejections on document upload.
 *   PR #17 (fix/burst-payload-downscale) fixes this — these tests are the
 *   guard that prevents regressing back to image/*.
 */
class VerificationRequestTest {

    @TempDir
    lateinit var tempDir: Path

    private fun tmpFile(name: String, content: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())): File {
        val file = tempDir.resolve(name).toFile()
        file.writeBytes(content)
        return file
    }

    // ── MIME type ─────────────────────────────────────────────────────────────

    @Test
    fun `REGRESSION R1 - MIME type is image_jpeg not image_wildcard`() {
        val file = tmpFile("front.jpg")
        val part = file.toMultipartBodyPart("DocumentFront")

        val contentType = part.body.contentType()
        assertNotNull(contentType, "Content-Type must not be null")
        assertEquals("image", contentType!!.type, "Type must be 'image'")
        assertEquals("jpeg", contentType.subtype, "Subtype must be 'jpeg', not '*'")
    }

    @Test
    fun `content-type string is exactly image_jpeg`() {
        val file = tmpFile("doc.jpg")
        val part = file.toMultipartBodyPart("DocumentFront")
        assertEquals("image/jpeg", part.body.contentType().toString())
    }

    // ── Part name ─────────────────────────────────────────────────────────────

    @Test
    fun `part name matches caller-supplied name`() {
        val file = tmpFile("portrait.jpg")
        val part = file.toMultipartBodyPart("PortraitPicture")

        val headers = part.headers
        assertNotNull(headers)
        val disposition = headers!!.get("Content-Disposition") ?: ""
        assert(disposition.contains("PortraitPicture")) {
            "Expected Content-Disposition to contain 'PortraitPicture' but was: $disposition"
        }
    }

    @Test
    fun `part name for DocumentBack`() {
        val file = tmpFile("back.jpg")
        val part = file.toMultipartBodyPart("DocumentBack")

        val disposition = part.headers?.get("Content-Disposition") ?: ""
        assert(disposition.contains("DocumentBack")) {
            "Expected 'DocumentBack' in disposition: $disposition"
        }
    }

    // ── Filename ──────────────────────────────────────────────────────────────

    @Test
    fun `filename matches the file's own name`() {
        val file = tmpFile("document_front.jpg")
        val part = file.toMultipartBodyPart("DocumentFront")

        val disposition = part.headers?.get("Content-Disposition") ?: ""
        assert(disposition.contains("document_front.jpg")) {
            "Expected filename 'document_front.jpg' in disposition: $disposition"
        }
    }

    // ── Body content ──────────────────────────────────────────────────────────

    @Test
    fun `body content matches original file bytes`() {
        val content = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xAA.toByte())
        val file = tmpFile("test.jpg", content)
        val part = file.toMultipartBodyPart("DocumentFront")

        val buffer = Buffer()
        part.body.writeTo(buffer)
        val readBytes = buffer.readByteArray()

        assert(readBytes.contentEquals(content)) {
            "Body bytes do not match original file content"
        }
    }

    @Test
    fun `content-length matches file size`() {
        val content = ByteArray(1024) { it.toByte() }
        val file = tmpFile("big.jpg", content)
        val part = file.toMultipartBodyPart("DocumentFront")

        assertEquals(1024L, part.body.contentLength())
    }

    // ── All three document parts ───────────────────────────────────────────────

    @Test
    fun `all three document parts use image_jpeg`() {
        val front = tmpFile("front.jpg")
        val back = tmpFile("back.jpg")
        val portrait = tmpFile("portrait.jpg")

        for ((name, file) in listOf(
            "DocumentFront" to front,
            "DocumentBack" to back,
            "PortraitPicture" to portrait,
        )) {
            val part = file.toMultipartBodyPart(name)
            assertEquals(
                "image/jpeg",
                part.body.contentType().toString(),
                "$name must use image/jpeg",
            )
        }
    }
}
