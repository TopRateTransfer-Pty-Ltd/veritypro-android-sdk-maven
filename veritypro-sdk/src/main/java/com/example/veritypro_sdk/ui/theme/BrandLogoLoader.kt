package com.example.veritypro_sdk.ui.theme

import android.content.Context
import coil.ComponentRegistry
import coil.ImageLoader
import coil.decode.SvgDecoder

/**
 * The image loader every brand-logo render goes through.
 *
 * Dashboard logos are uploaded as SVG: on staging on 2026-10-10 the only integration with a
 * dashboard logo serves two `image/svg+xml` files. Coil's default loader has no SVG decoder, so
 * `AsyncImage(model = url)` fetched the file, failed to decode it and painted nothing — the logo
 * was simply absent while `/kycintegration/branding/sdk` answered 200. Rasters (PNG/JPEG/WebP)
 * still go through Coil's built-in decoders; [SvgDecoder.Factory] only claims SVG data (by MIME
 * type or by sniffing the bytes).
 */
internal object BrandLogoLoader {
    @Volatile private var loader: ImageLoader? = null

    /** Components registered on the brand-logo loader; exposed so a unit test can pin SVG support. */
    internal fun components(): ComponentRegistry = ComponentRegistry.Builder()
        .add(SvgDecoder.Factory())
        .build()

    fun imageLoader(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .components(components())
                .build()
                .also { loader = it }
        }
}
