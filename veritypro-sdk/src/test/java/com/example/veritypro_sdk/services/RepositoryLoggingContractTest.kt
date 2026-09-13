package com.example.veritypro_sdk.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryLoggingContractTest {
    @Test fun `repository logs never interpolate response bodies or parsed error messages`() {
        val source = File("src/main/java/com/example/veritypro_sdk/services/repository.kt")
        val unsafe = source.readLines().filter { line ->
            line.contains("Log.") && listOf(
                "\$errorBody", "\$body", "\$errorMessage", "\$userMessage", "\$lastError", "\$response",
                "\${response.statusMessage}", "\${response.error", "\${result.message}",
            ).any { line.contains(it) }
        }
        assertTrue("HTTP error payloads must not enter repository logs: $unsafe", unsafe.isEmpty())
    }
}
