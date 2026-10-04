package com.veritypro.devicesdk
import org.junit.Assert.*
import org.junit.Test
class DeviceSignalTest {
    @Test fun `unreadable process maps is unknown not clean`() { assertNull(fridaSignal(null)) }
    @Test fun `loaded frida library is detected`() { assertEquals(true, fridaSignal("1000-2000 r-xp /data/app/libfrida-gadget.so")) }
    @Test fun `ordinary process maps is negative`() { assertEquals(false, fridaSignal("1000-2000 r-xp /system/lib64/libc.so")) }

    @Test fun `phone declaring jazzhand reports five`() {
        assertEquals(5, touchPointsFromFeatures { it == android.content.pm.PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND })
    }
    @Test fun `no declared touchscreen reports zero not five`() { assertEquals(0, touchPointsFromFeatures { false }) }
    @Test fun `api origin is required and must be an https origin`() {
        for (bad in listOf(null, "", " ", "http://gateway.test", "https://gateway.test/intelligence", "https://u:p@gateway.test", "gateway.test")) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { requireApiOrigin(bad) }
        }
        assertEquals("https://gateway.test", requireApiOrigin("https://gateway.test/"))
    }
}
