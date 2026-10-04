# VerityPro Android SDK

Native Android SDK for VerityPro identity verification: document capture, biometric liveness,
address verification, Enhanced Due Diligence (EDD), biometric step-up, and a standalone device
collection module.

> Every statement in this README was checked against the source at `origin/android_fix`
> (de63766). File paths in brackets show where.

Modules in this repository:

| Module | Purpose |
|---|---|
| `veritypro-sdk` | The verification SDK (AAR). Package `com.example.veritypro_sdk`. |
| `verity-device-sdk` | Standalone device collection, no camera, Compose or ML dependencies. Package `com.veritypro.devicesdk`. |

## Breaking change: `apiBaseUrl` is required

The SDK has **no built-in host**. Every request goes to the API origin you pass, and that host is
the one certificate-pinned (ISRG Root YE / X2 / X1). Earlier versions silently defaulted to
**Sandbox**, so an app that omitted the origin could send a Live customer's documents to Sandbox.

| Environment | `apiBaseUrl` |
|---|---|
| Sandbox | `https://api.skylinefare.com` |
| Live | `https://api.veritypro.ai` |

The origin must be HTTPS and must not carry a path, query, fragment or credentials. A trailing `/`
is accepted and removed. [`veritypro-sdk/.../services/VerityEndpoint.kt`, `requireApiOrigin`]

No config, no call. A missing or malformed origin throws `IllegalArgumentException` **before any
request is sent**. The message names the setting, for example
`apiBaseUrl is required: pass the VerityPro API origin for your environment ...`.

| Entry point | Where it throws |
|---|---|
| `VerityPro.createVerificationIntent(...)` / `VerityPro(options).startVerification(...)` | When the intent is built, from `VerityOption.apiBaseUrl`. |
| `VerityPro.createStepUpIntent(..., apiBaseUrl = ...)` | When the intent is built. |
| `VerityEndpoint.requireApiOrigin(origin)` | Call it yourself at start-up to fail fast. |
| `VerityDevice.collect(...)` (`verity-device-sdk`) | Before any collection, from `baseUrl`. |
| `VpDeviceSessionService.collectAndSubmit(...)` (`veritypro-sdk`) | Before any collection, from `baseUrl`. |

Catch `IllegalArgumentException` where you launch the flow: it is a configuration error, not a
network error.

### Migrating from earlier versions

1. Set `apiBaseUrl` on `VerityOption`. Pass `apiBaseUrl` to `createStepUpIntent`. Pass `baseUrl`
   to `VerityDevice.collect` and `VpDeviceSessionService.collectAndSubmit`.
2. **Sandbox integrators:** pass `https://api.skylinefare.com`. Behaviour is unchanged.
3. **Live integrators:** pass `https://api.veritypro.ai`. Earlier versions defaulted to Sandbox,
   so a Live build that never set a host was talking to Sandbox. Check your Live build before
   release.
4. Read the origin from build configuration (a `buildConfigField` per flavor), not a literal in
   code, so a Sandbox build cannot ship to Live.
5. `docs/INTEGRATION_GUIDE_ANDROID.md` previously claimed device collection "defaults to
   production". That was wrong for this version; the guide is corrected in the same change.

## Requirements

- `minSdk 24`, `compileSdk`/`targetSdk` 36, Java 17 [`veritypro-sdk/build.gradle`]. The AAR uses
  core library desugaring: enable `coreLibraryDesugaring` in your app.
- The AAR manifest declares `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `ACCESS_FINE_LOCATION` and
  `ACCESS_COARSE_LOCATION`; they merge into your app. Request camera (and location, if you want
  location signals) at runtime as usual.
- An `integrationId` and API key from the VerityPro dashboard. Keep keys in your secret store;
  the examples below use placeholders.

## Installation

Group `com.example.veritypro`, artifact `veritypro-sdk`, version `1.4.0` (source:
`veritypro-sdk/build.gradle`). The publish target in the build is GitHub Packages:
`https://maven.pkg.github.com/TopRateTransfer-Pty-Ltd/veritypro-android-sdk-maven`, which needs a
GitHub token with `read:packages`. Whether 1.4.0 is currently published there was not verified.

