package com.example.veritypro_sdk.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rule behind deviceIntegrity / tamperingResult. Pins the contract with the backend's
 * DeviceSignalVocabulary: every code emitted here is one the backend knows, and the booleans the
 * backend reads first (isRooted, isCompromised, isTampered) are derived the same way every time.
 */
class DeviceSignalCodesTest {

    private fun clean() = DeviceSignalCodes.derive(
        root = DeviceSignalCodes.RootProbe(), boot = DeviceSignalCodes.BootProbe(verifiedBootState = "green", flashLocked = "1", buildType = "user"),
        emulator = false, vpnActive = false, debuggerAttached = false, debuggable = false,
        signingValid = true, frida = false, screenRecording = false,
    )

    @Test
    fun stockReleaseDeviceEmitsNothing() {
        val v = clean()
        assertFalse(v.isRooted); assertFalse(v.isCompromised); assertFalse(v.isTampered); assertFalse(v.bootloaderUnlocked)
        assertTrue(v.environmentSecure)
        assertEquals(emptyList<String>(), v.integrityDetections)
        assertEquals(emptyList<String>(), v.tamperDetections)
        assertEquals(0.0, v.integrityRiskScore, 0.0)
    }

    @Test
    fun debugBuildIsReportedButIsNotTampering() {
        // The owner's staging session 7d5021d7: debug build of the host app on a stock phone.
        val v = DeviceSignalCodes.derive(
            root = DeviceSignalCodes.RootProbe(), boot = DeviceSignalCodes.BootProbe(verifiedBootState = "green"),
            emulator = false, vpnActive = false, debuggerAttached = false, debuggable = true,
            signingValid = true, frida = false, screenRecording = false,
        )
        assertEquals(listOf(DeviceSignalCodes.DEBUGGABLE_BUILD), v.tamperDetections)
        assertFalse(v.isTampered)
        assertFalse(v.isRooted)
    }

    @Test
    fun anySingleRootProbeMakesTheDeviceRootedAndCompromised() {
        val probes = listOf(
            DeviceSignalCodes.RootProbe(suBinary = true) to DeviceSignalCodes.SU_BINARY,
            DeviceSignalCodes.RootProbe(testKeys = true) to DeviceSignalCodes.TEST_KEYS_BUILD,
            DeviceSignalCodes.RootProbe(rootManagerApp = true) to DeviceSignalCodes.ROOT_MANAGER_APP,
            DeviceSignalCodes.RootProbe(magiskMount = true) to DeviceSignalCodes.MAGISK_MOUNT,
            DeviceSignalCodes.RootProbe(systemWritable = true) to DeviceSignalCodes.SYSTEM_WRITABLE,
        )
        for ((probe, code) in probes) {
            val v = DeviceSignalCodes.derive(
                root = probe, boot = DeviceSignalCodes.BootProbe(), emulator = false, vpnActive = false,
                debuggerAttached = false, debuggable = false, signingValid = true, frida = false, screenRecording = false,
            )
            assertTrue(code, v.isRooted); assertTrue(code, v.isCompromised); assertTrue(code, v.isTampered)
            assertFalse(code, v.environmentSecure)
            assertEquals(code, listOf(DeviceSignalCodes.ROOT_DETECTED, code), v.integrityDetections)
            assertEquals(code, listOf(DeviceSignalCodes.ROOT_DETECTED), v.tamperDetections)
        }
    }

    @Test
    fun unlockedBootloaderIsNotRootButIsReported() {
        for (boot in listOf(
            DeviceSignalCodes.BootProbe(verifiedBootState = "orange"),
            DeviceSignalCodes.BootProbe(flashLocked = "0"),
            DeviceSignalCodes.BootProbe(vbmetaDeviceState = "unlocked"),
        )) {
            val v = DeviceSignalCodes.derive(
                root = DeviceSignalCodes.RootProbe(), boot = boot, emulator = false, vpnActive = false,
                debuggerAttached = false, debuggable = false, signingValid = true, frida = false, screenRecording = false,
            )
            assertTrue(v.bootloaderUnlocked); assertFalse(v.isRooted); assertFalse(v.isTampered)
            assertEquals(listOf(DeviceSignalCodes.BOOTLOADER_UNLOCKED), v.integrityDetections)
        }
        val red = DeviceSignalCodes.derive(
            root = DeviceSignalCodes.RootProbe(), boot = DeviceSignalCodes.BootProbe(verifiedBootState = "red", buildType = "userdebug"),
            emulator = false, vpnActive = false, debuggerAttached = false, debuggable = false, signingValid = true, frida = false, screenRecording = false,
        )
        assertEquals(listOf(DeviceSignalCodes.VERIFIED_BOOT_NOT_GREEN, DeviceSignalCodes.USERDEBUG_BUILD), red.integrityDetections)
        assertTrue(red.bootloaderUnlocked); assertFalse(red.isRooted)
        val yellow = DeviceSignalCodes.derive(
            root = DeviceSignalCodes.RootProbe(), boot = DeviceSignalCodes.BootProbe(verifiedBootState = "yellow", flashLocked = "1"),
            emulator = false, vpnActive = false, debuggerAttached = false, debuggable = false, signingValid = true, frida = false, screenRecording = false,
        )
        // Custom-signed image on a locked bootloader is still not the manufacturer trusted state.
        assertEquals(listOf(DeviceSignalCodes.VERIFIED_BOOT_NOT_GREEN), yellow.integrityDetections)
        assertTrue(yellow.bootloaderUnlocked); assertFalse(yellow.isRooted)
    }

