package com.example.veritypro_sdk.ui.prototype

import android.content.Context
import android.util.Log
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.VerificationRequestMultipart
import com.example.veritypro_sdk.services.toMultipartBodyPart
import com.example.veritypro_sdk.ui.verification.VerityProViewModel
import com.example.veritypro_sdk.utils.CaptureRuntimeData
import com.example.veritypro_sdk.utils.DeviceUtils
import com.example.veritypro_sdk.utils.LocationHelper
import com.example.veritypro_sdk.utils.SecurityAssessmentCollector
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Real backend submission for the prototype flow — mirrors the production SDK's `updateKyc`
 * multipart. Collects and sends everything the backend requires: document front/back images,
 * a compressed document video clip (when captured), device platform, local IP, geolocation, and
 * the security-assessment JSON (device integrity + capture/motion signals), keyed to the session
 * and liveness IDs. Liveness itself is handled on the AWS network; its result is already posted to
 * the backend, so only the LivenessId is referenced here.
 *
 * Fail-safe: any single collector that fails degrades to empty/null — the submission still fires.
 * Returns true when the backend accepts the submission (Resource.Success), false otherwise, so the
 * caller can advance the UI on the awaited result without racing the kycState StateFlow.
 */
suspend fun protoSubmitVerification(
    context: Context,
    vm: VerityProViewModel,
    docTypeInt: Int,
    frontPath: String?,
    backPath: String?,
    videoPath: String?,
    livenessId: String,
    livenessConfidence: Double?,
    captureAttempts: Int?,
): Boolean {
    val loc = LocationHelper(context)
    val ip = runCatching { loc.getLocalIpAddress() }.getOrNull() ?: ""
    val location = runCatching { loc.getCurrentLocation() }.getOrNull()
    val locString = location?.let { "${it.latitude},${it.longitude}" } ?: ""

    val securityJson = runCatching {
        SecurityAssessmentCollector.collectJson(
            context,
            CaptureRuntimeData(
                latitude = location?.latitude,
                longitude = location?.longitude,
                locationAccuracy = location?.accuracy,
                locationString = locString,
                locationSource = if (location != null) "gps" else "none",
                livenessConfidence = livenessConfidence,
                captureAttempts = captureAttempts,
            ),
        )
    }.getOrNull()

    val front = frontPath?.let { File(it).takeIf { f -> f.exists() && f.length() > 0 } }
    val back = backPath?.let { File(it).takeIf { f -> f.exists() && f.length() > 0 } }
    val video = videoPath?.let { File(it).takeIf { f -> f.exists() && f.length() > 0 } }

    Log.d(
        "ProtoSubmit",
        "updateKyc: doc=$docTypeInt front=${front != null} back=${back != null} " +
            "video=${video?.length() ?: 0}B device=set ip=${ip.isNotBlank()} loc=${locString.isNotBlank()} " +
            "security=${securityJson != null} livenessId=$livenessId",
    )

    val result = vm.submitKycAwait(
        VerificationRequestMultipart(
            SessionId = vm.getSessionId(),
            LivenessId = livenessId,
            DocumentType = docTypeInt,
            PlatformUsed = "android",
            DeviceAndBrowser = DeviceUtils.getDevicePlatform(),
            IpAddress = ip,
            IpLocation = locString,
            DocumentFront = front?.toMultipartBodyPart("DocumentFront"),
            DocumentBack = back?.toMultipartBodyPart("DocumentBack"),
            SecurityAssessmentJson = securityJson,
            DocumentVideo = video?.let {
                MultipartBody.Part.createFormData(
                    "DocumentVideo", it.name, it.asRequestBody("video/mp4".toMediaTypeOrNull()),
                )
            },
        ),
    )
    if (result is Resource.Error) {
        Log.e("ProtoSubmit", "updateKyc failed: ${result.message}")
    }
    // updateKyc returns CompletedSuccess (HTTP 201) on accept — treat both success variants as OK.
    return result is Resource.Success || result is Resource.CompletedSuccess
}
