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
    @Test fun `default base url is production`() { assertEquals("https://api.veritypro.ai", VerityDevice.DEFAULT_BASE_URL) }
}
