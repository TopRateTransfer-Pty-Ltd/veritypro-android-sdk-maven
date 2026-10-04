package com.example.veritypro_sdk.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SDK has no default host: every client is built from, and pinned to, the configured origin. */
class RetrofitInstanceContractTest {
    @Test
    fun `configured api can be constructed without an http call`() {
        // Retrofit validates the base URL during construction, before any request is sent.
        RetrofitInstance.createApi("https://gateway.test/")
    }

    @Test
    fun `api origin is required and must be an https origin`() {
        for (bad in listOf(null, "", " ", "http://gateway.test", "https://gateway.test/kycintegration",
                "https://u:p@gateway.test", "https://gateway.test?x=1", "gateway.test")) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { VerityEndpoint.requireApiOrigin(bad) }
        }
        assertEquals("https://gateway.test", VerityEndpoint.requireApiOrigin("https://gateway.test/"))
    }

    @Test
    fun `missing origin names the setting`() {
        val error = assertThrows(IllegalArgumentException::class.java) { VerityEndpoint.requireApiOrigin(null) }
        assertTrue(error.message!!.startsWith("apiBaseUrl is required"))
    }

    @Test
    fun `pinner covers exactly the configured host with the three ISRG roots`() {
        for (host in listOf("api.skylinefare.com", "api.veritypro.ai", "gateway.test")) {
            val pins = VerityEndpoint.pinnerFor("https://$host").pins
            assertEquals(setOf(host), pins.map { it.pattern }.toSet())
            assertEquals(VerityEndpoint.ISRG_ROOT_PINS.toSet(), pins.map { "sha256/" + it.hash.base64() }.toSet())
        }
    }

    @Test
    fun `docai is unusable until a session configures it`() {
        // A fresh process has no DocAI origin; reaching for it must fail, not fall back to a host.
        val field = MLRetrofitInstance::class.java.getDeclaredField("mlBaseUrl").apply { isAccessible = true }
        val previous = field.get(MLRetrofitInstance)
        try {
            synchronized(MLRetrofitInstance) {
                field.set(MLRetrofitInstance, null)
                MLRetrofitInstance::class.java.getDeclaredField("mlApiService").apply { isAccessible = true }.set(MLRetrofitInstance, null)
            }
            assertThrows(IllegalStateException::class.java) { MLRetrofitInstance.api }
        } finally {
            field.set(MLRetrofitInstance, previous)
        }
    }
}
