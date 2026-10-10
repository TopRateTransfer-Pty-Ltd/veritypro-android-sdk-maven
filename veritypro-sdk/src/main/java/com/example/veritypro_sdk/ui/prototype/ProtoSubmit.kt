package com.example.veritypro_sdk.ui.prototype

import android.content.Context
import android.util.Log
import com.example.veritypro_sdk.BuildConfig
import com.example.veritypro_sdk.capture.CaptureAttemptStore
import com.example.veritypro_sdk.capture.CaptureFiles
import com.example.veritypro_sdk.capture.CaptureMetadataBuilder
import com.example.veritypro_sdk.capture.CaptureSide
import com.example.veritypro_sdk.capture.SideCapture
import com.example.veritypro_sdk.services.KycUploadOutcome
import com.example.veritypro_sdk.services.KycUploadResponseMapper
import com.example.veritypro_sdk.services.VerificationRequestMultipart
import com.example.veritypro_sdk.services.toMultipartBodyPart
import com.example.veritypro_sdk.ui.verification.VerityProViewModel
import com.example.veritypro_sdk.utils.DeviceUtils
import com.example.veritypro_sdk.utils.LocationHelper
import com.example.veritypro_sdk.utils.SecurityAssessmentCollector
import com.example.veritypro_sdk.utils.withCurrentLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Parts of the capture contract (veritypro.capture.v1) for one attempt: the stills, each side's
 * own clip, the attempt id and the metadata JSON. Pure apart from re-hashing the files, so the
 * mapping can be unit-tested.
 */
internal data class CaptureUploadParts(
    val attemptId: String,
    val front: File?,
    val back: File?,
    val frontVideo: File?,
    val backVideo: File?,
    val metadataJson: String,
)

internal fun buildCaptureUploadParts(
    store: CaptureAttemptStore,
    docTypeInt: Int,
    sdkVersion: String,
    hashFile: (File) -> String = { CaptureFiles.sha256(it) },
): CaptureUploadParts {
    val sides = store.captured()
    // The metadata must describe the exact bytes that are uploaded, so the stills are hashed again
    // here. The files are uniquely named per attempt/side/retake and never rewritten, so a
    // mismatch would be a defect; the uploaded bytes' hash is what gets reported.
    val verified: List<SideCapture> = sides.map { s ->
        val f = File(s.still.path)
        val actual = hashFile(f)
        if (actual != s.still.sha256) {
            Log.e("ProtoSubmit", "Still ${s.side} changed after capture; reporting the uploaded bytes' hash")
            s.copy(still = s.still.copy(sha256 = actual))
        } else s
    }
    val metadata = CaptureMetadataBuilder.build(
        attemptId = store.attemptId,
        sdkVersion = sdkVersion,
        documentType = CaptureMetadataBuilder.documentTypeName(docTypeInt),
        sides = verified,
    )
    fun still(side: CaptureSide) = store.side(side)?.still?.path?.let { File(it).takeIf { f -> f.exists() && f.length() > 0 } }
    return CaptureUploadParts(
        attemptId = store.attemptId,
        front = still(CaptureSide.FRONT),
        back = still(CaptureSide.BACK),
        frontVideo = store.side(CaptureSide.FRONT)?.video?.uploadableFile(),
        backVideo = store.side(CaptureSide.BACK)?.video?.uploadableFile(),
        metadataJson = metadata.toString(),
    )
}

/**
 * Real backend submission for the prototype flow — the `update-kyc-verification` multipart.
 * Sends the current capture attempt (front/back stills, FRONT clip as `DocumentVideo`, BACK clip
 * as `DocumentBackVideo`, `CaptureAttemptId`, `CaptureMetadataJson`) plus device platform, local
 * IP, geolocation and the security-assessment JSON, keyed to the session and liveness ids.
 *
 * Returns the [KycUploadOutcome]. A 503 TECHNICAL_UNAVAILABLE is retried at most 3 times with
 * the SAME attempt id; RECAPTURE_REQUIRED is returned to the caller, which must start a NEW attempt.
 */
