package com.example.veritypro_sdk.utils

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document video tiers against the limits on the upload path. HD used to set no bitrate and
 * a 30 MiB cap: its clips passed the KYC engine's 20 MB per-file limit and the whole upload was
 * rejected (prod 413 "document_video exceeds 20MB", 2026-10-28/29).
 */
class DocumentVideoTierLimitsTest {

    // kyc_gateway app/api/upload.py:674 — MAX_UPLOAD_MB * 1024 * 1024, MAX_UPLOAD_MB=20 on prod.
    private val enginePerFileBytes = 20L * 1024 * 1024
    // nginx client_max_body_size 50M (api.veritypro.ai) = VerityPro gateway Kestrel limit.
    private val requestBodyBytes = 50L * 1024 * 1024
    // Document stills (2560 px JPEG q90, 0.8-1.6 MB each) plus form fields, generously.
    private val nonVideoPartsBytes = 8L * 1024 * 1024

    @Test
    fun `every tier's clip fits the engine's per-file limit`() {
        for (tier in DocumentVideoTier.values()) {
            assertTrue("$tier cap ${tier.maxFileSizeBytes}", tier.maxFileSizeBytes < enginePerFileBytes)
        }
    }

    @Test
    fun `front and back clips together fit one upload request`() {
        for (tier in DocumentVideoTier.values()) {
            assertTrue("$tier", 2 * tier.maxFileSizeBytes + nonVideoPartsBytes < requestBodyBytes)
        }
    }

    @Test
    fun `every tier sets a bitrate, and HD matches the iOS SDK policy`() {
        for (tier in DocumentVideoTier.values()) assertTrue("$tier", tier.targetBitRate > 0)
        // verity-pro-ios VideoRecorder.swift: targetBitrate 700_000, maxRecordedFileSize 15 MiB, 180 s.
        assertTrue(DocumentVideoTier.HD.targetBitRate <= 700_000)
        assertTrue(DocumentVideoTier.HD.maxFileSizeBytes <= 15L * 1024 * 1024)
    }

    @Test
    fun `at its bitrate a tier records for most of its duration before the size cap ends the clip`() {
        // A clip that ends on its own before the shutter is ENDED_BEFORE_STILL (VideoEvidenceResolver),
        // so the size cap must not cut the clip short. HD's old ~8 Mbit/s default hit 30 MiB in ~30 s.
        for (tier in DocumentVideoTier.values()) {
            val secondsToCap = tier.maxFileSizeBytes * 8.0 / tier.targetBitRate
            assertTrue("$tier reaches its cap after ${secondsToCap}s", secondsToCap >= 80.0)
        }
    }
}
