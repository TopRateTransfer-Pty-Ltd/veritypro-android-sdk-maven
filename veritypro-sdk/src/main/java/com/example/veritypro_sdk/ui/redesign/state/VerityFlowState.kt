package com.example.veritypro_sdk.ui.redesign.state

/**
 * B1 — canonical verification state machine (D3 §2). The states are a projection of the
 * authoritative v2 server session plus local capture state. [VerityStateMachine.next] is a PURE
 * reducer so the transition table can be unit-tested (sign-off-gate conformance).
 */
enum class VerityFlowState {
    Initializing,
    AwaitingConsent,
    SelectingDocument,
    AwaitingPermission,
    AwaitingDocumentCapture,
    DocumentPreview,
    Uploading,
    ProcessingDocument,
    AwaitingSelfie,
    AwaitingLiveness,
    ProcessingBiometrics,
    RunningRiskChecks,
    PendingManualReview, // terminal
    Approved,            // terminal
    Rejected,            // terminal
    RetryRequired,       // recoverable
    SessionExpired,      // recoverable
    NetworkInterrupted,  // recoverable
    Cancelled,           // terminal
    Failed;              // terminal

    val isTerminal: Boolean
        get() = this == Approved || this == Rejected || this == PendingManualReview ||
                this == Cancelled || this == Failed
}

/** Events that drive transitions (D3 §2.2). Parameterized events carry the resume target. */
sealed interface VerityEvent {
    data object Ready : VerityEvent
    data object IntegrityBlocked : VerityEvent
    data object Consent : VerityEvent
    data object SelectDocument : VerityEvent
    data object PermissionGranted : VerityEvent
    data object PermissionDenied : VerityEvent
    data object Captured : VerityEvent
    data object ConfirmPreview : VerityEvent
    data object Retake : VerityEvent
    data object Uploaded : VerityEvent
    data object NetworkLost : VerityEvent
    data object DocNeedsBack : VerityEvent
    data object DocOk : VerityEvent
    data object DocRetry : VerityEvent
    data object BeginLiveness : VerityEvent
    data object LivenessDone : VerityEvent
    data object LivenessTimeout : VerityEvent
    data object BiometricsOk : VerityEvent
    data object DecisionApproved : VerityEvent
    data object DecisionReview : VerityEvent
    data object DecisionRejected : VerityEvent
    data class Retry(val to: VerityFlowState) : VerityEvent
    data class Reconnected(val to: VerityFlowState) : VerityEvent
    data object Cancel : VerityEvent
}

object VerityStateMachine {

    /** Pure transition. Unknown (state, event) pairs are no-ops (return the same state). */
    fun next(state: VerityFlowState, event: VerityEvent): VerityFlowState {
        // Cancel from any non-terminal interactive state.
        if (event is VerityEvent.Cancel && !state.isTerminal) return VerityFlowState.Cancelled
        if (state.isTerminal) return state

        return when (state) {
            VerityFlowState.Initializing -> when (event) {
                VerityEvent.Ready -> VerityFlowState.AwaitingConsent
                VerityEvent.IntegrityBlocked -> VerityFlowState.Failed
                else -> state
            }
            VerityFlowState.AwaitingConsent -> if (event is VerityEvent.Consent)
                VerityFlowState.SelectingDocument else state
            VerityFlowState.SelectingDocument -> if (event is VerityEvent.SelectDocument)
                VerityFlowState.AwaitingPermission else state
            VerityFlowState.AwaitingPermission -> when (event) {
                VerityEvent.PermissionGranted -> VerityFlowState.AwaitingDocumentCapture
                VerityEvent.PermissionDenied -> VerityFlowState.RetryRequired
                else -> state
            }
            VerityFlowState.AwaitingDocumentCapture -> if (event is VerityEvent.Captured)
                VerityFlowState.DocumentPreview else state
            VerityFlowState.DocumentPreview -> when (event) {
                VerityEvent.ConfirmPreview -> VerityFlowState.Uploading
                VerityEvent.Retake -> VerityFlowState.AwaitingDocumentCapture
                else -> state
            }
            VerityFlowState.Uploading -> when (event) {
                VerityEvent.Uploaded -> VerityFlowState.ProcessingDocument
                VerityEvent.NetworkLost -> VerityFlowState.NetworkInterrupted
                else -> state
            }
            VerityFlowState.ProcessingDocument -> when (event) {
                VerityEvent.DocNeedsBack -> VerityFlowState.AwaitingDocumentCapture
                VerityEvent.DocOk -> VerityFlowState.AwaitingSelfie
                VerityEvent.DocRetry -> VerityFlowState.RetryRequired
                else -> state
            }
            VerityFlowState.AwaitingSelfie -> if (event is VerityEvent.BeginLiveness)
                VerityFlowState.AwaitingLiveness else state
            VerityFlowState.AwaitingLiveness -> when (event) {
                VerityEvent.LivenessDone -> VerityFlowState.ProcessingBiometrics
                VerityEvent.LivenessTimeout -> VerityFlowState.RetryRequired
                else -> state
            }
            VerityFlowState.ProcessingBiometrics -> if (event is VerityEvent.BiometricsOk)
                VerityFlowState.RunningRiskChecks else state
            VerityFlowState.RunningRiskChecks -> when (event) {
                VerityEvent.DecisionApproved -> VerityFlowState.Approved
                VerityEvent.DecisionReview -> VerityFlowState.PendingManualReview
                VerityEvent.DecisionRejected -> VerityFlowState.Rejected
                else -> state
            }
            VerityFlowState.RetryRequired -> if (event is VerityEvent.Retry) event.to else state
            VerityFlowState.NetworkInterrupted -> if (event is VerityEvent.Reconnected) event.to else state
            VerityFlowState.SessionExpired -> if (event is VerityEvent.Retry) event.to else state
            else -> state
        }
    }
}
