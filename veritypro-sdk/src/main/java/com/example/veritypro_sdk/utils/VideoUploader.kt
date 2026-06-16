package com.example.veritypro_sdk.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

object VerityVideoUploader {

    private val client = OkHttpClient()

    fun uploadPortraitVideo(
        videoFile: File,
        sessionId: String,
        apiKey: String,
        baseUrl: String,
        endpoint: String = "kycintegration/kyc-verification/update-kyc-verification"
    ) {
        if (videoFile.length() == 0L) {
            videoFile.delete()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val videoPart = MultipartBody.Part.createFormData(
                    "PortraitVideo",
                    videoFile.name,
                    videoFile.asRequestBody("video/mp4".toMediaTypeOrNull())
                )
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("SessionId", sessionId)
                    .addPart(videoPart)
                    .build()

                val request = Request.Builder()
                    .url("$baseUrl/$endpoint")
                    .header("x-api-key", apiKey)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { /* fire and forget */ }
            } catch (e: Exception) {
                Log.w("VerityVideoUploader", "Video upload failed (non-fatal): ${e.message}")
            } finally {
                videoFile.delete()
            }
        }
    }
}