```groovy
// settings.gradle (dependencyResolutionManagement)
maven {
    url = uri("https://maven.pkg.github.com/TopRateTransfer-Pty-Ltd/veritypro-android-sdk-maven")
    credentials {
        username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_USER")
        password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
    }
}

// app/build.gradle
dependencies {
    implementation "com.example.veritypro:veritypro-sdk:1.4.0"
}
```

Alternative: CI builds `veritypro-sdk-release.aar` and uploads it as a run artifact
(14 days, `.github/workflows/android-ci.yml`). Drop the AAR into `libs/` with
`implementation files("libs/veritypro-sdk-release.aar")`. An AAR does not carry its transitive
dependencies, so you must add them yourself (see `veritypro-sdk/build.gradle`: CameraX, Compose,
ML Kit, Amplify Face Liveness, OkHttp, Retrofit, TensorFlow Lite).

`verity-device-sdk` has no publishing configuration in this repository. Build it from source
(`./gradlew :verity-device-sdk:assembleRelease`) or include the module in your build.

## Quick start

```kotlin
import com.example.veritypro_sdk.VerityPro
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.utils.VerityMode
import com.example.veritypro_sdk.utils.VerityOption
import com.example.veritypro_sdk.utils.VerityOutcome

val options = VerityOption(
    apiKey = "<YOUR_API_KEY>",                      // placeholder: load from your secret store
    integrationId = "<YOUR_INTEGRATION_UUID>",
    firstName = "Jane",
    lastName = "Citizen",
    dateOfBirth = "1990-01-01",
    vendorData = "<your-customer-id>",              // stable customer reference
    isO2Code = "AU",                                // ISO-2 country code
    streetAddress = "1 Example Street",
    mode = VerityMode.BIOMETRIC.name,
    apiBaseUrl = "https://api.skylinefare.com",     // Sandbox. Live: https://api.veritypro.ai
)

// Activity Result API; in Compose use rememberLauncherForActivityResult.
val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val verity = VerityPro.extractResult(result) ?: return@registerForActivityResult
    when (verity.outcome) {
        VerityOutcome.APPROVED -> { /* verified */ }
        VerityOutcome.SUBMITTED -> { /* received; no decision yet */ }
        VerityOutcome.REJECTED -> { }
        VerityOutcome.PENDING_MANUAL_REVIEW -> { }
        VerityOutcome.CANCELLED -> { /* user closed the flow */ }
        VerityOutcome.FAILED -> verity.error?.let { Log.w("Verity", "${it.code}: ${it.message}") }
    }
}

try {
    VerityPro(options, ThemeMode.SYSTEM).startVerification(launcher, this)
} catch (e: IllegalArgumentException) {
    // Configuration error: missing or malformed apiBaseUrl, unsupported mode or module.
}
```

`VerityPro.createVerificationIntent(context, options, themeMode, operationId)` returns the
`Intent` if you prefer to launch it yourself. The flow runs in `VerityProSdkActivity`, which sets
`FLAG_SECURE` (no screenshots or screen recording) unless the host app is debuggable. Back press
cancels the flow. `VerityPro.cancelVerification(operationId)` cancels a running flow; the
operation id is the one you passed to `createVerificationIntent`, or the `operationId` on the result.

## Flows

You choose the flow with `VerityOption.mode`, a string holding a `VerityMode` name
[`veritypro-sdk/.../utils/verity_options.kt`, `ui/prototype/ProtoVerificationScreen.kt`]:

