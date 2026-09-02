# VerityPro Android SDK — Integration Guide

## Prerequisites

- Android minSdk 24, compileSdk 34
- Kotlin 1.9+, Compose UI 1.6+
- Your **API Key** and **Integration ID** from the VerityPro dashboard (Settings → Integration)

---

## 1. Installation

### Add the AAR dependency

Copy `veritypro-sdk-release.aar` (provided by VerityPro) into your `app/libs/` folder.

In your module-level `build.gradle`:

```groovy
android {
    defaultConfig { minSdk 24 }
    buildFeatures { compose true }
    composeOptions { kotlinCompilerExtensionVersion '1.5.3' }
}

dependencies {
    implementation fileTree(dir: 'libs', include: ['*.aar'])

    // Required transitive dependencies
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

### Manifest permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

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

## 2. KYC Verification (v2 — Compose entry point)

The v2 API is a Composable that hosts the full state-driven verification flow. Host it from any Compose screen using a `Dialog`, `ModalBottomSheet`, or full-screen navigation destination.

### Basic usage

```kotlin
import androidx.compose.runtime.*
import com.example.veritypro_sdk.ui.redesign.VerityVerification
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocOption
import com.example.veritypro_sdk.ui.redesign.state.VerityFlowState
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.utils.VerityOption

@Composable
fun CheckoutScreen() {
    var showVerification by remember { mutableStateOf(false) }

    Button(onClick = { showVerification = true }) {
        Text("Start Verification")
    }

    if (showVerification) {
        VerityVerification(
            documentOptions = listOf(
                VerityDocOption(id = "passport",   label = "Passport"),
                VerityDocOption(id = "id_card",    label = "ID Card"),
                VerityDocOption(id = "drivers_license", label = "Driver's Licence")
            ),
            options = VerityOption(
                apiKey        = "your-api-key",
                integrationId = "your-integration-uuid",
                firstName     = "Jane",
                lastName      = "Smith",
                vendorData    = "internal-ref-123",   // optional, echoed back
                isO2Code      = "AU",                  // ISO 3166-1 alpha-2
                dateOfBirth   = "1990-01-15"           // yyyy-MM-dd
            ),
            themeMode = ThemeMode.SYSTEM,
            onResult = { state ->
                showVerification = false
                when (state) {
                    VerityFlowState.Approved            -> println("KYC passed")
                    VerityFlowState.PendingManualReview -> println("Under manual review")
                    VerityFlowState.Rejected            -> println("KYC rejected")
                    VerityFlowState.Cancelled,
                    VerityFlowState.Failed              -> println("User cancelled or flow failed")
                    else -> Unit
                }
            }
        )
    }
}
```

### Terminal states (`VerityFlowState`)

`onResult` fires exactly once when the flow reaches a terminal state.

| State | Meaning |
|---|---|
| `Approved` | Document + liveness passed — proceed |
| `PendingManualReview` | Queued for manual review — poll backend |
| `Rejected` | Hard decline |
| `Cancelled` | User dismissed the flow |
| `Failed` | Unrecoverable error (session creation failed, integrity block, etc.) |

### `VerityVerification` parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `documentOptions` | `List<VerityDocOption>` | ✓ | Document types to offer the user |
| `options` | `VerityOption?` | — | API credentials and applicant data. `null` = demo/stub mode (no API calls) |
| `onResult` | `(VerityFlowState) -> Unit` | ✓ | Terminal state callback |
| `themeMode` | `ThemeMode` | — | `LIGHT`, `DARK`, or `SYSTEM` (default) |
| `analytics` | `VerityAnalytics` | — | Analytics sink; defaults to `LogcatVerityAnalytics` |
| `brandConfig` | `VpBrandConfig?` | — | Optional brand overrides |

### `VerityDocOption` fields

| Field | Type | Description |
|---|---|---|
| `id` | `String` | Machine-readable key passed to the backend |
| `label` | `String` | Human-readable label shown in the document selector |

### `VerityOption` parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `apiKey` | `String` | ✓ | Integration API key from the dashboard |
| `integrationId` | `String` | ✓ | Integration UUID from the dashboard |
| `firstName` | `String` | ✓ | Applicant first name |
| `lastName` | `String` | ✓ | Applicant last name |
| `isO2Code` | `String` | ✓ | Country ISO 3166-1 alpha-2 (e.g. `"AU"`) |
| `dateOfBirth` | `String` | ✓ | `yyyy-MM-dd` format |
| `vendorData` | `String?` | — | Opaque reference echoed in result |
| `preCreatedSessionId` | `String?` | — | Skip session creation when session was pre-created server-side |
| `allowedDocumentTypes` | `List<String>?` | — | Restricts the document selector to these types (overrides `documentOptions`) |

### Brand customisation (`VpBrandConfig`)

Override the primary brand colour and logo for white-label deployments:

```kotlin
import com.example.veritypro_sdk.utils.VpBrandConfig