suspend fun protoSubmitVerification(
    context: Context,
    vm: VerityProViewModel,
    docTypeInt: Int,
    livenessId: String,
    livenessConfidence: Double?,
    captureAttempts: Int?,
    /**
     * Engine session id to key the multipart against. Blank (default) uses the ViewModel's createKyc
     * session id — the CLIENT-mode behaviour. Server-driven mode passes the v2 session's
     * kycEngineSessionId so the document is attached to the backend-owned engine session.
     */
    engineSessionId: String = "",
): KycUploadOutcome {
    val loc = LocationHelper(context)
    val ip = runCatching { loc.getLocalIpAddress() }.getOrNull() ?: ""
    val runtime = vm.captureRuntimeData().withCurrentLocation(context)
    val locString = if (runtime.latitude != null && runtime.longitude != null) "${runtime.latitude},${runtime.longitude}" else ""

    val securityJson = runCatching {
        SecurityAssessmentCollector.collectJson(
            context,
            runtime.copy(
                livenessConfidence = livenessConfidence,
                captureAttempts = captureAttempts,
            ),
        )
    }.getOrNull()

    val parts = try {
        withContext(Dispatchers.IO) { buildCaptureUploadParts(vm.documentCapture, docTypeInt, BuildConfig.SDK_VERSION) }
    } catch (e: Exception) {
        Log.e("ProtoSubmit", "Could not prepare the capture upload: ${e.javaClass.simpleName}: ${e.message}", e)
        return KycUploadOutcome.Failed("Your document photos could not be prepared. Please retake them.")
    }

    Log.i(
        "ProtoSubmit",
        "updateKyc: doc=$docTypeInt attempt=${parts.attemptId} front=${parts.front != null} back=${parts.back != null} " +
            "frontVideo=${parts.frontVideo?.length() ?: 0}B backVideo=${parts.backVideo?.length() ?: 0}B " +
            "ip=${ip.isNotBlank()} loc=${locString.isNotBlank()} security=${securityJson != null} livenessId=$livenessId",
    )

    fun video(name: String, f: File?) = f?.let {
        MultipartBody.Part.createFormData(name, it.name, it.asRequestBody("video/mp4".toMediaTypeOrNull()))
    }

    val sessionId = engineSessionId.ifBlank { vm.getSessionId() }
    val request = VerificationRequestMultipart(
        SessionId = sessionId,
        LivenessId = livenessId,
        DocumentType = docTypeInt,
        PlatformUsed = "android",
        DeviceAndBrowser = DeviceUtils.getDevicePlatform(),
        IpAddress = ip,
        IpLocation = locString,
        DocumentFront = parts.front?.toMultipartBodyPart("DocumentFront"),
        DocumentBack = parts.back?.toMultipartBodyPart("DocumentBack"),
        SecurityAssessmentJson = securityJson,
        DocumentVideo = video("DocumentVideo", parts.frontVideo),
        DocumentBackVideo = video("DocumentBackVideo", parts.backVideo),
        CaptureAttemptId = parts.attemptId,
        CaptureMetadataJson = parts.metadataJson,
    )
    val outcome = KycUploadResponseMapper.submitWithBoundedRetry { vm.submitKycOutcomeAwait(request) }
    when (outcome) {
        is KycUploadOutcome.Submitted -> Log.i("ProtoSubmit", "updateKyc submitted (captureStatus=${outcome.captureStatus}, duplicate=${outcome.duplicate})")
        is KycUploadOutcome.RecaptureRequired -> Log.w("ProtoSubmit", "updateKyc RECAPTURE_REQUIRED for attempt ${parts.attemptId}")
        is KycUploadOutcome.TechnicalUnavailable -> Log.e("ProtoSubmit", "updateKyc TECHNICAL_UNAVAILABLE after retries, attempt ${parts.attemptId}")
        is KycUploadOutcome.Failed -> Log.e("ProtoSubmit", "updateKyc failed: ${outcome.message}")
    }
    return outcome
}
