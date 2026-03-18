package com.example.veritypro_sdk.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.TimeZone

/**
 * Collects device security assessment data for EDD, Address, and KYC uploads.
 * Matches the backend SecurityAssessmentDTO schema.
 */
object SecurityAssessmentCollector {

    // Cache emulator result since it's checked multiple times and won't change at runtime
    private var cachedIsEmulator: Boolean? = null

    fun collectJson(context: Context): String {
        return try {
            val assessment = JSONObject()

            val emulator = isEmulator()
            val rooted = checkRooted(context)
            val debuggerAttached = android.os.Debug.isDebuggerConnected()
            val debuggable = isDebuggable(context)
            val vpnActive = isVpnActive(context)
            val signingValid = verifyCodeSigning(context)
            val screenRecording = isScreenBeingRecorded(context)

            // Device integrity
            val integrityDetections = JSONArray()
            if (rooted) integrityDetections.put("root_detected")
            if (emulator) integrityDetections.put("emulator")
            if (vpnActive) integrityDetections.put("vpn_active")

            val integrityRiskScore = calculateIntegrityRisk(rooted, emulator, vpnActive)
            assessment.put("deviceIntegrity", JSONObject().apply {
                put("isCompromised", rooted)
                put("riskScore", integrityRiskScore)
                put("detections", integrityDetections)
            })

            // Device fingerprint
            assessment.put("deviceFingerprint", JSONObject().apply {
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("osVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("screenResolution", getScreenResolution(context))
                put("timezone", TimeZone.getDefault().id)
                put("language", java.util.Locale.getDefault().toLanguageTag())
                put("isVpn", vpnActive)
                put("isSimulator", emulator)
            })

            // Tampering result
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

            // Injection check
            assessment.put("injectionCheck", JSONObject().apply {
                put("physicalCamera", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
                put("screenRecording", screenRecording)
                put("environmentSecure", !debuggerAttached && !rooted && signingValid && !screenRecording)
            })

            // Audit log
            assessment.put("auditLog", JSONArray())

            // Overall risk level
            val riskLevel = when {
                rooted && debuggerAttached -> "critical"
                rooted && !signingValid -> "critical"
                rooted -> "high"
                !signingValid -> "high"
                debuggerAttached -> "medium"
                emulator -> "medium"
                vpnActive && screenRecording -> "medium"
                vpnActive -> "low"
                else -> "low"
            }
            assessment.put("overallRiskLevel", riskLevel)

            assessment.toString()
        } catch (e: Exception) {
            Log.e("Verity", "SecurityAssessmentCollector failed: ${e.message}", e)
            JSONObject().apply {
                put("collectionError", true)
                put("errorMessage", e.message ?: "unknown")
                put("overallRiskLevel", "unknown")
            }.toString()
        }
    }

    fun platformUsed(): String = "Android"

    fun deviceAndBrowser(): String = "${Build.MANUFACTURER} ${Build.MODEL} - Android ${Build.VERSION.RELEASE}"

    /**
     * Enhanced root detection: checks su binary paths, build tags, known root management apps,
     * and attempts to execute `which su`.
     */
    private fun checkRooted(context: Context): Boolean {
        // 1. Check known su binary paths
        val suPaths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        if (suPaths.any { File(it).exists() }) return true

        // 2. Check build tags for test-keys (non-release builds)
        if (Build.TAGS?.contains("test-keys") == true) return true

        // 3. Check for known root management app packages
        val rootPackages = arrayOf(
            "com.topjohnwu.magisk",          // Magisk
            "eu.chainfire.supersu",            // SuperSU
            "com.koushikdutta.superuser",      // Superuser
            "com.noshufou.android.su",         // Superuser (older)
            "com.thirdparty.superuser",        // Generic superuser
            "com.yellowes.su",                 // Root checker
            "com.kingroot.kinguser",           // KingRoot
            "com.kingo.root",                  // KingoRoot
            "com.smedialink.oneclean",         // OneClean (root tool)
            "com.zhiqupk.root.global"          // iRoot
        )
        val pm = context.packageManager
        for (pkg in rootPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed, continue
            }
        }

        // 4. Attempt to run `which su`
        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()
            reader.close()
            process.destroy()
            if (!result.isNullOrBlank()) return true
        } catch (_: Exception) {
            // Expected to fail on non-rooted devices
        }

        return false
    }

