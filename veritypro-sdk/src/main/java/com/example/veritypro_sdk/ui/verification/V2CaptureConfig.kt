package com.example.veritypro_sdk.ui.verification

/**
 * Configuration object for V2 capture verification behaviour.
 *
 * [useV2CaptureVerify] is declared as a [val] (immutable). A host app MUST NOT be able
 * to disable anti-spoof capture at runtime by mutating this flag. Emergency rollback of
 * the V2 capture path must go through a new SDK release, not an app-controlled flag.
 */
object V2CaptureConfig {
    val useV2CaptureVerify: Boolean = true
}
