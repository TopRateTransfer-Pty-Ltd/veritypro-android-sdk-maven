package com.example.veritypro_sdk.ui.redesign.state

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B1 sign-off-gate: state-machine conformance (D3 §2.2). Pure reducer — no device/Compose needed.
 */
class VerityStateMachineTest {

    private fun next(s: VerityFlowState, e: VerityEvent) = VerityStateMachine.next(s, e)

    @Test
    fun happyPath_consentToApproved() {
        var s = VerityFlowState.AwaitingConsent
        s = next(s, VerityEvent.Consent);          assertEquals(VerityFlowState.SelectingDocument, s)
        s = next(s, VerityEvent.SelectDocument);   assertEquals(VerityFlowState.AwaitingPermission, s)
        s = next(s, VerityEvent.PermissionGranted);assertEquals(VerityFlowState.AwaitingDocumentCapture, s)
        s = next(s, VerityEvent.Captured);         assertEquals(VerityFlowState.DocumentPreview, s)
        s = next(s, VerityEvent.ConfirmPreview);   assertEquals(VerityFlowState.Uploading, s)
        s = next(s, VerityEvent.Uploaded);         assertEquals(VerityFlowState.ProcessingDocument, s)
        s = next(s, VerityEvent.DocOk);            assertEquals(VerityFlowState.AwaitingSelfie, s)
        s = next(s, VerityEvent.BeginLiveness);    assertEquals(VerityFlowState.AwaitingLiveness, s)
        s = next(s, VerityEvent.LivenessDone);     assertEquals(VerityFlowState.ProcessingBiometrics, s)
        s = next(s, VerityEvent.BiometricsOk);     assertEquals(VerityFlowState.RunningRiskChecks, s)
        s = next(s, VerityEvent.DecisionApproved); assertEquals(VerityFlowState.Approved, s)
    }

    @Test
    fun permissionDenied_routesToRetry() {
        assertEquals(VerityFlowState.RetryRequired, next(VerityFlowState.AwaitingPermission, VerityEvent.PermissionDenied))
    }

    @Test
    fun retake_returnsToCapture() {
        assertEquals(VerityFlowState.AwaitingDocumentCapture, next(VerityFlowState.DocumentPreview, VerityEvent.Retake))
    }

    @Test
    fun docNeedsBack_returnsToCapture() {
        assertEquals(VerityFlowState.AwaitingDocumentCapture, next(VerityFlowState.ProcessingDocument, VerityEvent.DocNeedsBack))
    }

    @Test
    fun networkLost_thenReconnect_resumes() {
        val n = next(VerityFlowState.Uploading, VerityEvent.NetworkLost)
        assertEquals(VerityFlowState.NetworkInterrupted, n)
        assertEquals(VerityFlowState.Uploading, next(n, VerityEvent.Reconnected(VerityFlowState.Uploading)))
    }

    @Test
    fun livenessTimeout_routesToRetry() {
        assertEquals(VerityFlowState.RetryRequired, next(VerityFlowState.AwaitingLiveness, VerityEvent.LivenessTimeout))
    }

    @Test
    fun decisionReview_and_reject() {
        assertEquals(VerityFlowState.PendingManualReview, next(VerityFlowState.RunningRiskChecks, VerityEvent.DecisionReview))
        assertEquals(VerityFlowState.Rejected, next(VerityFlowState.RunningRiskChecks, VerityEvent.DecisionRejected))
    }

    @Test
    fun cancel_fromInteractive_isCancelled() {
        assertEquals(VerityFlowState.Cancelled, next(VerityFlowState.AwaitingDocumentCapture, VerityEvent.Cancel))
    }

    @Test
    fun integrityBlocked_fails() {
        assertEquals(VerityFlowState.Failed, next(VerityFlowState.Initializing, VerityEvent.IntegrityBlocked))
    }

    @Test
    fun terminalStates_areAbsorbing() {
        assertEquals(VerityFlowState.Approved, next(VerityFlowState.Approved, VerityEvent.Cancel))
        assertEquals(VerityFlowState.Rejected, next(VerityFlowState.Rejected, VerityEvent.Retry(VerityFlowState.Initializing)))
        assertEquals(VerityFlowState.PendingManualReview, next(VerityFlowState.PendingManualReview, VerityEvent.Consent))
    }

    @Test
    fun unknownTransition_isNoOp() {
        assertEquals(VerityFlowState.AwaitingConsent, next(VerityFlowState.AwaitingConsent, VerityEvent.Uploaded))
    }
}
