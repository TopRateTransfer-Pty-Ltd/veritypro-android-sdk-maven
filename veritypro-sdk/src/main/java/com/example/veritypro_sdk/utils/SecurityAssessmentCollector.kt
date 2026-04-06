package com.example.veritypro_sdk.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.app.ActivityManager
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Runtime capture data passed from the capture/liveness screens.
 * All fields are nullable — old call sites that use collectJson(context) still work.
 */
data class CaptureRuntimeData(
    // captureMetadata
    val captureAttempts: Int? = null,
    val captureDurationSeconds: Double? = null,
    val antiSpoofBurstScore: Double? = null,
    val livenessConfidence: Double? = null,
    val facesDetected: Int? = null,
    val faceBoundingBox: FloatArray? = null,
    val faceQualityScore: Double? = null,
    // geolocation
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,
    val locationString: String? = null,
    val locationTimestamp: String? = null,
    val locationSource: String? = null,
    val countryCode: String? = null,
    // motionAnalysis
    val motionDurationMs: Long? = null,
    val motionSampleCount: Int? = null,
    val accelStdDev: FloatArray? = null,
    val gyroStdDev: FloatArray? = null,
    val motionScore: Float? = null,
    // auditLog events collected during session
    val auditEvents: List<AuditEvent>? = null,
)

data class AuditEvent(
    val timestamp: String,
    val sessionId: String? = null,
    val event: String,
    val category: String,
    val metadata: Map<String, Any>? = null,
)

/**
 * Collects device security assessment data for EDD, Address, and KYC uploads.
 * Generates SecurityAssessmentJson with 9 sections and 18 risk flags.
 */
object SecurityAssessmentCollector {

    private var cachedIsEmulator: Boolean? = null

    /** Backward-compatible overload — no runtime capture data. */
    fun collectJson(context: Context): String = collectJson(context, null)

