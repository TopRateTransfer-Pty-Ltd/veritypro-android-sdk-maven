# VerityPro iOS SDK — Integration Guide

## Prerequisites

- iOS 15.0+, Xcode 16+
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

<key>NSLocationWhenInUseUsageDescription</key>
<string>Location is collected for fraud-risk assessment during verification.</string>
```

---

## 2. KYC Verification (v2 — SwiftUI entry point)

The v2 API is a SwiftUI `View` driven by a state machine. Host it from any SwiftUI context using a `fullScreenCover` or navigation push.

### Basic usage

```swift
import SwiftUI
import VerityPro

struct ContentView: View {
    @State private var showVerification = false

    var body: some View {
        Button("Start Verification") { showVerification = true }
            .fullScreenCover(isPresented: $showVerification) {
                VerityVerificationView(
                    options: VerityOption(
                        apiKey:        "your-api-key",
                        integrationId: "your-integration-uuid",
                        firstName:     "Jane",
                        lastName:      "Smith",
                        vendorData:    "internal-ref-123",   // optional, echoed back
                        isO2Code:      "AU",                  // ISO 3166-1 alpha-2
                        dateOfBirth:   "1990-01-15"           // yyyy-MM-dd
                    ),
                    themeMode: .system
                ) { state, sessionId in
                    showVerification = false
                    switch state {
                    case .approved:
                        print("KYC passed — session: \(sessionId ?? "")")
                    case .pendingManualReview:
                        print("Under manual review — session: \(sessionId ?? "")")
                    case .rejected:
                        print("KYC rejected")
                    case .cancelled, .failed:
                        print("User cancelled or flow failed")
                    default:
                        break
                    }
                }
            }
    }
}
```

### Terminal states (`VerityFlowState`)

`onResult` fires exactly once when the flow reaches a terminal state.

| State | Meaning |
|---|---|
| `.approved` | Document + liveness passed — proceed |
| `.pendingManualReview` | Queued for manual review — poll backend |
| `.rejected` | Hard decline |
| `.cancelled` | User dismissed the flow |
| `.failed` | Unrecoverable error (session creation failed, integrity block, etc.) |

The `sessionId` parameter is the backend KYC session token. It is non-nil on `.approved`, `.pendingManualReview`, `.rejected`, and `.failed`.

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
| `mode` | `VerityMode` | — | See Verification modes below (default `.biometric`) |
| `preCreatedSessionId` | `String?` | — | Skip session creation when session was pre-created server-side |

### Verification modes (`VerityMode`)

| Mode | Description |
|---|---|
| `.biometric` | Document (front + back) + selfie + liveness |
| `.document` | Document capture only, no selfie |
| `.livenessOnly` | Selfie + liveness only (returning users) |
| `.address` | Address document upload |
| `.edd` | Enhanced Due Diligence document upload |
| `.combined` | Sequential: biometric → address → EDD |
| `.serverDriven` | Server controls step order via session response |

### Brand customisation (`VpBrandConfig`)

Override the primary brand colour and logo for white-label deployments:

```swift
VerityVerificationView(
    options: options,
    themeMode: .system
) { state, sessionId in ... }
```

Pass `brandConfig` to `VerityOption` (or update `VerityOption` with a `brandConfig` field if wiring is complete):

```swift
let brandConfig = VpBrandConfig(
    primaryColor: "#FF5500",   // hex string; nil = SDK default
    logoUrl: nil               // optional remote logo URL
)
```

### Theme modes

| Value | Behaviour |
|---|---|
| `.light` | Forces light mode |
| `.dark` | Forces dark mode |
| `.system` | Follows device setting (default) |

---

## 3. Device Fingerprinting

Device fingerprinting is **separate from KYC**. Call it at transaction time (send money, new-device login, payout change) and include the token in your transaction payload.

### Basic usage

```swift
import VerityPro

func onUserTapsSend() async {
    let deviceToken = await VpDeviceSessionService.collectAndSubmit(
        apiKey:        "your-api-key",
        baseUrl:       "https://api.skylinefare.com",
        integrationId: "your-integration-uuid"
    )

    await submitTransaction(
        amount:      500.00,
        currency:    "AUD",
        recipient:   recipientId,
        deviceToken: deviceToken   // nil = collection failed, still submit
    )
}
```

### What signals are collected

Device model, OS, screen, battery, disk/RAM (`ProcessInfo`), VPN detection, jailbreak detection (5 independent methods — URL schemes, suspicious file paths, sandbox write, fork-proxy, dylib scan), and a stable visitor ID (`_vp_vid` in `UserDefaults`). All signals are sent to VerityPro — you never see them.

### Rules

- **Never block the transaction** if `deviceToken` is nil.
- **Call fresh each time** — tokens are short-lived and single-use.
- **Do not call during KYC** — only for transaction / session flows.
- **Do not log or store the token**.

---

## 4. Combined transaction flow

```swift
func onUserTapsSend() async {
    let deviceToken = await VpDeviceSessionService.collectAndSubmit(
        apiKey:        "your-api-key",
        baseUrl:       "https://api.skylinefare.com",
        integrationId: "your-integration-uuid"
    )

    let response = try await yourApiClient.submitTransaction(
        amount:             amount,
        currency:           currency,
        recipient:          recipient,
        deviceSessionToken: deviceToken
    )

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

`VpDeviceSessionService.collectAndSubmit()` returns `nil` on any error — it never throws.

`VerityVerificationView` delivers errors via `onResult` with state `.failed` (unrecoverable) or `.cancelled` (user dismissed). Common root causes:

| `VerityFlowState` | Cause |
|---|---|
| `.failed` | Session creation failed (bad API key, network error) or device integrity block |
| `.cancelled` | User tapped Back / dismissed the flow |
| `.rejected` | Document or liveness failed decisioning |

---

## 6. Liveness check (AWS Rekognition)

The liveness stage uses AWS Amplify + Rekognition. No Amplify configuration is required in your app — the SDK obtains credentials from VerityPro's backend during the KYC session and passes them directly to `FaceLivenessDetectorView`.

---

## 7. ProGuard / R8

Not applicable for iOS. The XCFramework ships with `BUILD_LIBRARY_FOR_DISTRIBUTION = YES` — no additional symbol preservation is needed.
