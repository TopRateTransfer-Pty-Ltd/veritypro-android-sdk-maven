package com.veritypro.devicesdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * Collects mobile device signals and submits them to POST /aml-intelligence/api/v1/device/sessions.
 * Returns a vpds_* token to embed in transactions for server-side device risk scoring.
 * Non-fatal: returns null on network error (logged).
 *
 * Standalone implementation — no dependency on the full veritypro-sdk module.
 */
object VpDeviceSessionService {

    private const val TAG = "VerityDevice"
    private const val PREFS_NAME = "vp_device_prefs"
    private const val VISITOR_ID_KEY = "_vp_vid"

    /**
     * @param apiKey Deprecated and ignored; never sent. The endpoint is anonymous and attributes
     *   by integration_id, so a key in the app would only leak it.
     */
    suspend fun collectAndSubmit(
        context: Context,
        apiKey: String? = null,
        baseUrl: String,
        integrationId: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val rooted = isRooted()
            val emulator = isEmulator()
            val frida = isFridaDetected()

            val visitorId = getOrCreateVisitorId(context)
            val sessionId = UUID.randomUUID().toString()
            val collectedAt = System.currentTimeMillis()

            val metrics = context.resources.displayMetrics
            val tzOffset = -TimeZone.getDefault().getOffset(collectedAt) / 60_000

            val screen = JSONObject().apply {
                put("w", metrics.widthPixels)
                put("h", metrics.heightPixels)
                // Not a placeholder: Android composites every display as RGBA_8888 (32 bits);
                // Display.getPixelFormat() is documented to always return RGBA_8888 since API 17.
                put("depth", 32)
                put("ratio", metrics.density.toDouble())
            }

            val signals = JSONObject().apply {
                put("ua", buildUA())
                put("platform", "Android")
                put("screen", screen)
                put("tz_name", TimeZone.getDefault().id)
                put("tz_offset", tzOffset)
                put("language", Locale.getDefault().language)
                put("touch_points", touchPointsFromFeatures { context.packageManager.hasSystemFeature(it) })
                put("is_rooted", rooted)
                put("is_emulator", emulator)
                put("is_frida_detected", frida ?: JSONObject.NULL)
                put("is_jailbroken", JSONObject.NULL)
                put("is_cloned_app", JSONObject.NULL)
                put("is_remote_control", JSONObject.NULL)
                put("factory_reset_days_ago", JSONObject.NULL)
                put("visitor_id", visitorId)
                put("session_id", sessionId)
                put("collected_at", collectedAt)
                // Browser-only fields — null on Android
                put("canvas_hash", JSONObject.NULL)
                put("webgl_hash", JSONObject.NULL)
                put("audio_hash", JSONObject.NULL)
                put("webgl_renderer", JSONObject.NULL)
                put("webdriver", JSONObject.NULL)
                put("eval_length", JSONObject.NULL)
                put("chrome_object", JSONObject.NULL)
                put("screen_outer_match", JSONObject.NULL)
                put("languages_count", JSONObject.NULL)
                put("notification_permission", JSONObject.NULL)
                put("webrtc_ips", JSONObject.NULL)
                put("fonts_hash", JSONObject.NULL)
                put("plugins_hash", JSONObject.NULL)
                put("hw_concurrency", JSONObject.NULL)
                put("device_memory", JSONObject.NULL)
                put("connection", JSONObject.NULL)
                put("cookies", JSONObject.NULL)
                put("local_storage", JSONObject.NULL)
                put("dnt", JSONObject.NULL)
                put("page_url", JSONObject.NULL)
                put("referrer", JSONObject.NULL)
                put("battery", JSONObject.NULL)
            }

            val body = JSONObject().apply {
                put("integration_id", integrationId)
                put("sdk_version", "android-device-1.0.0")
                put("signals", signals)
            }.toString()

            val base = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
            val url = URL("$base/aml-intelligence/api/v1/device/sessions")

            // SEC-025: HttpsURLConnection validates the chain against the device trust store, and the
            // handshake is then pinned to the ISRG root keys (same pins as veritypro-sdk) before any
            // byte of the request is sent. An app-trusted or user-installed CA alone is not enough.
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            conn.connect()
            SpkiPins.verify(conn)

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            if (conn.responseCode != 200) {
                Log.w(TAG, "Device session mint failed: HTTP ${conn.responseCode}")
                return@withContext null
            }

            val response = conn.inputStream.bufferedReader().readText()
            JSONObject(response).optString("token", null)
        } catch (e: Exception) {
            Log.w(TAG, "Device session mint failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun getOrCreateVisitorId(context: Context): String {
        // SEC-024: Use EncryptedSharedPreferences (Android Keystore-backed AES-256-GCM)
        // instead of plain SharedPreferences. Plain SharedPreferences stores values in an
        // unencrypted XML file readable on rooted devices or via ADB backup.
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val existing = prefs.getString(VISITOR_ID_KEY, null)
        if (existing != null) return existing
        val vid = UUID.randomUUID().toString()
        prefs.edit().putString(VISITOR_ID_KEY, vid).apply()
        return vid
    }

    /**
     * Lightweight inline root detection.
     * Does not require SecurityAssessmentCollector from the full KYC SDK.
     */
    private fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        return paths.any { java.io.File(it).exists() } ||
            try { Runtime.getRuntime().exec("which su").waitFor() == 0 } catch (e: Exception) { false }
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("Emulator", ignoreCase = true) ||
        Build.MODEL.contains("Android SDK built for x86", ignoreCase = true) ||
        Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
        Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) ||
        Build.HARDWARE.contains("goldfish") ||
        Build.HARDWARE.contains("ranchu")

    private fun isFridaDetected(): Boolean? = fridaSignal(runCatching {
        java.io.File("/proc/self/maps").readText()
    }.getOrNull())

    private fun buildUA(): String {
        val release = Build.VERSION.RELEASE
        val model = Build.MODEL
        return "Mozilla/5.0 (Linux; Android $release; $model) AppleWebKit/537.36 VerityProDeviceSDK/1.0.0"
    }
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

/** Unknown collection is distinct from a completed negative observation. */
internal fun fridaSignal(processMaps: String?): Boolean? = processMaps?.let {
    it.contains("frida", ignoreCase = true) || it.contains("libgadget", ignoreCase = true)
}