    /** Full collection with optional runtime capture/geolocation/motion data. */
    fun collectJson(context: Context, runtimeData: CaptureRuntimeData?): String {
        return try {
            val assessment = JSONObject()

            val emulator = isEmulator()
            val rooted = checkRooted(context)
            val debuggerAttached = android.os.Debug.isDebuggerConnected()
            val debuggable = isDebuggable(context)
            val vpnActive = isVpnActive(context)
            val signingValid = verifyCodeSigning(context)
            val screenRecording = isScreenBeingRecorded(context)
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val hasAccel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            val hasGyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

            // ─── 1. deviceIntegrity ───
            val integrityDetections = JSONArray()
            if (rooted) integrityDetections.put("root_detected")
            if (emulator) integrityDetections.put("emulator")
            if (vpnActive) integrityDetections.put("vpn_active")

            assessment.put("deviceIntegrity", JSONObject().apply {
                put("isCompromised", rooted)
                put("riskScore", calculateIntegrityRisk(rooted, emulator, vpnActive))
                put("detections", integrityDetections)
            })

            // ─── 2. deviceFingerprint ───
            val metrics = context.resources.displayMetrics
            val batteryInfo = getBatteryInfo(context)
            assessment.put("deviceFingerprint", JSONObject().apply {
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("osVersion", "Android ${Build.VERSION.RELEASE}")
                put("screenResolution", "${metrics.widthPixels}x${metrics.heightPixels}")
                put("screenDensity", metrics.density.toDouble())
                put("timezone", TimeZone.getDefault().id)
                put("locale", Locale.getDefault().toString())
                put("language", Locale.getDefault().language)
                put("batteryLevel", batteryInfo.first.toDouble())
                put("isCharging", batteryInfo.second)
                put("availableDiskGB", getAvailableDiskGB())
                put("totalRAMGB", getTotalRAMGB(context))
                put("isVPNActive", vpnActive)
                put("isEmulator", emulator)
                put("timeSinceBootSeconds", SystemClock.elapsedRealtime() / 1000.0)
                put("fingerprintHash", generateFingerprintHash())
                put("hasAccelerometer", hasAccel)
                put("hasGyroscope", hasGyro)
                put("accelerometerVendor", getSensorVendor(sensorManager, Sensor.TYPE_ACCELEROMETER))
                put("gyroscopeVendor", getSensorVendor(sensorManager, Sensor.TYPE_GYROSCOPE))
            })

            // ─── 3. tamperingResult ───
            val tamperDetections = JSONArray()
            if (debuggerAttached) tamperDetections.put("debugger_attached")
            if (debuggable) tamperDetections.put("debuggable_build")
            if (!signingValid) tamperDetections.put("signature_mismatch")
            if (rooted) tamperDetections.put("root_detected")

            assessment.put("tamperingResult", JSONObject().apply {
                put("isTampered", debuggerAttached || rooted || !signingValid)
                put("debuggerAttached", debuggerAttached)
                put("codeSigningValid", signingValid)
                put("detections", tamperDetections)
            })

            // ─── 4. injectionCheck ───
            assessment.put("injectionCheck", JSONObject().apply {
                put("physicalCamera", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
                put("screenRecording", screenRecording)
                put("environmentSecure", !debuggerAttached && !rooted && signingValid && !screenRecording)
            })

            // ─── 5. auditLog ───
            val auditArr = JSONArray()
            runtimeData?.auditEvents?.forEach { evt ->
                auditArr.put(JSONObject().apply {
                    put("timestamp", evt.timestamp)
                    if (evt.sessionId != null) put("sessionId", evt.sessionId)
                    put("event", evt.event)
                    put("category", evt.category)
                    put("metadata", JSONObject(evt.metadata ?: emptyMap<String, Any>()))
                })
            }
            assessment.put("auditLog", auditArr)

            // ─── 6. captureMetadata ───
            val hasPhysicalCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            val networkType = getNetworkType(context)
            assessment.put("captureMetadata", JSONObject().apply {
                put("sdkVersion", "android-2.0.0")
                put("isPhysicalCamera", hasPhysicalCamera)
                put("networkType", networkType)
                put("captureAttempts", runtimeData?.captureAttempts ?: 0)
                put("captureDurationSeconds", runtimeData?.captureDurationSeconds ?: 0.0)
                putOpt("antiSpoofBurstScore", runtimeData?.antiSpoofBurstScore)
                putOpt("livenessConfidence", runtimeData?.livenessConfidence)
                putOpt("facesDetected", runtimeData?.facesDetected)
                if (runtimeData?.faceBoundingBox != null) {
                    put("faceBoundingBox", JSONArray().apply {
                        runtimeData.faceBoundingBox.forEach { put(it.toDouble()) }
                    })
                }
                putOpt("faceQualityScore", runtimeData?.faceQualityScore)
            })

            // ─── 7. geolocation ───
            assessment.put("geolocation", JSONObject().apply {
                putOpt("latitude", runtimeData?.latitude)
                putOpt("longitude", runtimeData?.longitude)
                putOpt("accuracy", runtimeData?.locationAccuracy?.toDouble())
                putOpt("locationString", runtimeData?.locationString)
                putOpt("countryCode", runtimeData?.countryCode)
                putOpt("timestamp", runtimeData?.locationTimestamp)
                put("source", runtimeData?.locationSource ?: "none")
            })

            // ─── 8. motionAnalysis ───
            assessment.put("motionAnalysis", JSONObject().apply {
                put("durationMs", runtimeData?.motionDurationMs ?: 0L)
                put("sampleCount", runtimeData?.motionSampleCount ?: 0)
                put("accelStdDev", JSONArray().apply {
                    runtimeData?.accelStdDev?.forEach { put(it.toDouble()) } ?: run {
                        put(0.0); put(0.0); put(0.0)
                    }
                })
                put("gyroStdDev", JSONArray().apply {
                    runtimeData?.gyroStdDev?.forEach { put(it.toDouble()) } ?: run {
                        put(0.0); put(0.0); put(0.0)
                    }
                })
                put("motionScore", runtimeData?.motionScore?.toDouble() ?: 0.0)
            })

            // ─── 9. overallRiskLevel (with full risk scorer) ───
            val riskScore = calculateFullRiskScore(
                rooted = rooted,
                emulator = emulator,
                vpnActive = vpnActive,
                debuggerAttached = debuggerAttached,
                signingValid = signingValid,
                physicalCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                screenRecording = screenRecording,
                environmentSecure = !debuggerAttached && !rooted && signingValid && !screenRecording,
                hasAccel = hasAccel,
                hasGyro = hasGyro,
                captureAttempts = runtimeData?.captureAttempts,
                captureDurationSeconds = runtimeData?.captureDurationSeconds,
                antiSpoofBurstScore = runtimeData?.antiSpoofBurstScore,
                livenessConfidence = runtimeData?.livenessConfidence,
                facesDetected = runtimeData?.facesDetected,
                hasLocation = runtimeData?.latitude != null,
                motionScore = runtimeData?.motionScore,
            )
            val riskLevel = when {
                riskScore >= 0.7 -> "critical"
                riskScore >= 0.45 -> "high"
                riskScore >= 0.2 -> "medium"
                else -> "low"
            }
            assessment.put("overallRiskLevel", riskLevel)

            assessment.toString()
        } catch (e: Exception) {
            Log.w("Verity", "SecurityAssessmentCollector: collection failed")
            JSONObject().apply {
                put("collectionError", true)
                put("overallRiskLevel", "unknown")
            }.toString()
        }
    }

    fun platformUsed(): String = "Android"

    fun deviceAndBrowser(): String = "${Build.MANUFACTURER} ${Build.MODEL} - Android ${Build.VERSION.RELEASE}"

    // ─── Risk scorer: 18 flags ───────────────────────────────────────────────────

    private fun calculateFullRiskScore(
        rooted: Boolean,
        emulator: Boolean,
        vpnActive: Boolean,
        debuggerAttached: Boolean,
        signingValid: Boolean,
        physicalCamera: Boolean,
        screenRecording: Boolean,
        environmentSecure: Boolean,
        hasAccel: Boolean,
        hasGyro: Boolean,
        captureAttempts: Int?,
        captureDurationSeconds: Double?,
        antiSpoofBurstScore: Double?,
        livenessConfidence: Double?,
        facesDetected: Int?,
        hasLocation: Boolean,
        motionScore: Float?,
    ): Double {
        var score = 0.0

        // Device integrity
        if (rooted) score += 0.35                                           // device_compromised

        // Device fingerprint
        if (emulator) score += 0.30                                         // emulator_detected
        if (vpnActive) score += 0.10                                        // vpn_active
        if (!hasAccel && !hasGyro) score += 0.05                            // no_motion_sensors

        // Tampering
        if (debuggerAttached) score += 0.25                                 // debugger_attached
        if (!signingValid) score += 0.20                                    // code_signing_invalid

        // Injection
        if (!physicalCamera) score += 0.30                                  // no_physical_camera
        if (screenRecording) score += 0.15                                  // screen_recording_active
        if (!environmentSecure) score += 0.10                               // environment_not_secure

        // Capture behavior
        if ((captureAttempts ?: 0) > 5) score += 0.10                       // excessive_capture_attempts
        if ((captureDurationSeconds ?: 999.0) < 2.0) score += 0.10         // capture_too_fast
        if (antiSpoofBurstScore != null && antiSpoofBurstScore < 0.3)
            score += 0.15                                                   // low_antispoof_score

        // Face biometrics
        if (livenessConfidence != null && livenessConfidence < 70.0)
            score += 0.20                                                   // low_liveness_confidence
        if ((facesDetected ?: 1) > 1) score += 0.10                         // multiple_faces_detected
        if (facesDetected != null && facesDetected == 0) score += 0.25      // no_face_detected

        // Geolocation
        if (!hasLocation) score += 0.05                                     // no_location_data

        // Motion anti-spoofing
        if (motionScore != null && motionScore < 0.05f)
            score += 0.15                                                   // static_device_during_capture

        return score.coerceIn(0.0, 1.0)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private fun getBatteryInfo(context: Context): Pair<Float, Boolean> {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val battery = context.registerReceiver(null, filter)
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level.toFloat() / scale else 0f
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, charging)
        } catch (e: Exception) {
            Pair(0f, false)
        }
    }

