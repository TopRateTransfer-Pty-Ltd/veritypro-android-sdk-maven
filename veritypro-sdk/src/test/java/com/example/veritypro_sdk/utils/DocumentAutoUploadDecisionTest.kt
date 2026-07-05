/**
 * DEF-1 — Regression test for the Bug 2 (document-only auto-upload) fix.
 *
 * Bug 2: In [VerityMode.DOCUMENT] there is no liveness/SELFIE_CAPTURE stage, so the
 * biometric-path `updateKyc` (fired after SELFIE_CAPTURE in `verification_start.kt`)
 * never runs and the captured document is never uploaded — the backend sees
 * `hasDocuments:false` and the session is eventually auto-declined.
 *
 * The fix fires the document upload from the document-capture/RESULT path, but ONLY
 * when the flow has no biometric stage (otherwise BIOMETRIC / COMBINED flows would
 * double-upload). That discriminator is [VerificationFlowRouter.shouldAutoUploadDocument]
 * (== `!isModuleEnabled(BIOMETRIC)`), which these tests assert directly against the
 * production code — not a duplicated copy of the logic.
 *
 * What is covered here (pure-logic, JVM unit test — no Robolectric/Compose needed):
 *  - In [VerityMode.DOCUMENT] the auto-upload path IS taken (decision == true).
 *  - In [VerityMode.LIVENESS_ONLY] the auto-upload path is NOT taken (decision == false).
 *  - BIOMETRIC and COMBINED (biometric-bearing) flows do NOT auto-upload (no double upload).
 *  - Legacy biometric-absent module sets (DOCUMENT+ADDRESS / DOCUMENT+EDD) DO auto-upload.
 *  - The "exactly once" invariant: when the production discriminator is true, the guarded
 *    fire happens on the first RESULT pass and never again; when it is false it never fires.
 *
 * What remains DEVICE / INSTRUMENTATION-ONLY (documented, not stubbed):
 *  The literal fire-once latch in `verification_start.kt` is a Compose `remember`
 *  `mutableStateOf` (`docOnlyUploadFired`) evaluated inside a composable callback, and the
 *  real upload is an `ApiRepository.updateKyc` multipart call. Exercising that exact latch +
 *  multipart construction end-to-end requires a Compose-UI / Robolectric harness, which is
 *  NOT set up in this module (`src/androidTest` has no Compose-UI test wiring for this flow).
 *  The `firesExactlyOnce...` test below reproduces the SAME guard expression the production
 *  code uses (`shouldAutoUploadDocument && !alreadyFired`) driven by the REAL production
 *  discriminator value, so the decision + once-semantics are verified at the logic level.
 */
package com.example.veritypro_sdk.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAutoUploadDecisionTest {

    // ── Per-mode discriminator: DOCUMENT uploads, LIVENESS_ONLY does not ──────────

    @Test
    fun `DOCUMENT mode takes the auto-upload path`() {
        val router = VerificationFlowRouter(VerityMode.DOCUMENT)
        assertTrue(
            "DOCUMENT has no biometric stage, so the doc-capture path must auto-upload",
            router.shouldAutoUploadDocument()
        )
    }

    @Test
    fun `LIVENESS_ONLY mode does NOT take the auto-upload path`() {
        val router = VerificationFlowRouter(VerityMode.LIVENESS_ONLY)
        assertFalse(
            "LIVENESS_ONLY is biometric-bearing (and captures no document) — must not auto-upload",
            router.shouldAutoUploadDocument()
        )
    }

    @Test
    fun `BIOMETRIC mode does NOT auto-upload (liveness path already uploads)`() {
        val router = VerificationFlowRouter(VerityMode.BIOMETRIC)
        assertFalse(
            "BIOMETRIC uploads after SELFIE_CAPTURE — auto-uploading again would double-upload",
            router.shouldAutoUploadDocument()
        )
    }

    @Test
    fun `COMBINED mode does NOT auto-upload (liveness path already uploads)`() {
        val router = VerificationFlowRouter(VerityMode.COMBINED)
        assertFalse(router.shouldAutoUploadDocument())
    }

    @Test
    fun `ADDRESS and EDD modes report auto-upload true but have no document to send`() {
        // Biometric-absent, so the discriminator is true; the call-site documentFrontPage
        // null-guard prevents an actual upload. This matches the legacy behaviour exactly.
        assertTrue(VerificationFlowRouter(VerityMode.ADDRESS).shouldAutoUploadDocument())
        assertTrue(VerificationFlowRouter(VerityMode.EDD).shouldAutoUploadDocument())
    }

    // ── Legacy module-set flows (no VerityMode) ──────────────────────────────────

    @Test
    fun `legacy DOCUMENT-only module set auto-uploads`() {
        val router = VerificationFlowRouter(setOf(VerificationModule.DOCUMENT))
        assertTrue(router.shouldAutoUploadDocument())
    }

    @Test
    fun `legacy DOCUMENT plus ADDRESS (no biometric) auto-uploads`() {
        val router = VerificationFlowRouter(
            setOf(VerificationModule.DOCUMENT, VerificationModule.ADDRESS)
        )
        assertTrue(
            "No biometric stage in this legacy set — document must upload from doc-capture path",
            router.shouldAutoUploadDocument()
        )
    }

    @Test
    fun `legacy DOCUMENT plus EDD (no biometric) auto-uploads`() {
        val router = VerificationFlowRouter(
            setOf(VerificationModule.DOCUMENT, VerificationModule.EDD)
        )
        assertTrue(router.shouldAutoUploadDocument())
    }

    @Test
    fun `legacy DOCUMENT plus BIOMETRIC does NOT auto-upload`() {
        val router = VerificationFlowRouter(
            setOf(VerificationModule.DOCUMENT, VerificationModule.BIOMETRIC)
        )
        assertFalse(
            "Biometric present — liveness path uploads; auto-upload would double-submit",
            router.shouldAutoUploadDocument()
        )
    }

    // ── "Exactly once" invariant, driven by the REAL production discriminator ────

    /**
     * Reproduces the production guard from `verification_start.kt`:
     *   `if (shouldAutoUploadDocument && !docOnlyUploadFired) { docOnlyUploadFired = true; upload() }`
     * using the actual [VerificationFlowRouter.shouldAutoUploadDocument] value, and drives it
     * through several RESULT-stage passes to prove the upload fires exactly once.
     */
    private fun countUploads(router: VerificationFlowRouter, resultPasses: Int): Int {
        var fired = false
        var uploadCount = 0
        val hasDocument = true // document was captured
        repeat(resultPasses) {
            if (router.shouldAutoUploadDocument() && !fired) {
                if (hasDocument) {
                    fired = true
                    uploadCount++
                }
            }
        }
        return uploadCount
    }

    @Test
    fun `DOCUMENT mode fires the upload exactly once across repeated RESULT passes`() {
        val router = VerificationFlowRouter(VerityMode.DOCUMENT)
        assertEquals(
            "Upload must fire exactly once even if the RESULT handler runs multiple times",
            1,
            countUploads(router, resultPasses = 5)
        )
    }

    @Test
    fun `LIVENESS_ONLY mode never fires the doc auto-upload`() {
        val router = VerificationFlowRouter(VerityMode.LIVENESS_ONLY)
        assertEquals(
            "Biometric-bearing flow must never auto-upload a document from the RESULT path",
            0,
            countUploads(router, resultPasses = 5)
        )
    }
}
