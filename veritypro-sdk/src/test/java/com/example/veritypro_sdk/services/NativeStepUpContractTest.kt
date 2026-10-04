package com.example.veritypro_sdk.services

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class NativeStepUpContractTest {
    @Test fun `EDD income context is sent in the documented profile JSON part`() {
        val parts = VerityApiService::class.java.methods.single { it.name == "createEddCase" }.parameterAnnotations
            .flatMap { it.toList() }.filterIsInstance<retrofit2.http.Part>().map { it.value }
        assertTrue("KycProfileJson part missing", "KycProfileJson" in parts)
    }
    @Test fun `begin response reads challenge session and flat credentials`() {
        val response = Gson().fromJson("""{"statusCode":200,"data":{"livenessSessionId":"live-1","region":"ap-southeast-2","accessKeyId":"test-id","secretAccessKey":"test-secret","sessionToken":"test-token"}}""", StepUpBeginResponse::class.java)
        assertEquals("live-1", response.data!!.livenessSessionId)
        assertEquals("test-id", response.data!!.credentials()!!.accessKeyId)
    }

    @Test fun `completion envelope preserves authorized false despite passed verdict`() {
        val response = Gson().fromJson("""{"statusCode":200,"data":{"challengeId":"challenge-1","status":"Completed","verdict":"Passed","authorized":false}}""", StepUpCompletionEnvelope::class.java)
        assertFalse(response.data!!.isAuthorized())
    }

    @Test fun `only matching passed verdict with authorization permits access`() {
        val response = Gson().fromJson("""{"statusCode":200,"data":{"challengeId":"challenge-1","status":"Completed","verdict":"Passed","authorized":true,"livenessVerdict":true}}""", StepUpCompletionEnvelope::class.java)
        assertTrue(response.data!!.isAuthorized())
    }
}
