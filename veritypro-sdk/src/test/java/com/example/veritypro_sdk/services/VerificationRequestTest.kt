package com.example.veritypro_sdk.services

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

// TDD/regression tests for File.toMultipartBodyPart.
//
// R1 (REGRESSION): MIME type must be "image/jpeg", not the wildcard "image/star".
//   Using a wildcard MIME type caused server-side 400 rejections on document upload.
//   PR #17 (fix/burst-payload-downscale) fixes this — these tests guard against regression.
@RunWith(JUnit4::class)
class VerificationRequestTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun tmpFile(name: String, content: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())): File {
        val file = tempFolder.newFile(name)
        file.writeBytes(content)
        return file
    }

    // ── MIME type ─────────────────────────────────────────────────────────────

    @Test
    fun `REGRESSION R1 - MIME type is image_jpeg not image_wildcard`() {
        val file = tmpFile("front.jpg")
        val part = file.toMultipartBodyPart("DocumentFront")

        val contentType = part.body.contentType()
        assertNotNull("Content-Type must not be null", contentType)
        assertEquals("Type must be 'image'", "image", contentType!!.type)
        assertEquals("Subtype must be 'jpeg', not '*'", "jpeg", contentType.subtype)
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
    fun `filename matches the file own name`() {
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
                "$name must use image/jpeg",
                "image/jpeg",
                part.body.contentType().toString(),
            )
        }
    }
}
