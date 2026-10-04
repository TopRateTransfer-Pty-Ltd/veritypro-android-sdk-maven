package com.example.veritypro_sdk.services

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * `@Part("document") MultipartBody.Part` made Retrofit throw IllegalArgumentException while building
 * attachBasicEddDocument, so every Basic EDD document upload failed before any HTTP call. The EDD API
 * (BasicEddController.AddAssessmentDocument) binds `IFormFile document` from multipart/form-data.
 */
class BasicEddAttachMultipartTest {

    @Test
    fun attachSendsOneMultipartPartNamedDocument() = runTest {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }).build()
        val api = Retrofit.Builder().baseUrl("https://api.example.test/")
            .addConverterFactory(GsonConverterFactory.create()).client(client).build()
            .create(VerityApiService::class.java)

        val part = MultipartBody.Part.createFormData(
            "document", "proof.pdf", "%PDF".toRequestBody("application/pdf".toMediaType()))
        api.attachBasicEddDocument(assessmentId = "a1", document = part, apiKey = "k")

        val body = captured!!.body as MultipartBody
        assertEquals("/edd/api/v1/edd/basic/assessments/a1/documents", captured!!.url.encodedPath)
        assertEquals(1, body.size)
        val disposition = body.part(0).headers!!["Content-Disposition"]!!
        assertTrue(disposition, disposition.contains("name=\"document\"") && disposition.contains("filename=\"proof.pdf\""))
    }
}
