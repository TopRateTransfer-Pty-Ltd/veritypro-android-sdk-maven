package com.example.veritypro_sdk.utils

/**
 * The SDK version the backend sees in every payload (`sdkVersion` in the security assessment,
 * `sdk_version` in the device session). One constant, so the two never drift.
 *
 * This is the PAYLOAD CONTRACT version, not the Maven artifact version (`veritypro-sdk/build.gradle`
 * `publishing.version`, DEF-4). The backend keys minimum-version gates on this string
 * (e.g. the DocAI session gate: "android-2.0.0" or newer), so bump it only when the payload
 * contract changes; bump the Maven version on every publish.
 */
object VeritySdkVersion {
    const val PAYLOAD = "android-2.1.0"
}