    @Test
    fun stockEmulatorImageIsEmulatorNotRooted() {
        // AVD images: ro.debuggable=1, ro.secure=0, verifiedbootstate orange. That is an emulator
        // and an untrusted build state, never "rooted" (which would hard-fail emulator QA as root).
        val v = DeviceSignalCodes.derive(
            root = DeviceSignalCodes.RootProbe(),
            boot = DeviceSignalCodes.BootProbe(verifiedBootState = "orange", buildType = "userdebug", roDebuggable = true, roSecureOff = true),
            emulator = true, vpnActive = false, debuggerAttached = false, debuggable = true, signingValid = true, frida = false, screenRecording = false,
        )
        assertFalse(v.isRooted); assertFalse(v.isCompromised); assertTrue(v.bootloaderUnlocked)
        assertEquals(listOf(DeviceSignalCodes.BOOTLOADER_UNLOCKED, DeviceSignalCodes.USERDEBUG_BUILD, DeviceSignalCodes.RO_DEBUGGABLE,
            DeviceSignalCodes.RO_SECURE_OFF, DeviceSignalCodes.EMULATOR), v.integrityDetections)
        assertFalse(v.integrityDetections.contains(DeviceSignalCodes.ROOT_DETECTED))
    }

    @Test
    fun emulatorAndFridaAndVpnAreCodedAndWeighted() {
        val v = DeviceSignalCodes.derive(
            root = DeviceSignalCodes.RootProbe(), boot = DeviceSignalCodes.BootProbe(), emulator = true, vpnActive = true,
            debuggerAttached = false, debuggable = false, signingValid = true, frida = true, screenRecording = false,
        )
        assertEquals(listOf(DeviceSignalCodes.EMULATOR, DeviceSignalCodes.VPN_ACTIVE, DeviceSignalCodes.FRIDA_DETECTED), v.integrityDetections)
        assertEquals(listOf(DeviceSignalCodes.FRIDA_DETECTED), v.tamperDetections)
        assertTrue(v.isCompromised); assertTrue(v.isTampered); assertFalse(v.isRooted)
        assertEquals(0.8, v.integrityRiskScore, 1e-9)
    }

    @Test
    fun debuggerAndBadSignatureAreTampering() {
        val v = DeviceSignalCodes.derive(
            root = DeviceSignalCodes.RootProbe(), boot = DeviceSignalCodes.BootProbe(), emulator = false, vpnActive = false,
            debuggerAttached = true, debuggable = true, signingValid = false, frida = false, screenRecording = true,
        )
        assertEquals(listOf(DeviceSignalCodes.DEBUGGER_ATTACHED, DeviceSignalCodes.DEBUGGABLE_BUILD, DeviceSignalCodes.SIGNATURE_MISMATCH), v.tamperDetections)
        assertTrue(v.isTampered); assertFalse(v.environmentSecure); assertFalse(v.isCompromised)
    }

    @Test
    fun mountAndMapParsersMatchRealLines() {
        val stockMounts = sequenceOf(
            "/dev/block/dm-0 / ext4 ro,seclabel,relatime 0 0",
            "/dev/block/dm-3 /system ext4 ro,seclabel,relatime 0 0",
            "tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,size=1380124k 0 0",
        )
        assertFalse(DeviceSignalCodes.mountsLookRooted(stockMounts))
        assertFalse(DeviceSignalCodes.mountsShowWritableSystem(stockMounts))

        val magiskMounts = sequenceOf(
            "/dev/block/dm-0 / ext4 ro,seclabel,relatime 0 0",
            "magisk /sbin/.magisk/mirror/system ext4 rw,seclabel,relatime 0 0",
        )
        assertTrue(DeviceSignalCodes.mountsLookRooted(magiskMounts))

        val rwSystem = sequenceOf("/dev/block/dm-3 /system ext4 rw,seclabel,relatime 0 0")
        assertTrue(DeviceSignalCodes.mountsShowWritableSystem(rwSystem))

        val maps = sequenceOf(
            "7f1234000-7f1240000 r-xp 00000000 fd:00 1234 /system/lib64/libc.so",
            "7f2000000-7f2100000 r-xp 00000000 fd:00 5678 /data/local/tmp/frida-agent-64.so",
        )
        assertTrue(DeviceSignalCodes.mapsShowFrida(maps))
        assertFalse(DeviceSignalCodes.mapsShowFrida(maps.take(1)))
    }
}
