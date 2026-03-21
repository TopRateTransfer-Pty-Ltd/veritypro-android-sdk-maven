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
    val IpAddress: String,
    val IpLocation: String,
    val SecurityAssessmentJson: String? = null
)

fun File.toMultipartBodyPart(partName: String): MultipartBody.Part =
    MultipartBody.Part.createFormData(
        partName,
        this.name,
        RequestBody.create("image/*".toMediaTypeOrNull(), this)
    )