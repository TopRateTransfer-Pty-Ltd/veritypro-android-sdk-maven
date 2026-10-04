package com.example.veritypro_sdk.utils

import com.example.veritypro_sdk.services.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AssessmentTransportTest {
    @Test fun `legacy no body and versioned assessment use same existing route`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"statusCode\":200,\"data\":null}".toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://sdk-contract.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(VerityApiService::class.java)
        api.beginLiveness("synthetic-session", "synthetic-key")
        assertEquals(0L, requests.single().body?.contentLength() ?: 0L)
        val evidence = "{\"SchemaVersion\":1,\"SignalAvailability\":{\"behavior\":\"unavailable\"}}"
        api.beginLivenessWithAssessment("synthetic-session", "synthetic-key", BeginLivenessAssessmentRequest(evidence))
        val sent = requests.last()
        assertEquals("/kycintegration/kyc-verification/begin-liveness", sent.url.encodedPath)
        assertEquals("synthetic-session", sent.url.queryParameter("sessionId"))
        val body = Buffer().also { sent.body!!.writeTo(it) }.readUtf8()
        assertEquals(evidence, JSONObject(body).getString("securityAssessmentJson"))
        assertFalse(body.contains("integrationId"))
    }
}
