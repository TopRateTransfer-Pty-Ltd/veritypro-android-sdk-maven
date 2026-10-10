package com.example.veritypro_sdk.services

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File


data class VerificationRequestMultipart(
    val SessionId: String,
    val LivenessId: String,
    val DocumentType: Int,
    val PortraitPicture: MultipartBody.Part? = null,
    val DocumentFront: MultipartBody.Part? = null,
    val DocumentBack: MultipartBody.Part? = null,
    val PlatformUsed: String,
    val DeviceAndBrowser: String,
    /**
     * Device's local network interface IP address (e.g. 192.168.x.x).
     * This is NOT the public IP; the server should capture the public IP from request headers.
     * Field name kept as "IpAddress" for backend API compatibility.
     */
    val IpAddress: String,
    val IpLocation: String,
    val SecurityAssessmentJson: String? = null,
    val PortraitVideo: MultipartBody.Part? = null,
    /** FRONT-side document video (name unchanged so old backends keep working). */
    val DocumentVideo: MultipartBody.Part? = null,
    /** BACK-side document video (veritypro.capture.v1). */
    val DocumentBackVideo: MultipartBody.Part? = null,
    /** UUID v4, one per document submission; the server's idempotency key. */
    val CaptureAttemptId: String? = null,
    /** JSON, schema veritypro.capture.v1 (see CaptureMetadataBuilder). */
    val CaptureMetadataJson: String? = null,
)

fun File.toMultipartBodyPart(partName: String): MultipartBody.Part =
    MultipartBody.Part.createFormData(
        partName,
        this.name,
        RequestBody.create("image/jpeg".toMediaTypeOrNull(), this)
    )