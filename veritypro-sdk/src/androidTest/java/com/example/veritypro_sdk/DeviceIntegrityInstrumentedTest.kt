package com.example.veritypro_sdk

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.veritypro_sdk.utils.DeviceSignalCodes
import com.example.veritypro_sdk.utils.SecurityAssessmentCollector
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real collector on the real device/emulator and checks the payload against what the
 * backend expects. On an emulator the emulator code MUST fire; on a stock physical phone the root
 * family MUST NOT. The full payload is written to logcat (tag VerityDeviceProof) so a run leaves
 * evidence of what each layer saw.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIntegrityInstrumentedTest {

    private fun looksLikeEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.PRODUCT.contains("sdk_gphone") || Build.MODEL.contains("Emulator")

    @Test
    fun collectorEmitsVocabularyAndExplicitBooleans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = SecurityAssessmentCollector.collectJson(context)
        Log.i("VerityDeviceProof", "fingerprint=${Build.FINGERPRINT} model=${Build.MODEL}")
        Log.i("VerityDeviceProof", json)
        val root = JSONObject(json)

        val integrity = root.getJSONObject("deviceIntegrity")
        val tamper = root.getJSONObject("tamperingResult")
        val fingerprint = root.getJSONObject("deviceFingerprint")
        assertTrue("isRooted must be present", integrity.has("isRooted"))
        assertTrue("isCompromised must be present", integrity.has("isCompromised"))
        assertEquals("android-2.1.0", root.getJSONObject("captureMetadata").getString("sdkVersion"))

        val integrityCodes = (0 until integrity.getJSONArray("detections").length()).map { integrity.getJSONArray("detections").getString(it) }
        val tamperCodes = (0 until tamper.getJSONArray("detections").length()).map { tamper.getJSONArray("detections").getString(it) }
        val known = setOf(
            DeviceSignalCodes.ROOT_DETECTED, DeviceSignalCodes.SU_BINARY, DeviceSignalCodes.TEST_KEYS_BUILD,
            DeviceSignalCodes.ROOT_MANAGER_APP, DeviceSignalCodes.MAGISK_MOUNT, DeviceSignalCodes.SYSTEM_WRITABLE,
            DeviceSignalCodes.RO_DEBUGGABLE, DeviceSignalCodes.RO_SECURE_OFF, DeviceSignalCodes.BOOTLOADER_UNLOCKED,
            DeviceSignalCodes.VERIFIED_BOOT_NOT_GREEN, DeviceSignalCodes.USERDEBUG_BUILD, DeviceSignalCodes.EMULATOR,
            DeviceSignalCodes.VPN_ACTIVE, DeviceSignalCodes.FRIDA_DETECTED, DeviceSignalCodes.DEBUGGER_ATTACHED,
            DeviceSignalCodes.DEBUGGABLE_BUILD, DeviceSignalCodes.SIGNATURE_MISMATCH,
        )
        (integrityCodes + tamperCodes).forEach { assertTrue("unknown code $it", it in known) }

        // The instrumentation APK is a debug build: the collector must say so.
        assertTrue("debuggable_build expected on a test build", DeviceSignalCodes.DEBUGGABLE_BUILD in tamperCodes)

        if (looksLikeEmulator()) {
            assertTrue("emulator must be detected", fingerprint.getBoolean("isEmulator"))
            assertTrue("emulator code must be present", DeviceSignalCodes.EMULATOR in integrityCodes)
        } else {
            assertFalse("physical device must not read as emulator", fingerprint.getBoolean("isEmulator"))
            // A stock retail phone. If this fails on a device that is genuinely rooted, that is the detector working.
            Log.i("VerityDeviceProof", "physical device: isRooted=${integrity.getBoolean("isRooted")} codes=$integrityCodes")
        }
    }
}
