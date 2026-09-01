package com.example.veritypro_sdk.ui.verification

/**
 * Runtime switch for the V2 server-driven capture path.
 * Default true — V2 (POST /docai/v2/kyc/doc/capture-verify) is the primary path.
 * V1 rollback requires a new SDK release, not a runtime flag.
 */
object V2CaptureConfig {
    val useV2CaptureVerify: Boolean = true
}