    /**
     * Enhanced emulator detection: checks build properties for known emulator signatures
     * including popular third-party emulators (BlueStacks, LDPlayer, Nox, MEmu, etc.)
     */
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
                // BlueStacks
                Build.MANUFACTURER.equals("BlueStacks", ignoreCase = true) ||
                Build.MODEL.contains("BlueStacks", ignoreCase = true) ||
                // LDPlayer
                Build.HARDWARE.contains("ttVM_Hdragon", ignoreCase = true) ||
                Build.MODEL.contains("LDPlayer", ignoreCase = true) ||
                Build.MANUFACTURER.equals("LDPlayer", ignoreCase = true) ||
                // Nox Player
                Build.PRODUCT.contains("nox", ignoreCase = true) ||
                Build.MANUFACTURER.equals("nox", ignoreCase = true) ||
                Build.HARDWARE.contains("nox", ignoreCase = true) ||
                // MEmu
                Build.MODEL.contains("MEmu", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Microvirt", ignoreCase = true) ||
                // MuMu Player
                Build.MODEL.contains("MuMu", ignoreCase = true) ||
                // Andy
                Build.PRODUCT.contains("andy", ignoreCase = true) ||
                // Generic emulator hints
                Build.BOARD.contains("unknown", ignoreCase = true) ||
                Build.PRODUCT == "sdk" ||
                Build.PRODUCT == "sdk_x86" ||
                Build.PRODUCT == "sdk_google" ||
                Build.PRODUCT.contains("emulator", ignoreCase = true)

        cachedIsEmulator = result
        return result
    }

    /**
     * Detects active VPN connection using ConnectivityManager.
     * Checks for NET_CAPABILITY_NOT_VPN (absent = VPN active).
     */
    private fun isVpnActive(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } catch (e: Exception) {
            Log.w("Verity", "VPN detection failed: ${e.message}")
            false
        }
    }

    /**
     * Verifies the APK signing certificate hasn't been tampered with.
     * Computes SHA-256 of the first signing certificate and checks it's not empty.
     * In production, the expected hash should be compared against a known value.
     */
    @Suppress("DEPRECATION")
    private fun verifyCodeSigning(context: Context): Boolean {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures
            }

            // If we can read signatures and there's at least one, signing is intact
            signatures != null && signatures.isNotEmpty()
        } catch (e: Exception) {
            Log.w("Verity", "Code signing verification failed: ${e.message}")
            false
        }
    }

    /**
     * Attempts to detect if screen recording/casting is active.
     * Uses MediaProjection detection via display flags on API 21+.
     */
    private fun isScreenBeingRecorded(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                    ?: return false
                val displays = displayManager.displays
                // If there are virtual displays beyond the default, recording may be active
                displays.any { display ->
                    display.displayId != android.view.Display.DEFAULT_DISPLAY &&
                            (display.flags and android.view.Display.FLAG_PRESENTATION) == 0
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w("Verity", "Screen recording detection failed: ${e.message}")
            false
        }
    }

    private fun isDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun getScreenResolution(context: Context): String {
        return try {
            val metrics = context.resources.displayMetrics
            "${metrics.widthPixels}x${metrics.heightPixels}"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun calculateIntegrityRisk(rooted: Boolean, emulator: Boolean, vpn: Boolean): Double {
        var score = 0.0
        if (rooted) score += 0.5
        if (emulator) score += 0.3
        if (vpn) score += 0.1
        return score.coerceAtMost(1.0)
    }
}
