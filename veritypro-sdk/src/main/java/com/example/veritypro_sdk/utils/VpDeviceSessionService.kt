package com.example.veritypro_sdk.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.example.veritypro_sdk.services.VerityEndpoint
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "VpDeviceSession"
private const val PREFS_NAME = "vp_device_prefs"
private const val KEY_VISITOR_ID = "_vp_vid"
private const val MINT_PATH = "/intelligence/api/v1/device/sessions"

/**
 * Mints a vpds_* device-session token by POSTing mobile device signals to the
 * AML-Intelligence device sessions endpoint. Mirrors iOS VpDeviceSessionService.
 *
 * Graceful-degradation: all failures return null — the KYC flow must not block
 * on this result. Never throws.
 */
object VpDeviceSessionService {

    // Overridden in unit tests only — null means use real network. Set this
    // before calling collectAndSubmit() in tests so the OkHttpClient is never
    // initialised (Android stubs used in unit tests have null Build.* fields
    // which cause OkHttp 5.x platform detection to throw).
    @Volatile
    internal var testHandler: ((okhttp3.Request) -> okhttp3.Response)? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Retrieves the persisted visitor ID or mints and stores a new UUID.
     * Stable across sessions; keyed under [KEY_VISITOR_ID] in [PREFS_NAME].
     */
    fun getOrCreateVisitorId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_VISITOR_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_VISITOR_ID, it).apply()
        }
    }

    /**
     * Collects device signals and submits them to the device sessions endpoint.
     * Returns the minted vpds_* token, or null on any failure or timeout.
     *
     * Suspend — must be called from a coroutine. Never throws; callers treat
     * null as "token unavailable" and continue the KYC flow without it.
     *
     * @param apiKey Deprecated and ignored; never sent. The endpoint is anonymous and attributes
     *   by integration_id, so a key in the app would only leak it.
     * @param baseUrl The integrator's API origin. Required: there is no default host.
     * @throws IllegalArgumentException when [baseUrl] is missing or not an HTTPS origin — a
     *   configuration error, raised before any collection. Network failures still return null.
     */
    suspend fun collectAndSubmit(
        context: Context,
        apiKey: String? = null,
        integrationId: String,
        baseUrl: String
    ): String? {
        val origin = VerityEndpoint.requireApiOrigin(baseUrl, "baseUrl")
        return withTimeoutOrNull(8_000L) {
            tryMint(context, integrationId, origin)
        }.also { if (it == null) Log.w(TAG, "Device session mint timed out or failed") }
    }

    private suspend fun tryMint(
        context: Context,
        integrationId: String,
        baseUrl: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val payload = buildPayload(context, integrationId)
            val url = "$baseUrl$MINT_PATH"

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            // In unit tests testHandler is set — bypass OkHttpClient entirely so
            // Android stubs with null Build.* fields never trigger OkHttp init.
            val response = testHandler?.invoke(request)
                ?: VerityEndpoint.pinnedClient(httpClient, baseUrl).newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) {
                    Log.w(TAG, "HTTP ${res.code} from device sessions endpoint")
                    return@withContext null
                }
                val token = JSONObject(res.body?.string() ?: return@withContext null)
                    .optString("token").takeIf { it.isNotBlank() }
                if (token != null) Log.d(TAG, "vpds token minted (prefix=${token.take(10)}…)")
                token
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device session mint error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun buildPayload(context: Context, integrationId: String): JSONObject {
        val tz = TimeZone.getDefault()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getMetrics(metrics)
        val locale = try { context.resources.configuration.locales[0] } catch (_: Exception) { java.util.Locale.getDefault() }

        return JSONObject().apply {
            put("integration_id", integrationId)
            put("sdk_version", VeritySdkVersion.PAYLOAD)
            put("signals", JSONObject().apply {
                put("ua", "VerityProAndroid/${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})")
                put("platform", "android")
                put("screen", JSONObject().apply {
                    put("w", metrics.widthPixels)
                    put("h", metrics.heightPixels)
                    // Not a placeholder: Android composites every display as RGBA_8888 (32 bits);
                    // Display.getPixelFormat() is documented to always return RGBA_8888 since API 17.
                    put("depth", 32)
                    put("ratio", metrics.density.toDouble())
                })
                put("tz_name", tz.id)
                put("tz_offset", timezoneOffsetMinutes(tz, System.currentTimeMillis()))
                put("language", locale.language)
                put("touch_points", touchPointsFromFeatures { context.packageManager.hasSystemFeature(it) })
                getBatteryLevel(context)?.let { put("battery", it) }
                // Root is is_rooted; is_jailbroken is the iOS signal. Unknown is sent as null, never false.
                put("is_rooted", isRooted() ?: JSONObject.NULL)
                put("is_emulator", isEmulator())
                put("is_frida_detected", fridaSignal(readProcessMaps()) ?: JSONObject.NULL)
                put("visitor_id", getOrCreateVisitorId(context))
                put("session_id", UUID.randomUUID().toString())
                put("collected_at", System.currentTimeMillis())
            })
        }
    }

    private fun getBatteryLevel(context: Context): Double? = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (level >= 0) level.toDouble() / 100.0 else null
    } catch (_: Exception) { null }

    private fun isRooted(): Boolean? = try {
        listOf(
            "/su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su"
        ).any { java.io.File(it).exists() }
    } catch (e: Exception) {
        Log.w(TAG, "Root check unreadable: ${e.javaClass.simpleName}")
        null
    }

    private fun isEmulator(): Boolean = try {
        Build.FINGERPRINT?.startsWith("generic") == true ||
        Build.FINGERPRINT?.startsWith("unknown") == true ||
        Build.MODEL?.contains("google_sdk") == true ||
        Build.MODEL?.contains("Emulator") == true ||
        Build.MODEL?.contains("Android SDK built for x86") == true ||
        Build.MANUFACTURER?.contains("Genymotion") == true ||
        (Build.BRAND?.startsWith("generic") == true && Build.DEVICE?.startsWith("generic") == true)
    } catch (_: Exception) { false }

    private fun readProcessMaps(): String? = try {
        java.io.File("/proc/self/maps").readText()
    } catch (e: Exception) {
        Log.w(TAG, "Frida check unreadable: ${e.javaClass.simpleName}")
        null
    }
}

/** Unreadable process maps is unknown (null), not a clean negative. */
internal fun fridaSignal(processMaps: String?): Boolean? = processMaps?.let {
    it.contains("frida") || it.contains("gadget")
}

/**
 * Android has no max-touch-points API; the touchscreen features the device declares give a floor.
 * Real phones declare jazzhand (5+). Never report touch the device did not declare.
 */
internal fun touchPointsFromFeatures(hasFeature: (String) -> Boolean): Int = when {
    hasFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND) -> 5
    hasFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT) -> 2
    hasFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH) -> 2
    hasFeature(PackageManager.FEATURE_TOUCHSCREEN) -> 1
    else -> 0
}
