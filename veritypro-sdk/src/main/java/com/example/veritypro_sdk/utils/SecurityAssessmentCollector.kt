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
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.app.ActivityManager
import android.provider.Settings
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
    val locationAvailability: String? = null,
    val countryCode: String? = null,
    // motionAnalysis
    val motionDurationMs: Long? = null,
    val motionSampleCount: Int? = null,
    val accelStdDev: FloatArray? = null,
    val gyroStdDev: FloatArray? = null,
    val motionScore: Float? = null,
    val motionAvailability: String? = null,
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

    /** Kept for source compatibility; process-global capture data could cross verification sessions. */
    @Deprecated("Pass session-local runtime data to collectJson(context, runtimeData)")
    fun storeRuntimeData(@Suppress("UNUSED_PARAMETER") data: CaptureRuntimeData) = Unit

    /** Legacy callers have no session context: never borrow another verification's measurements. */
    fun collectJson(context: Context): String = collectJson(context, null)

    /** Full collection with optional runtime capture/geolocation/motion data. */
    fun collectJson(context: Context, runtimeData: CaptureRuntimeData?): String {
        return try {
            val assessment = JSONObject()
            assessment.put("SchemaVersion", 1)
            assessment.put("CollectedAt", java.time.Instant.now().toString())

            val emulator = isEmulator()
            val probes = runProbes(context)
            val rootProbe = probes.root
            val bootProbe = probes.boot
            val frida = probes.frida
            val debuggerAttached = android.os.Debug.isDebuggerConnected()
            val debuggable = isDebuggable(context)
            val vpnActive = isVpnActive(context)
            val signingValid = verifyCodeSigning(context)
            val screenRecording = isScreenBeingRecorded(context)
            val verdict = DeviceSignalCodes.derive(
                root = rootProbe, boot = bootProbe, emulator = emulator, vpnActive = vpnActive,
                debuggerAttached = debuggerAttached, debuggable = debuggable, signingValid = signingValid,
                frida = frida, screenRecording = screenRecording,
            )
            val rooted = verdict.isRooted
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val hasAccel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            val hasGyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

            // ─── 1. deviceIntegrity ───
            // Codes are the contract (DeviceSignalCodes <-> backend DeviceSignalVocabulary). The
            // explicit isRooted boolean is what the backend and the engine read first; the codes
            // say WHY so an analyst sees "magisk_mount", not just "rooted".
            assessment.put("deviceIntegrity", JSONObject().apply {
                put("isCompromised", verdict.isCompromised)
                put("isRooted", verdict.isRooted)
                put("bootloaderUnlocked", verdict.bootloaderUnlocked)
                put("riskScore", verdict.integrityRiskScore)
                put("detections", JSONArray(verdict.integrityDetections))
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
            assessment.put("tamperingResult", JSONObject().apply {
                put("isTampered", verdict.isTampered)
                put("debuggerAttached", debuggerAttached)
                put("debuggableBuild", debuggable)
                put("codeSigningValid", signingValid)
                put("detections", JSONArray(verdict.tamperDetections))
            })

            // ─── 4. injectionCheck ───
            assessment.put("injectionCheck", JSONObject().apply {
                put("physicalCamera", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
                put("screenRecording", screenRecording)
                put("environmentSecure", verdict.environmentSecure)
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
                put("sdkVersion", VeritySdkVersion.PAYLOAD)
                put("isPhysicalCamera", hasPhysicalCamera)
                put("networkType", networkType)
                putOpt("captureAttempts", runtimeData?.captureAttempts)
                putOpt("captureDurationSeconds", runtimeData?.captureDurationSeconds)
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
                putOpt("source", runtimeData?.locationSource)
            })

            // ─── 8. motionAnalysis ───
            assessment.put("motionAnalysis", JSONObject().apply {
                putOpt("durationMs", runtimeData?.motionDurationMs)
                putOpt("sampleCount", runtimeData?.motionSampleCount)
                runtimeData?.accelStdDev?.let { put("accelStdDev", JSONArray(it.toList())) }
                runtimeData?.gyroStdDev?.let { put("gyroStdDev", JSONArray(it.toList())) }
                putOpt("motionScore", runtimeData?.motionScore?.toDouble())
            })
            assessment.put("SignalAvailability", JSONObject().apply {
                put("device", "collected")
                put("network", if (networkType == "unknown") "unavailable" else "collected")
                put("behavior", runtimeData?.motionAvailability ?: if (runtimeData?.captureAttempts != null) "collected" else "unavailable")
                put("location", runtimeData?.locationAvailability ?: if (runtimeData?.latitude != null && runtimeData.longitude != null) "collected" else "unavailable")
            })

            // ─── 8b. forensics — environment signals for geo-consistency and network-anonymisation checks ───
            assessment.put("forensics", collectForensicSignals(context))

            // ─── 9. overallRiskLevel (with full risk scorer) ───
            val riskScore = calculateFullRiskScore(
                rooted = rooted,
                emulator = emulator,
                vpnActive = vpnActive,
                debuggerAttached = debuggerAttached,
                signingValid = signingValid,
                physicalCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                screenRecording = screenRecording,
                environmentSecure = verdict.environmentSecure,
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
                put("SchemaVersion", 1)
                put("CollectedAt", java.time.Instant.now().toString())
                put("SignalAvailability", JSONObject().apply {
                    listOf("device", "network", "behavior", "location").forEach { put(it, "error") }
                })
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
     * Returns true when the device appears to be rooted.
     * Public so that callers can gate verification on device integrity.
     */
    fun checkRooted(context: Context): Boolean = probeRoot(context).any

    private val suPaths = arrayOf(
        "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
        "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
    )

    // Declared in the SDK manifest <queries> block: without that, getPackageInfo throws
    // NameNotFoundException for every one of these on Android 11+ whether installed or not,
    // which is why this probe was silently dead until 2026-10-07.
    private val rootManagerPackages = arrayOf(
        "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser",
        "com.noshufou.android.su", "com.thirdparty.superuser", "com.yellowes.su",
        "com.kingroot.kinguser", "com.kingo.root", "com.smedialink.oneclean",
        "com.zhiqupk.root.global", "me.weishu.kernelsu", "io.github.vvb2060.magisk",
        "com.kingouser.com", "me.bmax.apatch"
    )

    /**
     * Every root probe, each as "evidence found". Heuristic by nature: Magisk with DenyList
     * unmounts itself from a denied process and hides su, so a clean result is "no evidence",
     * not "proven clean". Hardware attestation (Play Integrity) is the only signal that
     * survives that, and it is not part of this SDK yet.
     */
    internal fun probeRoot(context: Context): DeviceSignalCodes.RootProbe {
        val su = suPaths.any { runCatching { File(it).exists() }.getOrDefault(false) } || whichSuFound()
        val testKeys = Build.TAGS?.contains("test-keys") == true
        val pm = context.packageManager
        val manager = rootManagerPackages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0); true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                // A failing PackageManager query is not "not installed"; say so in the log rather
                // than silently reading as clean.
                Log.w("Verity", "root-manager probe failed for $pkg: ${e.javaClass.simpleName}")
                false
            }
        }
        val mounts = readProcLines("/proc/self/mounts")
        val magisk = mounts != null && DeviceSignalCodes.mountsLookRooted(mounts.asSequence())
        val writable = mounts != null && DeviceSignalCodes.mountsShowWritableSystem(mounts.asSequence())
        return DeviceSignalCodes.RootProbe(
            suBinary = su, testKeys = testKeys, rootManagerApp = manager, magiskMount = magisk,
            systemWritable = writable,
            unreadableSources = if (mounts == null) listOf("/proc/self/mounts") else emptyList(),
        )
    }

    internal fun probeBoot(): DeviceSignalCodes.BootProbe {
        // One `getprop` process (no args prints every property) instead of six; parsed once.
        val props = readAllProps()
        return DeviceSignalCodes.BootProbe(
            verifiedBootState = props?.get("ro.boot.verifiedbootstate"),
            flashLocked = props?.get("ro.boot.flash.locked"),
            vbmetaDeviceState = props?.get("ro.boot.vbmeta.device_state"),
            buildType = props?.get("ro.build.type") ?: Build.TYPE,
            roDebuggable = props?.get("ro.debuggable") == "1",
            roSecureOff = props?.get("ro.secure") == "0",
            unreadable = props == null,
        )
    }

    /** Raw results of the three probes that touch the filesystem, a subprocess or a socket. */
    internal class ProbeResults(
        val root: DeviceSignalCodes.RootProbe,
        val boot: DeviceSignalCodes.BootProbe,
        val frida: Boolean,
    )

    /**
     * Run probeRoot / probeBoot / isFridaPresent in parallel with ONE shared deadline, so the
     * collector blocks its caller for at most [PROBE_DEADLINE_MS] no matter how many subprocesses
     * are involved (the review on #51 measured ~7s worst case when they ran one after another).
     * A probe that misses the deadline is logged and reads as "no evidence" for that probe only.
     */
    internal fun runProbes(context: Context): ProbeResults {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "verity-probe").apply { isDaemon = true } }
        try {
            val rootF = pool.submit<DeviceSignalCodes.RootProbe> { probeRoot(context) }
            val bootF = pool.submit<DeviceSignalCodes.BootProbe> { probeBoot() }
            val fridaF = pool.submit<Boolean> { isFridaPresent() }
            val deadline = System.currentTimeMillis() + PROBE_DEADLINE_MS
            fun <T> await(name: String, f: java.util.concurrent.Future<T>, fallback: T): T = try {
                f.get((deadline - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                Log.w("Verity", "device probe '$name' missed the ${PROBE_DEADLINE_MS}ms deadline; reported as no evidence")
                f.cancel(true); fallback
            } catch (e: Exception) {
                Log.w("Verity", "device probe '$name' failed: ${e.javaClass.simpleName}; reported as no evidence")
                fallback
            }
            return ProbeResults(
                root = await("root", rootF, DeviceSignalCodes.RootProbe()),
                boot = await("boot", bootF, DeviceSignalCodes.BootProbe()),
                frida = await("frida", fridaF, false),
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private const val PROBE_DEADLINE_MS = 1500L

    /**
     * Frida: default server port open on loopback, or a gadget/agent mapped into this process.
     * The socket probe runs on its own thread so it works from the main thread too (Android
     * throws NetworkOnMainThreadException for a connect on the UI thread) and is bounded.
     */
    internal fun isFridaPresent(): Boolean {
        var portOpen = false
        val t = Thread {
            portOpen = try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", 27042), 150)
                    true
                }
            } catch (_: Exception) { false }
        }
        t.isDaemon = true
        t.start()
        t.join(400)
        if (portOpen) return true
        val maps = readProcLines("/proc/self/maps") ?: return false  // logged by readProcLines
        return DeviceSignalCodes.mapsShowFrida(maps.asSequence())
    }

    private fun whichSuFound(): Boolean = !runBounded("which", "su").isNullOrBlank()

    /** Lines of a /proc file, or null when it cannot be read. Null is reported as PROBE_UNREADABLE by the caller. */
    private fun readProcLines(path: String): List<String>? = try {
        File(path).bufferedReader().use { it.readLines() }
    } catch (e: Exception) {
        Log.w("Verity", "could not read $path: ${e.javaClass.simpleName}; recorded as probe_unreadable")
        null
    }

    /** `[ro.key]: [value]` lines from one bounded `getprop` run, or null when it failed or timed out. */
    private fun readAllProps(): Map<String, String>? {
        val lines = runBoundedLines("getprop") ?: return null
        val out = HashMap<String, String>(lines.size)
        for (line in lines) {
            val m = PROP_LINE.matchEntire(line.trim()) ?: continue
            out[m.groupValues[1]] = m.groupValues[2]
        }
        return out
    }

    private val PROP_LINE = Regex("""\[([^\]]+)\]: \[([^\]]*)\]""")

    /**
     * Run a tiny system command and return its first output line, or null. The process is
     * given at most one second BEFORE anything is read, so a command that never prints cannot
     * block the collector; a destroyed process yields null.
     */
    private fun runBounded(vararg cmd: String): String? =
        runBoundedLines(*cmd)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Runs a tiny command and returns its output lines, or null on failure/timeout. stdout is drained
     * on a reader thread WHILE the process runs: a bare `getprop` dump is routinely larger than the
     * 64 KB pipe buffer, and waiting before reading would block the child on a full pipe until the
     * timeout, which then read as "no properties" (review on #51).
     */
    private fun runBoundedLines(vararg cmd: String): List<String>? = try {
        // stdout only: stderr text ("not found", a permission message) must never read as output.
        val process = ProcessBuilder(*cmd).start()
        Thread({ try { process.errorStream.use { it.skip(Long.MAX_VALUE) } } catch (_: Exception) {} }, "verity-probe-stderr")
            .apply { isDaemon = true; start() }
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().useLines { seq -> seq.forEach { lines.add(it) } }
            } catch (_: Exception) { /* process destroyed: partial output is discarded below */ }
        }, "verity-probe-reader").apply { isDaemon = true; start() }
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(200)
            Log.w("Verity", "'${cmd.joinToString(" ")}' did not finish within 1s; recorded as probe_unreadable")
            null
        } else {
            reader.join(500)
            when {
                reader.isAlive -> {
                    // The drain did not finish: a truncated property map would read as "clean".
                    Log.w("Verity", "'${cmd.joinToString(" ")}' output not fully read within 500ms; recorded as probe_unreadable")
                    null
                }
                process.exitValue() != 0 -> null   // e.g. `which su` -> not found. Not an error, not evidence.
                else -> ArrayList(lines)
            }
        }
    } catch (e: Exception) {
        Log.w("Verity", "'${cmd.joinToString(" ")}' failed: ${e.javaClass.simpleName}; recorded as probe_unreadable")
        null
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

    // ─── Forensic environment signals (iOS parity) ────────────────────────────────

    private fun collectForensicSignals(context: Context): JSONObject {
        val obj = JSONObject()
        // Network anonymisation
        obj.put("vpnActive", isVpnActive(context))
        obj.put("proxyConfigured", isProxyConfigured())
        // Locale / timezone — backend cross-checks against claimed country + IP geolocation
        obj.put("timezone", TimeZone.getDefault().id)
        obj.put("utcOffsetSeconds", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000)
        obj.put("localeRegion", Locale.getDefault().country)
        obj.put("preferredLanguage", Locale.getDefault().language)
        // Device / environment state
        obj.put("lowPowerMode", isPowerSaveMode(context))
        obj.put("thermalState", getThermalState(context))
        obj.put("deviceUptimeSeconds", SystemClock.elapsedRealtime() / 1000L)
        obj.put("screenBrightness", getScreenBrightness(context))
        val (batteryPct, _) = getBatteryInfo(context)
        if (batteryPct >= 0f) obj.put("batteryLevel", batteryPct.toDouble())
        obj.put("multitaskingSupported", true)
        return obj
    }

    private fun isProxyConfigured(): Boolean {
        val host = System.getProperty("http.proxyHost") ?: System.getProperty("https.proxyHost")
        return !host.isNullOrBlank()
    }

    private fun isPowerSaveMode(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode ?: false
        } catch (e: Exception) { false }
    }

    private fun getThermalState(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "nominal"
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "nominal"
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "nominal"
                PowerManager.THERMAL_STATUS_LIGHT -> "fair"
                PowerManager.THERMAL_STATUS_MODERATE -> "fair"
                PowerManager.THERMAL_STATUS_SEVERE -> "serious"
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
                else -> "nominal"
            }
        } catch (e: Exception) { "nominal" }
    }

    private fun getScreenBrightness(context: Context): Double {
        return try {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (raw < 0) -1.0 else raw / 255.0  // normalise to 0.0–1.0, matching iOS UIScreen.brightness
        } catch (e: Exception) { -1.0 }
    }
}
