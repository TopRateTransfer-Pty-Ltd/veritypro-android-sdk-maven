package com.example.veritypro_sdk.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.WindowManager
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit + regression + smoke tests for VpDeviceSessionService.
 *
 * HTTP is intercepted via the internal testHandler hook — no real network calls,
 * no server setup, works with any OkHttp version.
 *
 * Smoke test runs only with: ./gradlew :veritypro-sdk:test -Dveritypro.smoke=true
 */
class VpDeviceSessionServiceTest {

    private val logMessages = mutableListOf<String>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } answers {
            val msg = it.invocation.args[1] as String
            logMessages.add(msg)
            println("LOG.W: $msg")
            0
        }
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
        VpDeviceSessionService.testHandler = null
        logMessages.clear()
    }

    // ── API key guard tests ──────────────────────────────────────────────────

    @Test
    fun `collectAndSubmit returns null for blank API key`() = runBlocking {
        var handlerCalled = false
        VpDeviceSessionService.testHandler = { _ -> handlerCalled = true; fakeResponse("") }
        val result = VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "",
            integrationId = "intg-001"
        )
        assertNull(result)
        assertFalse("testHandler must not be called for blank key", handlerCalled)
    }

    @Test
    fun `collectAndSubmit returns null for whitespace-only API key`() = runBlocking {
        var handlerCalled = false
        VpDeviceSessionService.testHandler = { _ -> handlerCalled = true; fakeResponse("") }
        val result = VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "   ",
            integrationId = "intg-001"
        )
        assertNull(result)
        assertFalse(handlerCalled)
    }

    @Test
    fun `collectAndSubmit returns null for placeholder key starting with angle bracket`() = runBlocking {
        var handlerCalled = false
        VpDeviceSessionService.testHandler = { _ -> handlerCalled = true; fakeResponse("") }
        val result = VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "<from-secrets>",
            integrationId = "intg-001"
        )
        assertNull(result)
        assertFalse(handlerCalled)
    }

    // ── HTTP success path ────────────────────────────────────────────────────

    @Test
    fun `collectAndSubmit returns vpds token on HTTP 200`() = runBlocking {
        var interceptorCalled = false
        VpDeviceSessionService.testHandler = {
            interceptorCalled = true
            fakeResponse("""{"token":"vpds_abc123def456","expires_in_seconds":3600}""")
        }
        val token = VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "valid-api-key",
            integrationId = "intg-001"
        )
        assertTrue("Interceptor not called — check logs: $logMessages", interceptorCalled)
        assertEquals("vpds_abc123def456", token)
    }

    @Test
    fun `collectAndSubmit returns null when response token field is blank`() = runBlocking {
        VpDeviceSessionService.testHandler = { fakeResponse("""{"token":"","session_id":"xyz"}""") }
        val result = VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "key", integrationId = "intg"
        )
        assertNull(result)
    }

    @Test
    fun `collectAndSubmit returns null when response has no token field`() = runBlocking {
        VpDeviceSessionService.testHandler = { fakeResponse("""{"session":"xyz","status":"ok"}""") }
        val result = VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "key", integrationId = "intg"
        )
        assertNull(result)
    }

    // ── HTTP error paths ─────────────────────────────────────────────────────

    @Test
    fun `collectAndSubmit returns null on HTTP 401`() = runBlocking {
        VpDeviceSessionService.testHandler = { fakeResponse("""{"error":"unauthorized"}""", code = 401) }
        val result = VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "bad-key", integrationId = "intg"
        )
        assertNull(result)
    }

    @Test
    fun `collectAndSubmit returns null on HTTP 422`() = runBlocking {
        VpDeviceSessionService.testHandler = {
            fakeResponse("""{"detail":"Invalid integration_id"}""", code = 422)
        }
        val result = VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "key", integrationId = "bad-intg"
        )
        assertNull(result)
    }

    @Test
    fun `collectAndSubmit returns null on HTTP 500`() = runBlocking {
        VpDeviceSessionService.testHandler = { fakeResponse("""{"error":"internal"}""", code = 500) }
        val result = VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "key", integrationId = "intg"
        )
        assertNull(result)
    }

    @Test
    fun `collectAndSubmit returns null on malformed JSON response`() = runBlocking {
        VpDeviceSessionService.testHandler = { fakeResponse("NOT_JSON") }
        val result = VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "key", integrationId = "intg"
        )
        assertNull(result)
    }

    // ── Request shape tests ──────────────────────────────────────────────────

    @Test
    fun `request body contains required top-level fields`() = runBlocking {
        var capturedBody: JSONObject? = null
        VpDeviceSessionService.testHandler = { req ->
            capturedBody = readBody(req)
            fakeResponse("""{"token":"vpds_t1"}""")
        }
        VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "key",
            integrationId = "intg-payload-test"
        )
        val body = capturedBody!!
        assertEquals("intg-payload-test", body.getString("integration_id"))
        assertEquals("android-2.1.0", body.getString("sdk_version"))
        assertTrue("signals field must be present", body.has("signals"))
    }

    @Test
    fun `signals contains all required detection and fingerprint fields`() = runBlocking {
        var capturedSignals: JSONObject? = null
        VpDeviceSessionService.testHandler = { req ->
            capturedSignals = readBody(req).getJSONObject("signals")
            fakeResponse("""{"token":"vpds_t2"}""")
        }
        VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "key", integrationId = "intg"
        )
        val s = capturedSignals!!
        assertTrue("ua", s.has("ua"))
        assertEquals("android", s.getString("platform"))
        assertTrue("tz_name", s.has("tz_name"))
        assertTrue("tz_offset", s.has("tz_offset"))
        assertTrue("language", s.has("language"))
        assertTrue("screen", s.has("screen"))
        assertTrue("touch_points", s.has("touch_points"))
        assertTrue("visitor_id", s.has("visitor_id"))
        assertTrue("session_id", s.has("session_id"))
        assertTrue("collected_at", s.has("collected_at"))
        assertTrue("is_jailbroken", s.has("is_jailbroken"))
        assertTrue("is_emulator", s.has("is_emulator"))
        assertTrue("is_frida_detected", s.has("is_frida_detected"))
    }

    @Test
    fun `signals screen object has width height depth and ratio`() = runBlocking {
        var capturedScreen: JSONObject? = null
        VpDeviceSessionService.testHandler = { req ->
            capturedScreen = readBody(req).getJSONObject("signals").getJSONObject("screen")
            fakeResponse("""{"token":"vpds_t3"}""")
        }
        VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "key", integrationId = "intg"
        )
        val screen = capturedScreen!!
        assertTrue("screen.w", screen.has("w"))
        assertTrue("screen.h", screen.has("h"))
        assertEquals(32, screen.getInt("depth"))
        assertTrue("screen.ratio", screen.has("ratio"))
    }

    @Test
    fun `request sets x-api-key header`() = runBlocking {
        var apiKeyHeader: String? = null
        VpDeviceSessionService.testHandler = { req ->
            apiKeyHeader = req.header("x-api-key")
            fakeResponse("""{"token":"vpds_t4"}""")
        }
        VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "my-secret-key-999", integrationId = "intg"
        )
        assertEquals("my-secret-key-999", apiKeyHeader)
    }

    @Test
    fun `request targets the correct MINT_PATH`() = runBlocking {
        var capturedPath: String? = null
        var capturedMethod: String? = null
        VpDeviceSessionService.testHandler = { req ->
            capturedPath = req.url.encodedPath
            capturedMethod = req.method
            fakeResponse("""{"token":"vpds_t5"}""")
        }
        VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "key", integrationId = "intg"
        )
        assertEquals("/intelligence/api/v1/device/sessions", capturedPath)
        assertEquals("POST", capturedMethod)
    }

    @Test
    fun `request Content-Type is application json`() = runBlocking {
        var contentType: String? = null
        VpDeviceSessionService.testHandler = { req ->
            contentType = req.body?.contentType()?.toString()
            fakeResponse("""{"token":"vpds_t6"}""")
        }
        VpDeviceSessionService.collectAndSubmit(
            context = buildMockContext(), apiKey = "key", integrationId = "intg"
        )
        assertNotNull(contentType)
        assertTrue("Expected application/json, got: $contentType",
            contentType!!.startsWith("application/json"))
    }

    // ── visitor_id persistence tests ─────────────────────────────────────────

    @Test
    fun `getOrCreateVisitorId returns stored ID without writing`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString("_vp_vid", null) } returns "stable-vid-abc"
        val ctx = mockk<Context>()
        every { ctx.getSharedPreferences("vp_device_prefs", Context.MODE_PRIVATE) } returns prefs

        assertEquals("stable-vid-abc", VpDeviceSessionService.getOrCreateVisitorId(ctx))
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `getOrCreateVisitorId generates and persists a UUID when none stored`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString("_vp_vid", null) } returns null
        every { prefs.edit() } returns editor
        val ctx = mockk<Context>()
        every { ctx.getSharedPreferences("vp_device_prefs", Context.MODE_PRIVATE) } returns prefs

        val id = VpDeviceSessionService.getOrCreateVisitorId(ctx)

        assertTrue("visitor_id must not be blank", id.isNotBlank())
        assertTrue("UUID format",
            id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        verify { editor.putString("_vp_vid", id) }
        verify { editor.apply() }
    }

    // ── Regression: session_id uniqueness ────────────────────────────────────

    @Test
    fun `regression each call sends a unique session_id`() = runBlocking {
        val sessionIds = mutableListOf<String>()
        VpDeviceSessionService.testHandler = { req ->
            val sid = readBody(req).getJSONObject("signals").getString("session_id")
            sessionIds.add(sid)
            fakeResponse("""{"token":"vpds_r${sessionIds.size}"}""")
        }

        VpDeviceSessionService.collectAndSubmit(buildMockContext(), "key", "intg")
        VpDeviceSessionService.collectAndSubmit(buildMockContext(), "key", "intg")

        assertEquals(2, sessionIds.size)
        assertNotEquals("session_id must differ across calls", sessionIds[0], sessionIds[1])
    }

    // ── Regression: visitor_id stability ─────────────────────────────────────

    @Test
    fun `regression visitor_id is stable across consecutive calls`() = runBlocking {
        val visitorIds = mutableListOf<String>()
        VpDeviceSessionService.testHandler = { req ->
            val vid = readBody(req).getJSONObject("signals").getString("visitor_id")
            visitorIds.add(vid)
            fakeResponse("""{"token":"vpds_s${visitorIds.size}"}""")
        }

        val ctx = buildMockContext(visitorId = "pinned-visitor-id")
        VpDeviceSessionService.collectAndSubmit(ctx, "key", "intg")
        VpDeviceSessionService.collectAndSubmit(ctx, "key", "intg")

        assertEquals(2, visitorIds.size)
        assertEquals("visitor_id must be stable across calls", visitorIds[0], visitorIds[1])
        assertEquals("pinned-visitor-id", visitorIds[0])
    }

    // ── Pipeline smoke test (gated by -Dveritypro.smoke=true) ───────────────

    @Test
    fun `SMOKE Stage1to3 device session pipeline against staging`() = runBlocking {
        if (System.getProperty("veritypro.smoke") != "true") {
            println("[SMOKE SKIP] Run with -Dveritypro.smoke=true to execute against staging")
            return@runBlocking
        }

        // Smoke test uses REAL network — clear the test handler
        VpDeviceSessionService.testHandler = null

        val apiKey = System.getProperty("veritypro.apiKey",
            "0uztgfOqdIfBaKsTfGLVY0woWfFetS4F6tuOitjjVFw")
        val integrationId = System.getProperty("veritypro.integrationId", "smoke-test-intg")
        val ctx = buildMockContext()

        println("""
            |[SMOKE] ═══════════════════════════════════════════════════════
            |[SMOKE] Stage 1 — Android SDK device signal collection
            |[SMOKE]   apiKey prefix : ${apiKey.take(8)}…
            |[SMOKE]   integrationId : $integrationId
            |[SMOKE]   endpoint      : https://api.skylinefare.com
        """.trimMargin())

        val token = VpDeviceSessionService.collectAndSubmit(
            context = ctx, apiKey = apiKey, integrationId = integrationId,
            baseUrl = "https://api.skylinefare.com"
        )

        println("""
            |[SMOKE] ───────────────────────────────────────────────────────
            |[SMOKE] Stage 2 — Token received from AML-Intel:
            |[SMOKE]   device_session_token : $token
        """.trimMargin())

        assertNotNull("Stage 2: expected a vpds_* token from staging", token)
        assertTrue("Stage 2: token must start with vpds_", token!!.startsWith("vpds_"))

        println("""
            |[SMOKE] ───────────────────────────────────────────────────────
            |[SMOKE] Stage 3 — Transaction payload ready for AML pipeline:
            |[SMOKE]   {
            |[SMOKE]     "transaction_type": "TRANSFER",
            |[SMOKE]     "amount": 250.00,
            |[SMOKE]     "currency": "AUD",
            |[SMOKE]     "device_session_token": "$token"
            |[SMOKE]   }
            |[SMOKE]   Pipeline: AML-Intel device scoring → PASS / REVIEW / DECLINE
            |[SMOKE] ═══════════════════════════════════════════════════════
        """.trimMargin())
    }

    // ── Test helpers ─────────────────────────────────────────────────────────

    private fun fakeResponse(json: String, code: Int = 200): Response =
        Response.Builder()
            .request(Request.Builder().url("http://localhost/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()

    private fun readBody(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun buildMockContext(visitorId: String = "test-visitor-id-00000000"): Context {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor

        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), null) } returns visitorId
        every { prefs.edit() } returns editor

        @Suppress("DEPRECATION")
        val display = mockk<android.view.Display>(relaxed = true)
        val wm = mockk<WindowManager>(relaxed = true)
        @Suppress("DEPRECATION")
        every { wm.defaultDisplay } returns display

        return mockk<Context>(relaxed = true) {
            every { getSharedPreferences(any(), any()) } returns prefs
            every { getSystemService(Context.WINDOW_SERVICE) } returns wm
            every { getSystemService(Context.BATTERY_SERVICE) } returns null
        }
    }
}
