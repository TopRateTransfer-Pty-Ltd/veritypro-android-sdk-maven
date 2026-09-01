# VerityPro Android SDK — Integration Guide

## Prerequisites

- Android minSdk 24, compileSdk 34
- Kotlin 1.9+
- Your **API Key** and **Integration ID** from the VerityPro dashboard (Settings → Integration)

---

## 1. Installation

### Add the AAR dependency

Copy the `veritypro-sdk-release.aar` file (provided by VerityPro) into your `app/libs/` folder.

In your module-level `build.gradle`:

```groovy
android {
    defaultConfig {
        minSdk 24
    }
}

dependencies {
    implementation fileTree(dir: 'libs', include: ['*.aar'])

    // Required transitive dependencies
    implementation 'androidx.activity:activity-compose:1.8.2'
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'com.amplifyframework:aws-auth-cognito:2.14.0'
}
```

### Manifest permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />

<uses-feature android:name="android.hardware.camera" android:required="true" />
```

Register the SDK activity inside `<application>`:

```xml
<activity
    android:name="com.example.veritypro_sdk.VerityProSdkActivity"
    android:exported="false"
    android:screenOrientation="portrait" />
```

---

## 2. KYC Verification

KYC verification covers document capture (passport, ID card, driver's licence), selfie, and liveness check.

### Start a verification

```kotlin
import com.example.veritypro_sdk.VerityProSdkActivity
import com.example.veritypro_sdk.utils.VerityOption
import com.example.veritypro_sdk.utils.LivenessResult
import android.content.Intent

class CheckoutActivity : AppCompatActivity() {

    private val verifyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val liveness: LivenessResult? = result.data?.getParcelableExtra("verification_result")
            if (liveness?.success == true) {
                // KYC passed — proceed
                val sessionId = liveness.sessionId
            } else {
                // KYC failed or user cancelled
                val error = liveness?.error
            }
        }
    }

    fun startKyc() {
        val options = VerityOption(
            apiKey        = "your-api-key",
            integrationId = "your-integration-uuid",
            firstName     = "Jane",
            lastName      = "Smith",
            vendorData    = "internal-reference-123",   // optional, echoed back in result
            isO2Code      = "AU",                       // ISO-3166-1 alpha-2 country code
            dateOfBirth   = "1990-01-15",               // yyyy-MM-dd
        )

        val intent = Intent(this, VerityProSdkActivity::class.java).apply {
            putExtra("verity_options", options)
            putExtra("theme_mode", "LIGHT")  // "LIGHT" | "DARK" | "SYSTEM"
        }
        verifyLauncher.launch(intent)
    }
}
```

### Verification modes

| Mode constant | Description |
|---|---|
| `"LIGHT"` / `"DARK"` / `"SYSTEM"` | Theme passed as `theme_mode` extra |

The SDK automatically handles document type selection, front/back capture, selfie, and liveness. You only receive the final result.

### Result fields (`LivenessResult`)

| Field | Type | Description |
|---|---|---|
| `success` | `Boolean` | `true` when KYC passed |
| `sessionId` | `String?` | Backend session token |
| `error` | `String?` | Human-readable error message when `success = false` |
| `eddCaseId` | `String?` | Present when EDD document upload succeeded |

---

## 3. Device Fingerprinting

Device fingerprinting is **separate from KYC**. Call it at transaction time (send money, login from new device, payout change) and include the token in your transaction payload.

### Basic usage

```kotlin
import com.example.veritypro_sdk.utils.VpDeviceSessionService
import kotlinx.coroutines.launch

// In a CoroutineScope (e.g. viewModelScope or lifecycleScope)
fun onUserTapsSend() {
    lifecycleScope.launch {
        val deviceToken = VpDeviceSessionService.collectAndSubmit(
            context       = applicationContext,
            apiKey        = "your-api-key",
            integrationId = "your-integration-uuid"
            // baseUrl defaults to https://api.skylinefare.com
        )

        submitTransaction(
            amount      = 500.00,
            currency    = "AUD",
            recipient   = recipientId,
            deviceToken = deviceToken   // null = token unavailable, still submit
        )
    }
}
```

### Visitor ID (stable cross-session identifier)

The SDK automatically generates and persists a stable visitor ID in `SharedPreferences`. You can read it independently if needed:

```kotlin
val visitorId = VpDeviceSessionService.getOrCreateVisitorId(context)
```

### What signals are collected

The SDK collects device model, OS, screen, timezone, language, battery level, jailbreak / root detection (9 su-binary paths), emulator detection (28 checks), Frida injection detection, and a stable visitor ID. All signals are sent to VerityPro — you never see them.

### Rules

- **Never block the transaction** if `deviceToken` is null. Network or device issues may prevent collection.
- **Do not reuse tokens** across transactions. Call `collectAndSubmit` fresh each time.
- **Do not call this during KYC**. It is only for transaction / session flows.
- **Do not log or store the token**. Treat it as an opaque session identifier.

---

## 4. Combined transaction flow

```kotlin
fun onUserTapsSend() {
    lifecycleScope.launch {
        // 1. Collect device token (best-effort, ~1–3s)
        val deviceToken = VpDeviceSessionService.collectAndSubmit(
            context       = applicationContext,
            apiKey        = "your-api-key",
            integrationId = "your-integration-uuid"
        )

        // 2. Submit transaction to your backend with the token
        val response = yourApiClient.submitTransaction(
            TransactionRequest(
                amount           = amount,
                currency         = currency,
                recipient        = recipient,
                deviceSessionToken = deviceToken
            )
        )

        // 3. Your backend forwards deviceSessionToken to VerityPro and
        //    receives PASS / REVIEW / DECLINE to act on.
        when (response.decision) {
            "PASS"    -> showSuccessScreen()
            "REVIEW"  -> showReviewPendingScreen()
            "DECLINE" -> showDeclineScreen(response.reason)
        }
    }
}
```

---

## 5. Error handling

| Error code | Cause |
|---|---|
| `MISSING_PARAM` | `apiKey` or `integrationId` was empty |
| `NO_ACTIVITY` | Activity not attached when SDK was called |
| `IN_PROGRESS` | Another verification is already running |

For device fingerprinting, `collectAndSubmit` returns `null` on any error — it never throws. Log it but don't surface it to the user.

---

## 6. ProGuard / R8

Add to your `proguard-rules.pro`:

```
-keep class com.example.veritypro_sdk.** { *; }
-keep class com.example.veritypro_sdk.utils.LivenessResult { *; }
-keep class com.example.veritypro_sdk.utils.VerityOption { *; }
```

---

## 7. Sample `build.gradle` (complete)

```groovy
android {
    compileSdk 34

    defaultConfig {
        minSdk 24
        targetSdk 34
    }

    buildFeatures {
        compose true
    }

    composeOptions {
        kotlinCompilerExtensionVersion '1.5.3'
    }
}

dependencies {
    implementation fileTree(dir: 'libs', include: ['*.aar'])

    implementation 'androidx.activity:activity-compose:1.8.2'
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'androidx.compose.ui:ui:1.6.0'
    implementation 'androidx.compose.material3:material3:1.2.0'
    implementation 'androidx.camera:camera-core:1.3.1'
    implementation 'androidx.camera:camera-camera2:1.3.1'
    implementation 'androidx.camera:camera-lifecycle:1.3.1'
    implementation 'androidx.camera:camera-view:1.3.1'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'com.amplifyframework:aws-auth-cognito:2.14.0'
}
```
