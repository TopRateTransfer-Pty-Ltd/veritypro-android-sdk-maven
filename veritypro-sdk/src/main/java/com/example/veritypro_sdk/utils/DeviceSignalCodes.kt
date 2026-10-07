package com.example.veritypro_sdk.utils

/**
 * The machine vocabulary this SDK writes into `deviceIntegrity.detections` and
 * `tamperingResult.detections`, and the pure rule that turns raw probe results into the
 * booleans the backend reads (`isRooted`, `isCompromised`, `isTampered`).
 *
 * Shared contract with the backend (`DeviceSignalVocabulary` in Veritypro-KYC-Integration):
 * every string emitted here is listed there. The backend fails CLOSED on a string it does not
 * know, so adding a code here means adding it there in the same change.
 *
 * Kept free of Android framework types so the rule is unit-testable on the JVM; the probes
 * that need a Context live in [SecurityAssessmentCollector].
 */
object DeviceSignalCodes {
    // deviceIntegrity.detections — root family (any one => isRooted)
    const val ROOT_DETECTED = "root_detected"
    const val SU_BINARY = "su_binary"
    const val TEST_KEYS_BUILD = "test_keys_build"
    const val ROOT_MANAGER_APP = "root_manager_app"
    const val MAGISK_MOUNT = "magisk_mount"
    const val SYSTEM_WRITABLE = "system_writable"
    const val RO_DEBUGGABLE = "ro_debuggable"
    const val RO_SECURE_OFF = "ro_secure_off"

    // deviceIntegrity.detections — device left the manufacturer's trusted state (Review, not root)
    const val BOOTLOADER_UNLOCKED = "bootloader_unlocked"
    const val VERIFIED_BOOT_NOT_GREEN = "verified_boot_not_green"
    const val USERDEBUG_BUILD = "userdebug_build"

    // deviceIntegrity.detections — environment
    const val EMULATOR = "emulator"
    const val VPN_ACTIVE = "vpn_active"
    const val FRIDA_DETECTED = "frida_detected"

    // tamperingResult.detections
    const val DEBUGGER_ATTACHED = "debugger_attached"
    const val DEBUGGABLE_BUILD = "debuggable_build"
    const val SIGNATURE_MISMATCH = "signature_mismatch"

    /** Raw results of the root probes. Each is "evidence found", never "unknown". */
    data class RootProbe(
        val suBinary: Boolean = false,
        val testKeys: Boolean = false,
        val rootManagerApp: Boolean = false,
        val magiskMount: Boolean = false,
        val systemWritable: Boolean = false,
        val roDebuggable: Boolean = false,
        val roSecureOff: Boolean = false,
    ) {
        val any: Boolean
            get() = suBinary || testKeys || rootManagerApp || magiskMount || systemWritable || roDebuggable || roSecureOff
    }

    /** Verified-boot / bootloader system properties. Null when the property is absent or unreadable. */
    data class BootProbe(
        val verifiedBootState: String? = null,   // ro.boot.verifiedbootstate: green | yellow | orange | red
        val flashLocked: String? = null,         // ro.boot.flash.locked: "1" locked, "0" unlocked
        val vbmetaDeviceState: String? = null,   // ro.boot.vbmeta.device_state: locked | unlocked
        val buildType: String? = null,           // ro.build.type: user | userdebug | eng
    )

    data class Verdict(
        val isRooted: Boolean,
        val isCompromised: Boolean,
        val bootloaderUnlocked: Boolean,
        val isTampered: Boolean,
        val environmentSecure: Boolean,
        val integrityDetections: List<String>,
        val tamperDetections: List<String>,
        val integrityRiskScore: Double,
    )

    fun derive(
        root: RootProbe,
        boot: BootProbe,
        emulator: Boolean,
        vpnActive: Boolean,
        debuggerAttached: Boolean,
        debuggable: Boolean,
        signingValid: Boolean,
        frida: Boolean,
        screenRecording: Boolean,
    ): Verdict {
        val integrity = ArrayList<String>()
        if (root.any) integrity += ROOT_DETECTED
        if (root.suBinary) integrity += SU_BINARY
        if (root.testKeys) integrity += TEST_KEYS_BUILD
        if (root.rootManagerApp) integrity += ROOT_MANAGER_APP
        if (root.magiskMount) integrity += MAGISK_MOUNT
        if (root.systemWritable) integrity += SYSTEM_WRITABLE
        if (root.roDebuggable) integrity += RO_DEBUGGABLE
        if (root.roSecureOff) integrity += RO_SECURE_OFF

        val unlocked = boot.verifiedBootState.equals("orange", ignoreCase = true)
                || boot.flashLocked == "0"
                || boot.vbmetaDeviceState.equals("unlocked", ignoreCase = true)
        if (unlocked) integrity += BOOTLOADER_UNLOCKED
        if (boot.verifiedBootState.equals("red", ignoreCase = true)) integrity += VERIFIED_BOOT_NOT_GREEN
        if (boot.buildType.equals("userdebug", ignoreCase = true) || boot.buildType.equals("eng", ignoreCase = true)) {
            integrity += USERDEBUG_BUILD
        }

        if (emulator) integrity += EMULATOR
        if (vpnActive) integrity += VPN_ACTIVE
        if (frida) integrity += FRIDA_DETECTED

        val tamper = ArrayList<String>()
        if (debuggerAttached) tamper += DEBUGGER_ATTACHED
        if (debuggable) tamper += DEBUGGABLE_BUILD
        if (!signingValid) tamper += SIGNATURE_MISMATCH
        if (frida) tamper += FRIDA_DETECTED
        if (root.any) tamper += ROOT_DETECTED

        val rooted = root.any
        val tampered = debuggerAttached || rooted || !signingValid || frida
        var risk = 0.0
        if (rooted) risk += 0.5
        if (frida) risk += 0.4
        if (emulator) risk += 0.3
        if (unlocked) risk += 0.2
        if (vpnActive) risk += 0.1

        return Verdict(
            isRooted = rooted,
            isCompromised = rooted || frida,
            bootloaderUnlocked = unlocked,
            isTampered = tampered,
            environmentSecure = !debuggerAttached && !rooted && signingValid && !screenRecording && !frida,
            integrityDetections = integrity,
            tamperDetections = tamper,
            integrityRiskScore = risk.coerceAtMost(1.0),
        )
    }

    /** Lines of /proc/self/mounts that betray a Magisk-style overlay in this process's namespace. */
    fun mountsLookRooted(mounts: Sequence<String>): Boolean = mounts.any { line ->
        val l = line.lowercase()
        l.contains("magisk") || l.contains("/sbin/.magisk") || l.contains("core/mirror") || l.contains("/data/adb/modules")
    }

    /** Lines of /proc/self/mounts where "/" or "/system" is mounted read-write. Stock devices mount both read-only. */
    fun mountsShowWritableSystem(mounts: Sequence<String>): Boolean = mounts.any { line ->
        val parts = line.split(' ')
        if (parts.size < 4) return@any false
        val mountPoint = parts[1]
        val options = parts[3].split(',')
        (mountPoint == "/system" || mountPoint == "/") && options.firstOrNull() == "rw"
    }

    /** Lines of /proc/self/maps that show a Frida gadget / agent mapped into this process. */
    fun mapsShowFrida(maps: Sequence<String>): Boolean = maps.any { line ->
        val l = line.lowercase()
        l.contains("frida") || l.contains("libgadget") || l.contains("linjector")
    }
}
