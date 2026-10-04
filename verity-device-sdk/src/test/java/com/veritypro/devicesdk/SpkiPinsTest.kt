package com.veritypro.devicesdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

class SpkiPinsTest {
    // JVM unit tests stub android.*, so pass java.util.Base64 (the production default is android.util.Base64).
    private val b64: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }

    // ISRG Root X1 as published at https://letsencrypt.org/certs/isrgrootx1.pem (public CA certificate).
    private val isrgRootX1: X509Certificate =
        javaClass.classLoader!!.getResourceAsStream("isrgrootx1.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    @Test
    fun `spki pin of ISRG Root X1 is the pinned X1 hash`() {
        assertEquals("sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", SpkiPins.spkiPin(isrgRootX1, b64))
    }

    @Test
    fun `a chain containing a pinned root key passes`() {
        assertTrue(SpkiPins.anyPinned(listOf(isrgRootX1), b64 = b64))
    }

    @Test
    fun `a chain with no pinned key fails`() {
        val otherPins = setOf("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        assertFalse(SpkiPins.anyPinned(listOf(isrgRootX1), otherPins, b64))
        assertFalse(SpkiPins.anyPinned(emptyList(), b64 = b64))
    }

    @Test
    fun `device sdk pins match the veritypro-sdk pin set`() {
        assertEquals(3, SpkiPins.ISRG_ROOT_PINS.size)
        assertTrue("sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=" in SpkiPins.ISRG_ROOT_PINS) // Root YE
        assertTrue("sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=" in SpkiPins.ISRG_ROOT_PINS) // Root X2
    }
}