VerityVerification(
    documentOptions = documentOptions,
    options = options,
    brandConfig = VpBrandConfig(
        primaryColor = "#FF5500",   // hex string; null = SDK default
        logoUrl = null              // optional remote logo URL
    ),
    onResult = { state -> ... }
)
```

---

## 3. Device Fingerprinting

Device fingerprinting is **separate from KYC**. Call it at transaction time (send money, new-device login, payout change) and include the token in your transaction payload.

### Basic usage

```kotlin
import com.example.veritypro_sdk.utils.VpDeviceSessionService
import kotlinx.coroutines.launch

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

```kotlin
val visitorId = VpDeviceSessionService.getOrCreateVisitorId(context)
```

### What signals are collected

Device model, OS, screen, timezone, language, battery level, jailbreak/root detection (9 su-binary paths), emulator detection (28 checks), Frida injection detection, and a stable visitor ID (`SharedPreferences`). All signals are sent to VerityPro — you never see them.

### Rules

- **Never block the transaction** if `deviceToken` is null.
- **Call fresh each time** — tokens are short-lived and single-use.
- **Do not call during KYC** — only for transaction / session flows.
- **Do not log or store the token**.

---

## 4. Combined transaction flow

```kotlin
fun onUserTapsSend() {
    lifecycleScope.launch {
        val deviceToken = VpDeviceSessionService.collectAndSubmit(
            context       = applicationContext,
            apiKey        = "your-api-key",
            integrationId = "your-integration-uuid"
        )

        val response = yourApiClient.submitTransaction(
            TransactionRequest(
                amount             = amount,
                currency           = currency,
                recipient          = recipient,
                deviceSessionToken = deviceToken
            )
        )

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

`VpDeviceSessionService.collectAndSubmit()` returns `null` on any error — it never throws.

`VerityVerification` delivers errors via `onResult` with `VerityFlowState.Failed` or `VerityFlowState.Cancelled`. Common root causes:

| `VerityFlowState` | Cause |
|---|---|
| `Failed` | Session creation failed (bad API key, network error) or device integrity block |
| `Cancelled` | User tapped Back / dismissed the flow |
| `Rejected` | Document or liveness failed decisioning |

---

## 6. ProGuard / R8

Add to your `proguard-rules.pro`:

```
-keep class com.example.veritypro_sdk.** { *; }
-keep class com.example.veritypro_sdk.utils.VerityOption { *; }
-keep class com.example.veritypro_sdk.utils.VpBrandConfig { *; }
-keep class com.example.veritypro_sdk.ui.redesign.state.VerityFlowState { *; }
-keep class com.example.veritypro_sdk.ui.redesign.screens.VerityDocOption { *; }
```

---

## 7. Demo / stub mode

Pass `options = null` to `VerityVerification` to run the full flow without making any backend API calls. All screens render with real UI; document capture and liveness are skipped. Useful for UI testing and design review.

```kotlin
VerityVerification(
    documentOptions = listOf(VerityDocOption("passport", "Passport")),
    options = null,   // stub mode — no API calls
    onResult = { state -> println("Demo result: $state") }
)
```