    private fun getAvailableDiskGB(): Double {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) { 0.0 }
    }

    private fun getTotalRAMGB(context: Context): Double {
        return try {
            val info = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            info.totalMem / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) { 0.0 }
    }

    private fun generateFingerprintHash(): String {
        return try {
            val raw = "${Build.BOARD}|${Build.BRAND}|${Build.DEVICE}|${Build.HARDWARE}|" +
                    "${Build.MANUFACTURER}|${Build.MODEL}|${Build.PRODUCT}|${Build.FINGERPRINT}"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(32)
        } catch (e: Exception) { "unknown" }
    }

    private fun getSensorVendor(sm: SensorManager?, type: Int): Any {
        return try {
            sm?.getDefaultSensor(type)?.vendor ?: JSONObject.NULL
        } catch (e: Exception) { JSONObject.NULL }
    }

    fun isoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    // ─── Detection helpers (unchanged) ───────────────────────────────────────────

    /**
     * Returns true when the device appears to be rooted / has su binaries.
     * Public so that callers can gate verification on device integrity.
     */
    fun checkRooted(context: Context): Boolean {
        val suPaths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
        )
        if (suPaths.any { File(it).exists() }) return true
        if (Build.TAGS?.contains("test-keys") == true) return true
        val rootPackages = arrayOf(
            "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser",
            "com.noshufou.android.su", "com.thirdparty.superuser", "com.yellowes.su",
            "com.kingroot.kinguser", "com.kingo.root", "com.smedialink.oneclean",
            "com.zhiqupk.root.global"
        )
        val pm = context.packageManager
        for (pkg in rootPackages) {
            try { pm.getPackageInfo(pkg, 0); return true }
            catch (_: PackageManager.NameNotFoundException) {}
        }
        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()
            reader.close(); process.destroy()
            if (!result.isNullOrBlank()) return true
        } catch (_: Exception) {}
        return false
    }

    private fun isEmulator(): Boolean {
        cachedIsEmulator?.let { return it }
        val result = Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.PRODUCT.contains("sdk_gphone") ||
                Build.PRODUCT.contains("vbox86p") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.MANUFACTURER.equals("BlueStacks", ignoreCase = true) ||
                Build.MODEL.contains("BlueStacks", ignoreCase = true) ||
                Build.HARDWARE.contains("ttVM_Hdragon", ignoreCase = true) ||
                Build.MODEL.contains("LDPlayer", ignoreCase = true) ||
                Build.MANUFACTURER.equals("LDPlayer", ignoreCase = true) ||
                Build.PRODUCT.contains("nox", ignoreCase = true) ||
                Build.MANUFACTURER.equals("nox", ignoreCase = true) ||
                Build.HARDWARE.contains("nox", ignoreCase = true) ||
                Build.MODEL.contains("MEmu", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Microvirt", ignoreCase = true) ||
                Build.MODEL.contains("MuMu", ignoreCase = true) ||
                Build.PRODUCT.contains("andy", ignoreCase = true) ||
                Build.BOARD.contains("unknown", ignoreCase = true) ||
                Build.PRODUCT == "sdk" || Build.PRODUCT == "sdk_x86" ||
                Build.PRODUCT == "sdk_google" ||
                Build.PRODUCT.contains("emulator", ignoreCase = true)
        cachedIsEmulator = result
        return result
    }

    private fun isVpnActive(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } catch (e: Exception) { false }
    }

    @Suppress("DEPRECATION")
    private fun verifyCodeSigning(context: Context): Boolean {
        return try {
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val si = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                if (si?.hasMultipleSigners() == true) si.apkContentsSigners else si?.signingCertificateHistory
            } else {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            sigs != null && sigs.isNotEmpty()
        } catch (e: Exception) { false }
    }

    /**
     * Checks whether the device appears to have an active screen recording
     * or screen-cast session via the DisplayManager virtual display heuristic.
     *
     * Public so that capture screens can call this during the active
     * verification flow (not only at submission time), matching iOS parity.
     */
    fun isScreenBeingRecorded(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager ?: return false
                dm.displays.any { it.displayId != android.view.Display.DEFAULT_DISPLAY && (it.flags and android.view.Display.FLAG_PRESENTATION) == 0 }
            } else false
        } catch (e: Exception) { false }
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun getNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
            val net = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(net) ?: return "unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "unknown"
            }
        } catch (e: Exception) { "unknown" }
    }

    private fun calculateIntegrityRisk(rooted: Boolean, emulator: Boolean, vpn: Boolean): Double {
        var score = 0.0
        if (rooted) score += 0.5; if (emulator) score += 0.3; if (vpn) score += 0.1
        return score.coerceAtMost(1.0)
    }
}