| `mode` | What the user does | Notes |
|---|---|---|
| `DOCUMENT` | ID document capture | No liveness. |
| `BIOMETRIC` | Document capture, then liveness selfie | Default. |
| `LIVENESS_ONLY` | Liveness selfie only | Pass `previousEngineSessionId` from an earlier document verification. |
| `ADDRESS` | Address entry and address document upload | |
| `EDD` | EDD income details and document upload | Optional `eddProfile`, `authToken`, `city`, `stateOrProvince`, `postalCode`, `country`, `subjectId`. |
| `COMBINED` | Document, liveness, address and EDD | |
| `SERVER_DRIVEN` | Module sequence comes from the backend `/v2/sessions` API | Pass `serverSessionId` to resume a session you created server-side. |
| `STEP_UP_AUTH` | Liveness against an enrolled template | Use `createStepUpIntent` (below). |

`requiredModules` accepts `DOCUMENT`, `BIOMETRIC`, `LIVENESS`, `LIVENESS_ONLY`, `ADDRESS`, `EDD`;
anything else throws `IllegalArgumentException("Unsupported verification module")`.
`optionalModules` must be empty. An unknown `mode` string throws
`IllegalArgumentException("Unsupported verification mode")` when the intent is built.

Other `VerityOption` fields: `allowedDocumentTypes`, `preCreatedSessionId`, `brandConfig`
(`VpBrandConfig` with `primaryColor` and `logoUrl`), `locale` (BCP-47), `signingKey`.
`placesApiKey` is deprecated and ignored.

### Step-up (biometric re-authentication)

Your server creates the challenge and, for production, mints a short-lived challenge-scoped
capability token so the permanent API key never ships in the app.

```kotlin
val intent = VerityPro.createStepUpIntent(
    context = this,
    challengeId = "<challenge-id-from-your-server>",
    capabilityToken = "<short-lived-token>",     // or apiKey = ... (development only)
    apiBaseUrl = "https://api.skylinefare.com",  // REQUIRED
)
stepUpLauncher.launch(intent)

// In the result callback:
when (val r = VerityPro.extractStepUpResult(result)) {
    is StepUpResult.Passed -> { }              // r.similarityScore, r.decisionId
    is StepUpResult.Failed -> { }              // r.reasonCodes, r.attemptCount, r.maxAttempts
    is StepUpResult.ManualReview -> { }
    is StepUpResult.NoEnrolledTemplate -> { }  // run full KYC first
    is StepUpResult.Locked -> { }              // r.lockedUntilEpochSeconds
    is StepUpResult.Expired -> { }             // challenge TTL elapsed; create a new one
    is StepUpResult.Cancelled -> { }
    is StepUpResult.Error -> { }               // r.message
    null -> { }                                // result missing or malformed
}
```

`VerityPro.extractResult(result)` also works on a step-up result: it returns a `VerityResult`
with `verdict`, `similarityScore`, `attemptCount` and `maxAttempts`.

> **Known defect: do not use `VerityPro.startStepUp(launcher, activity, ...)`.** It has no
> `apiBaseUrl` parameter and calls `createStepUpIntent` without one, so it always throws
> `IllegalArgumentException: apiBaseUrl is required` now that there is no default host
> [`veritypro-sdk/.../VerityPro.kt`, `startStepUp`]. Use `createStepUpIntent(..., apiBaseUrl = ...)`
> as above, or `createVerificationIntent` with `mode = STEP_UP_AUTH`, `stepUpChallengeId` and
> `apiBaseUrl` set on `VerityOption`.

### Device collection (standalone, for transaction monitoring)

For merchants who only need device signals, with no KYC flow. Call it from a coroutine.

```kotlin
import com.veritypro.devicesdk.VerityDevice

lifecycleScope.launch {
    val token: String? = VerityDevice.collect(
        context = applicationContext,
        integrationId = "<YOUR_INTEGRATION_UUID>",
        baseUrl = "https://api.skylinefare.com",   // Sandbox. Live: https://api.veritypro.ai
    )
    // token is a "vpds_..." string, or null on network failure. Attach it to your transaction
    // as `vpds_session_token`.
}
```

The endpoint is anonymous. `apiKey` is a deprecated parameter that is ignored and never sent; do
not pass one. A network failure returns `null` (submit your transaction without a token rather
than blocking it); a bad `baseUrl` throws `IllegalArgumentException`.

## Results and errors

