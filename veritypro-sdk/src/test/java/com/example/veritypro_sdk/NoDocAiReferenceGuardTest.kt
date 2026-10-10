package com.example.veritypro_sdk

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DocAI is retired from the SDK (CONTRACT.md section 1: "No SDK code path may call the DocAI paths or any
 * inference endpoint"). This fails the build if a DocAI path, client or on-device model dependency
 * comes back into a shipped module — code, comments, resources or build scripts.
 */
class NoDocAiReferenceGuardTest {
    private val textTypes = setOf("kt", "java", "xml", "json", "pro", "properties", "txt", "gradle", "kts")
    private val forbidden = Regex(
        listOf(
            """/docai""", """docai/""", """MLRetrofitInstance""", """MLRepository""", """MLV2Repository""",
            """MLApiService""", """v[12]/kyc/doc/""", """capture-verify""", """verify-burst""", """pair-check""",
            """org\.tensorflow""", """tensorflow-lite""", """\.tflite""", """antiSpoofBurstScore""",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun `no DocAI client, endpoint or on-device model in shipped modules`() {
        val root = File("..").canonicalFile
        val scanned = listOf(
            "veritypro-sdk/src/main", "verity-device-sdk/src/main", "app/src/main",
            "veritypro-sdk/build.gradle", "veritypro-sdk/consumer-rules.pro", "veritypro-sdk/proguard-rules.pro",
        ).map { File(root, it) }
        scanned.forEach { assertTrue("missing $it", it.exists()) }
        val hits = scanned.flatMap { f ->
            f.walkTopDown().filter { it.isFile && it.extension in textTypes }.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" else null
                }
            }.toList()
        }
        assertTrue("DocAI reference(s) in shipped code:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
