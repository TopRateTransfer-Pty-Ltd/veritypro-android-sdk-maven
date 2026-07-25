package com.example.veritypro_sdk.ui.redesign.analytics

/**
 * B1 — analytics contract (D3 §4). Privacy-preserving: names + enum/boolean/count properties only,
 * never PII. Integrators supply an implementation; the SDK defaults to Logcat.
 */
interface VerityAnalytics {
    fun track(event: VerityAnalyticsEvent)
}

data class VerityAnalyticsEvent(
    val name: String,
    val properties: Map<String, Any?> = emptyMap()
) {
    companion object {
        fun sessionStarted(mode: String) = VerityAnalyticsEvent("session_started", mapOf("mode" to mode))
        fun consentAccepted() = VerityAnalyticsEvent("consent_accepted")
        fun documentSelected(docType: String) = VerityAnalyticsEvent("document_selected", mapOf("docType" to docType))
        fun permissionResult(granted: Boolean) = VerityAnalyticsEvent("permission_result", mapOf("granted" to granted))
        fun captureSucceeded(side: String) = VerityAnalyticsEvent("capture_succeeded", mapOf("side" to side))
        fun livenessResult(result: String) = VerityAnalyticsEvent("liveness_result", mapOf("result" to result))
        fun errorDisplayed(code: String) = VerityAnalyticsEvent("error_displayed", mapOf("code" to code))
        fun stateEntered(state: String) = VerityAnalyticsEvent("state_entered", mapOf("state" to state))
        fun verificationCompleted(outcome: String) = VerityAnalyticsEvent("verification_completed", mapOf("outcome" to outcome))
        fun verificationAbandoned(lastState: String) = VerityAnalyticsEvent("verification_abandoned", mapOf("lastState" to lastState))
    }
}

/** Default sink — Logcat. No PII is emitted (property values are enums/booleans/counts). */
class LogcatVerityAnalytics : VerityAnalytics {
    override fun track(event: VerityAnalyticsEvent) {
        android.util.Log.d("VerityAnalytics", "${event.name} ${event.properties}")
    }
}

/** For tests / opt-out. */
object NoopVerityAnalytics : VerityAnalytics {
    override fun track(event: VerityAnalyticsEvent) {}
}