The flow returns a `VerityResult` in the activity-result `Intent` under the extra `verity_result`;
read it with `VerityPro.extractResult(result)` [`veritypro-sdk/.../utils/VerityResult.kt`]. The
activity always finishes with `RESULT_OK`; the outcome is in the result, not the result code.
A legacy `verification_result` extra (`LivenessResult`) is still set; use `extractResult` instead.

- `outcome` (`VerityOutcome`): `APPROVED`, `SUBMITTED`, `REJECTED`, `PENDING_MANUAL_REVIEW`,
  `CANCELLED`, `FAILED`. `isApproved` is the approved check. `SUBMITTED` means received, no
  decision yet. `status` holds the same value as a string.
- `sessionId` (engine session; keep it for a later `previousEngineSessionId`), `serverSessionId`,
  `addressSessionId`, `operationId`, `completedSteps`, `confidence`, `eddCaseId`,
  `eddAssessmentId`, `eddVerdict`, `addressVerdict`, `decisionId`, `challengeId`.
- Step-up fields: `verdict`, `similarityScore`, `attemptCount`, `maxAttempts`.
- `error` (`VerityVerificationError`): `errorCode` (`VerityErrorCode`), `code` (string),
  `message`, `recoverable`, `recommendedAction`, `supportReferenceId`.

`VerityErrorCode` values. Unrecognised server codes map to `UNKNOWN`.

| Group | Codes |
|---|---|
| Configuration and consent | `CONFIG_INVALID`, `CONSENT_REQUIRED`, `PERMISSION_DENIED` |
| Capture | `CAMERA_UNAVAILABLE`, `DOCUMENT_UNREADABLE`, `DOCUMENT_WRONG_TYPE`, `DOCUMENT_EXPIRED` |
| Upload | `UPLOAD_FAILED`, `UPLOAD_DUPLICATE`, `IDEMPOTENCY_CONFLICT` |
| Liveness and match | `LIVENESS_TIMEOUT`, `LIVENESS_FAILED`, `FACE_MISMATCH` |
| Network and session | `NETWORK_INTERRUPTED`, `NETWORK_UNAVAILABLE`, `SESSION_EXPIRED`, `SESSION_NOT_FOUND` |
| Platform | `DEVICE_INTEGRITY_BLOCKED`, `RATE_LIMITED`, `SERVER_UNAVAILABLE`, `PROCESSING_TIMEOUT` |
| Fallback | `UNKNOWN` |

Note that an invalid `apiBaseUrl` surfaces as a thrown `IllegalArgumentException` when you build
the intent, not as a `CONFIG_INVALID` result.

## Certificate pinning

`veritypro-sdk` pins TLS to the **configured** `apiBaseUrl` host with an OkHttp `CertificatePinner`
holding the SPKI hashes of ISRG Root YE, ISRG Root X2 and ISRG Root X1
[`VerityEndpoint.ISRG_ROOT_PINS`, `pinnerFor`, `pinnedClient`; applied in `services/retro_instance.kt`
and `utils/VpDeviceSessionService.kt`]. Pins are on CA keys, so the 90-day leaf renewals need no
SDK rebuild. OkHttp pins only hosts it has an entry for, so other hosts the SDK talks to (for
example AWS liveness endpoints) use default system trust.

**The standalone `verity-device-sdk` module is not pinned.** It uses `HttpsURLConnection` with the
device's system CA store and states that it relies on that instead of pinning
[`verity-device-sdk/.../VpDeviceSessionService.kt`, SEC-025]. If you need pinned device collection,
use `VpDeviceSessionService.collectAndSubmit` from `veritypro-sdk`, which is pinned.

## Security notes

- Keep the permanent API key out of shipped apps where you can. Use step-up capability tokens
  and server-created sessions.
- Device collection never sends an API key.
- This README contains placeholders only. Never commit real keys or customer data.

## More documentation

- `docs/INTEGRATION_GUIDE_ANDROID.md`: server-side flows, webhooks, address, EDD.
- `docs/IMPLEMENTATION_GUIDE_ANDROID.md`: implementation notes.
