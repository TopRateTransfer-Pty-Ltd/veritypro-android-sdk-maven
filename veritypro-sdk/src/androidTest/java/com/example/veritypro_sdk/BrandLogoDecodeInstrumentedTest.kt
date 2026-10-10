package com.example.veritypro_sdk

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.veritypro_sdk.ui.theme.BrandLogoLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Decodes logos on a real Android runtime. The asset has the shape of the dashboard uploads seen
 * on staging (XML prolog, DOCTYPE, width/height "100%", viewBox only).
 *
 * Optional live check against a real logo URL:
 *   ./gradlew :veritypro-sdk:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.logoUrl=https://...
 */
@RunWith(AndroidJUnit4::class)
class BrandLogoDecodeInstrumentedTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val svgBytes = InstrumentationRegistry.getInstrumentation().context.assets
        .open("brand_logo_percent_size.svg").use { it.readBytes() }

    private fun request(data: Any) = ImageRequest.Builder(appContext).data(data).build()

    @Test
    fun defaultLoaderCannotDecodeSvg_theOriginalDefect() = runBlocking {
        val result = ImageLoader(appContext).execute(request(ByteBuffer.wrap(svgBytes)))
        assertTrue("Coil's default loader unexpectedly decoded SVG: $result", result is ErrorResult)
    }

    @Test
    fun brandLogoLoaderDecodesDashboardShapedSvg() = runBlocking {
        val result = BrandLogoLoader.imageLoader(appContext).execute(request(ByteBuffer.wrap(svgBytes)))
        assertTrue("SVG logo did not decode: $result", result is SuccessResult)
        val drawable = (result as SuccessResult).drawable
        assertTrue("decoded SVG has no size", drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
    }

    @Test
    fun brandLogoLoaderStillDecodesRasterLogos() = runBlocking {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val result = BrandLogoLoader.imageLoader(appContext).execute(request(ByteBuffer.wrap(png)))
        assertTrue("PNG logo did not decode: $result", result is SuccessResult)
    }

    @Test
    fun liveLogoUrlDecodes_whenProvided() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("logoUrl")
        assumeTrue("no logoUrl instrumentation argument", !url.isNullOrBlank())
        val loader = BrandLogoLoader.imageLoader(appContext)
        val result = loader.execute(request(url!!))
        assertTrue("live logo did not decode: $result", result is SuccessResult)
    }
}
