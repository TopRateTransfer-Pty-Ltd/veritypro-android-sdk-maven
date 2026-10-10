package com.example.veritypro_sdk.ui.theme

import coil.decode.SvgDecoder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dashboard logos are SVG (staging, 2026-10-10: the only integration with a dashboard logo serves
 * two `image/svg+xml` files). Coil's default loader cannot decode SVG, so the welcome hero painted
 * nothing while the branding endpoint answered 200. These tests pin the two things that fixed it.
 */
class BrandLogoLoaderTest {

    @Test
    fun `brand logo loader registers the SVG decoder`() {
        val factories = BrandLogoLoader.components().decoderFactories
        assertTrue("SvgDecoder.Factory missing from $factories", factories.any { it is SvgDecoder.Factory })
    }

    @Test
    fun `every AsyncImage in shipped sources uses the brand logo loader`() {
        val root = File("src/main").canonicalFile
        assertTrue("missing source root $root", root.isDirectory)
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            Regex("""\bAsyncImage\(""").findAll(text)
                // Skip KDoc / comment mentions.
                .filterNot { m ->
                    val lineStart = text.lastIndexOf('\n', m.range.first) + 1
                    text.substring(lineStart, m.range.first).trimStart().let { it.startsWith("*") || it.startsWith("//") }
                }
                // The call's arguments: the next 600 chars cover any realistic AsyncImage call.
                .filterNot { m -> text.substring(m.range.first, minOf(text.length, m.range.first + 600)).contains("BrandLogoLoader.imageLoader(") }
                .map { m -> "${file.relativeTo(root)}: offset ${m.range.first}" }
        }.toList()
        assertTrue(
            "AsyncImage without BrandLogoLoader cannot decode SVG logos:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
