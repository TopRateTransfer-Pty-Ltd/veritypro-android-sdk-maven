package com.example.veritypro_sdk.services

import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Result of `update-kyc-verification` (CONTRACT.md section 2).
 *
 * New backends add `captureStatus`. Old backends do not, and for them the SDK keeps exactly
 * today's behaviour: 201 / 409 upload_duplicate = submitted, anything else = [Failed]. Nothing in
 * here turns an error into a success.
 */
sealed class KycUploadOutcome {
    data class Submitted(
        val message: String,
        /** Null on an old backend that does not report it. */
        val captureStatus: String? = null,
        val captureAttemptId: String? = null,
        val duplicate: Boolean = false,
    ) : KycUploadOutcome()

    /** HTTP 400 RECAPTURE_REQUIRED: the photo/file was not usable. Capture again under a NEW attempt. */
    data class RecaptureRequired(val reason: String) : KycUploadOutcome()

    /** HTTP 503 TECHNICAL_UNAVAILABLE: not submitted, safe to retry with the SAME attempt id. */
    data class TechnicalUnavailable(val reason: String) : KycUploadOutcome()

    /** Any other failure, mapped exactly as before this contract existed. */
    data class Failed(val message: String) : KycUploadOutcome()

    fun toResource(): Resource<String> = when (this) {
        is Submitted -> Resource.CompletedSuccess(message)
        is RecaptureRequired -> Resource.Error(reason)
        is TechnicalUnavailable -> Resource.Error(reason)
        is Failed -> Resource.Error(message)
    }
}

object KycUploadResponseMapper {
    const val CAPTURE_ACCEPTED = "CAPTURE_ACCEPTED"
    const val RECAPTURE_REQUIRED = "RECAPTURE_REQUIRED"
    const val TECHNICAL_UNAVAILABLE = "TECHNICAL_UNAVAILABLE"

    data class CaptureFields(val captureStatus: String?, val captureAttemptId: String?, val duplicate: Boolean?, val reason: String?)

    /**
     * Reads the contract fields from a JSON body. They are looked for at the top level first, then
     * inside `data` and `error` objects, because the existing APIResponse envelope nests payloads.
     */
    fun captureFields(body: String?): CaptureFields {
        if (body.isNullOrBlank()) return CaptureFields(null, null, null, null)
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "Upload response body is not JSON (${e.javaClass.simpleName})")
            return CaptureFields(null, null, null, null)
        }
        val scopes = listOfNotNull(root, root.optJSONObject("data"), root.optJSONObject("error"))
        fun str(key: String): String? = scopes.firstNotNullOfOrNull { o ->
            if (o.has(key) && !o.isNull(key)) o.optString(key).takeIf { it.isNotBlank() } else null
        }
        val dup = scopes.firstOrNull { it.has("duplicate") && !it.isNull("duplicate") }?.optBoolean("duplicate")
        return CaptureFields(
            captureStatus = str("captureStatus"),
            captureAttemptId = str("captureAttemptId"),
            duplicate = dup,
            reason = str("reason") ?: str("statusMessage") ?: str("message"),
        )
    }

    /**
     * A 2xx response. [statusCode] is the envelope's own statusCode (the backend repeats the HTTP
     * status there).
     */
    fun fromSuccess(statusCode: Int, statusMessage: String, errorMessage: String?, fields: CaptureFields): KycUploadOutcome {
        when (fields.captureStatus) {
            RECAPTURE_REQUIRED -> return KycUploadOutcome.RecaptureRequired(fields.reason ?: statusMessage)
            TECHNICAL_UNAVAILABLE -> return KycUploadOutcome.TechnicalUnavailable(fields.reason ?: statusMessage)
        }
        return when {
            statusCode == 201 -> KycUploadOutcome.Submitted(
                message = statusMessage,
                captureStatus = fields.captureStatus,
                captureAttemptId = fields.captureAttemptId,
                duplicate = fields.duplicate == true,
            )
            statusCode == 409 && errorMessage == "upload_duplicate" ->
                KycUploadOutcome.Submitted("KYC Verification already submitted", fields.captureStatus, fields.captureAttemptId, duplicate = true)
            else -> KycUploadOutcome.Failed(errorMessage ?: "Unable to validate")
        }
    }

    /**
     * A non-2xx response. Returns null when the body carries no recognised `captureStatus` (an old
     * backend, or a different error), so the caller keeps its existing error handling.
     */
    fun fromHttpError(httpCode: Int, errorBody: String?): KycUploadOutcome? {
        val f = captureFields(errorBody)
        return when {
            httpCode == 400 && f.captureStatus == RECAPTURE_REQUIRED ->
                KycUploadOutcome.RecaptureRequired(f.reason ?: "Please retake your document photos.")
            httpCode == 503 && f.captureStatus == TECHNICAL_UNAVAILABLE ->
                KycUploadOutcome.TechnicalUnavailable(f.reason ?: "The document service is temporarily unavailable.")
            else -> null
        }
    }

    /** Backoff before retry n (1-based): 2 s, 4 s, 8 s. */
    val RETRY_BACKOFF_MS = longArrayOf(2_000, 4_000, 8_000)
    const val MAX_TECHNICAL_RETRIES = 3

    /**
     * Calls [submitOnce] and retries ONLY on [KycUploadOutcome.TechnicalUnavailable], at most
     * [MAX_TECHNICAL_RETRIES] times. The caller must send the same CaptureAttemptId every time;
     * that id is what makes the retry idempotent on the server. Network errors are not retried:
     * an old backend cannot de-duplicate them.
     */
    suspend fun submitWithBoundedRetry(
        maxRetries: Int = MAX_TECHNICAL_RETRIES,
        backoffMs: LongArray = RETRY_BACKOFF_MS,
        sleep: suspend (Long) -> Unit = { delay(it) },
        submitOnce: suspend () -> KycUploadOutcome,
    ): KycUploadOutcome {
        var outcome = submitOnce()
        var retry = 0
        while (outcome is KycUploadOutcome.TechnicalUnavailable && retry < maxRetries) {
            val wait = backoffMs[minOf(retry, backoffMs.size - 1)]
            retry++
            Log.w(TAG, "Upload TECHNICAL_UNAVAILABLE; retry $retry/$maxRetries in ${wait}ms (same attempt id)")
            sleep(wait)
            outcome = submitOnce()
        }
        return outcome
    }

    private const val TAG = "KycUploadOutcome"
}
