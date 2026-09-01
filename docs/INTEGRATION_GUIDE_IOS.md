# VerityPro iOS SDK — Integration Guide

## Prerequisites

- iOS 15.0+, Xcode 15+
- Swift 5.9+
- Your **API Key** and **Integration ID** from the VerityPro dashboard (Settings → Integration)

---

## 1. Installation

### Add the XCFramework

1. Build the framework: `bash build_xcframework.sh` in the `verity-pro-ios` repo root.
2. Drag `VerityPro.xcframework` into your Xcode project.
3. In **Target → General → Frameworks, Libraries, and Embedded Content** set it to **Embed & Sign**.

### Info.plist permissions

```xml
<key>NSCameraUsageDescription</key>
<string>VerityPro needs camera access to capture your identity document.</string>

<key>NSMicrophoneUsageDescription</key>
<string>Required for liveness verification.</string>
```

---

## 2. KYC Verification

KYC verification handles document capture (passport, ID card, driver's licence), selfie, and liveness in a full-screen modal.

### Start a verification

```swift
import VerityPro
import UIKit

class CheckoutViewController: UIViewController {

    func startKyc() {
        let options = VerityOption(
            apiKey:        "your-api-key",
            integrationId: "your-integration-uuid",
            firstName:     "Jane",
            lastName:      "Smith",
            vendorData:    "internal-reference-123",  // optional, echoed back in result
            isO2Code:      "AU",                       // ISO-3166-1 alpha-2 country code
            dateOfBirth:   "1990-01-15",               // yyyy-MM-dd
            mode:          .biometric                  // see Verification modes below
        )

        let sdk = VerityProSDK(options: options, themeMode: .light)

        sdk.startVerification(from: self) { result in
            if result.success {
                let sessionId = result.sessionId
                // KYC passed — proceed
            } else {
                let error = result.error ?? "Verification failed"
                // Show error to user
            }
        }
    }
}
```

### Verification modes

| Mode | Description |
|---|---|
| `.biometric` | Document (front + back if required) + selfie + liveness |
| `.document` | Document only, no liveness |
| `.livenessOnly` | Liveness only, no document capture |
| `.address` | Address document verification |
| `.edd` | AUSTRAC Enhanced Due Diligence document submission |
| `.combined` | Multi-step sequential flow (biometric + address + EDD) |
| `.serverDriven` | Server creates the session; SDK renders it |

### Result fields (`LivenessResult`)

| Field | Type | Description |
|---|---|---|
| `success` | `Bool` | `true` when KYC passed |
| `sessionId` | `String?` | Backend session token |
| `sessionToken` | `String?` | AWS liveness session token (if applicable) |
| `confidence` | `Float?` | Liveness confidence score (0–1) |
| `error` | `String?` | Human-readable error when `success = false` |
| `eddCaseId` | `String?` | Present when EDD upload succeeded |

---

## 3. Device Fingerprinting

Device fingerprinting is **separate from KYC**. Call it at transaction time (send money, login from new device, payout change) and include the token in your transaction payload.

### Basic usage (standalone `VerityDevice` API)

```swift
import VerityPro

func onUserTapsSend() async {
    // Collect device signals and mint a vpds_* token (~1–3s)
    let deviceToken = await VerityDevice.collect(
        apiKey:        "your-api-key",
        integrationId: "your-integration-uuid"
        // baseUrl defaults to https://api.skylinefare.com
    )

    await submitTransaction(
        amount:      500.00,
        currency:    "AUD",
        recipient:   recipientId,
        deviceToken: deviceToken   // nil = token unavailable, still submit
    )
}
```

### Direct `VpDeviceSessionService` (advanced)

If you need more control (e.g. running on a background task alongside other work):

```swift
import VerityPro

let token = await VpDeviceSessionService.collectAndSubmit(
    apiKey:        "your-api-key",
    baseUrl:       "https://api.skylinefare.com",
    integrationId: "your-integration-uuid"
)
```

### What signals are collected

The SDK collects device model (via `utsname()`), OS, screen, battery (`UIDevice`), disk / RAM via `ProcessInfo`, VPN detection via `CFNetworkCopySystemProxySettings`, jailbreak detection (5 independent methods: URL schemes, 7 suspicious file paths, sandbox write test, fork-proxy test, dylib scan), and a stable visitor ID stored in `UserDefaults` under `_vp_vid`. All signals are sent to VerityPro.

### Rules

- **Never block the transaction** if `deviceToken` is nil.
- **Call fresh each time** — tokens are short-lived and single-use.
- **Never call this during KYC** — it is only for transaction / session flows.
- **Never log or store the token**.

---

## 4. Combined transaction flow

```swift
func onUserTapsSend() async {
    // 1. Collect device token (best-effort)
    let deviceToken = await VerityDevice.collect(
        apiKey:        "your-api-key",
        integrationId: "your-integration-uuid"
    )

    // 2. Submit to your backend
    let response = try await yourApiClient.submitTransaction(
        amount:             amount,
        currency:           currency,
        recipient:          recipient,
        deviceSessionToken: deviceToken
    )

    // 3. Your backend forwarded deviceSessionToken to VerityPro
    //    and received PASS / REVIEW / DECLINE
    switch response.decision {
    case "PASS":    showSuccessScreen()
    case "REVIEW":  showReviewPendingScreen()
    case "DECLINE": showDeclineScreen(reason: response.reason)
    default:        break
    }
}
```

---

## 5. Error handling

`VpDeviceSessionService.collectAndSubmit()` returns `nil` on any error — it never throws. `VerityProSDK.startVerification` delivers errors via the `LivenessResult.success = false` + `LivenessResult.error` path.

Common `error` values:

| Error message | Cause |
|---|---|
| `"User cancelled"` | User dismissed the flow |
| `"Verification cannot proceed on a compromised device..."` | Jailbreak detected |
| `"missing_options"` | `VerityOption` was nil when passed to the activity |

---

## 6. Liveness check (AWS Rekognition)

The liveness stage uses AWS Amplify + Rekognition. No Amplify configuration is required in your app — the SDK handles it internally using the credentials obtained from VerityPro's backend during the KYC session.

---

## 7. Theme modes

```swift
VerityProSDK(options: options, themeMode: .light)   // forces light
VerityProSDK(options: options, themeMode: .dark)    // forces dark
VerityProSDK(options: options, themeMode: .system)  // follows system setting
```
