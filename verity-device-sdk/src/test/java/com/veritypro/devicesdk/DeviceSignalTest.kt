package com.veritypro.devicesdk
import org.junit.Assert.*
import org.junit.Test
class DeviceSignalTest {
    @Test fun `unreadable process maps is unknown not clean`() { assertNull(fridaSignal(null)) }
    @Test fun `loaded frida library is detected`() { assertEquals(true, fridaSignal("1000-2000 r-xp /data/app/libfrida-gadget.so")) }
    @Test fun `ordinary process maps is negative`() { assertEquals(false, fridaSignal("1000-2000 r-xp /system/lib64/libc.so")) }
}
