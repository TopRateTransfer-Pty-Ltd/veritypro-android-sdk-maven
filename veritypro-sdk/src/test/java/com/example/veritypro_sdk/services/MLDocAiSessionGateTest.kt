package com.example.veritypro_sdk.services

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The public /docai/ surface is gated by nginx auth_request on a live verification (KYC
 * /v2/sessions/docai/authorize). The SDK must therefore send X-Verity-Session on every inference
 * call, and send it to the session's own API origin rather than a hard-coded staging host.
 */
class MLDocAiSessionGateTest {

    private val captured = mutableListOf<Request>()

    private fun api(): MLApiService {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            captured += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }).build()
        return Retrofit.Builder().baseUrl("https://api.example.test/docai/")
            .addConverterFactory(GsonConverterFactory.create()).client(client).build()
            .create(MLApiService::class.java)
    }

    @After
    fun reset() = MLRetrofitInstance.configure("http://127.0.0.1:8001")

    @Test
    fun everyInferenceCallCarriesTheSessionHeader() = runTest {
        val api = api()
        api.predict("sid-predict", MLPredictRequest(sessionId = "sid-predict", imageJpegBase64 = "x"))
        api.verifyBurst("sid-burst", MLVerifyBurstRequest(sessionId = "sid-burst", frames = listOf("x")))
        api.detectPresence("sid-presence", MLDetectPresenceRequest(sessionId = "sid-presence", imageJpegBase64 = "x"))
        api.pairCheck("sid-pair", MLPairCheckRequest("sid-pair", null, null))

        assertEquals(
            listOf("sid-predict", "sid-burst", "sid-presence", "sid-pair"),
            captured.map { it.header("X-Verity-Session") },
        )
        assertEquals("/docai/v1/kyc/doc/predict", captured[0].url.encodedPath)
    }

    @Test
    fun docAiHostFollowsTheSessionApiOrigin() {
        MLRetrofitInstance.configureForApiBaseUrl("https://api.veritypro.ai/")
        assertEquals("https://api.veritypro.ai/docai", MLRetrofitInstance.getBaseUrl())
        MLRetrofitInstance.configureForApiBaseUrl("https://api.skylinefare.com")
        assertEquals("https://api.skylinefare.com/docai", MLRetrofitInstance.getBaseUrl())
    }

    @Test
    fun docAiHostRejectsWhatCreateApiRejects() {
        for (bad in listOf("http://api.veritypro.ai", "https://api.veritypro.ai/kycintegration",
                "https://user:pw@api.veritypro.ai", "https://api.veritypro.ai?x=1")) {
            assertThrows(bad, IllegalArgumentException::class.java) { MLRetrofitInstance.configureForApiBaseUrl(bad) }
        }
    }
}
