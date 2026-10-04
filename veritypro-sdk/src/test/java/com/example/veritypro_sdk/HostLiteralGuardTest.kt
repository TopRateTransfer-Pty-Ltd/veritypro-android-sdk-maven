package com.example.veritypro_sdk

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every host comes from the integrator's configuration. A VerityPro host written into shipped
 * source is how the SDK used to send Live traffic to Sandbox (and the reverse), so this fails the
 * build if one appears anywhere in a library module's main sources — code, comments or resources.
 */
class HostLiteralGuardTest {
    private val textTypes = setOf("kt", "java", "xml", "json", "pro", "properties", "txt")
    private val forbidden = Regex("""skylinefare\.com|veritypro\.ai""", RegexOption.IGNORE_CASE)

    @Test
    fun `no VerityPro host literal in shipped sources`() {
        // Gradle runs unit tests with the module directory as the working directory.
        val root = File("..").canonicalFile
        val modules = listOf("veritypro-sdk/src/main", "verity-device-sdk/src/main").map { File(root, it) }
        modules.forEach { assertTrue("missing source root $it", it.isDirectory) }
        val hits = modules.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension in textTypes }.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" else null
                }
            }.toList()
        }
        assertTrue("Hardcoded VerityPro host(s) — take the host from apiBaseUrl instead:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
