package com.example.veritypro_sdk.utils

import com.example.veritypro_sdk.services.SdkBranding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrandingResolverTest {

    @Test
    fun `dashboard branding fills what the app left unset`() {
        val merged = BrandingResolver.merge(
            app = null,
            server = SdkBranding(logoPrimaryUrl = "https://cdn.example.org/logo.png", primaryColor = "#0B5FFF"),
        )
        assertEquals("https://cdn.example.org/logo.png", merged?.logoUrl)
        assertEquals("#0B5FFF", merged?.primaryColor)
    }

    @Test
    fun `app config wins per field over the dashboard`() {
        val merged = BrandingResolver.merge(
            app = VpBrandConfig(primaryColor = "#111111", logoUrl = null),
            server = SdkBranding(logoPrimaryUrl = "https://cdn.example.org/logo.png", primaryColor = "#0B5FFF"),
        )
        assertEquals("#111111", merged?.primaryColor)
        assertEquals("https://cdn.example.org/logo.png", merged?.logoUrl)
    }

    @Test
    fun `secondary logo is used when the primary is blank`() {
        val merged = BrandingResolver.merge(
            app = null,
            server = SdkBranding(logoPrimaryUrl = " ", logoSecondaryUrl = "https://cdn.example.org/alt.png"),
        )
        assertEquals("https://cdn.example.org/alt.png", merged?.logoUrl)
        assertNull(merged?.primaryColor)
    }

    @Test
    fun `nothing configured anywhere yields null so the SDK default look applies`() {
        assertNull(BrandingResolver.merge(app = null, server = null))
        assertNull(BrandingResolver.merge(app = VpBrandConfig(), server = SdkBranding(primaryColor = "")))
    }

    @Test
    fun `app config is returned unchanged when the dashboard has nothing`() {
        val app = VpBrandConfig(primaryColor = "#222222", logoUrl = "https://app.example.org/logo.png")
        assertEquals(app, BrandingResolver.merge(app, null))
    }
}
