package com.example.veritypro_sdk.ui.verification

/**
 * Runtime switch for the V2 server-driven capture path.
 * Default true — V2 (POST /docai/v2/kyc/doc/capture-verify) is the primary path.
 * V1 fallback retained for emergency rollback only.
 */
object V2CaptureConfig {
    var useV2CaptureVerify: Boolean = true
}
