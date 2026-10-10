# Changelog — VerityPro Android SDK (`com.example.veritypro:veritypro-sdk`)

## Unreleased

Document capture no longer calls DocAI. The version number is not bumped here; the release step
does that. This release is a **source and binary breaking change** for any integrator that used the
public ML classes listed below. No integrator in the VerityPro workspace uses them (the Flutter SDK
was searched); third-party use has not been checked.

### Removed (breaking)

- DocAI client and models, package `com.example.veritypro_sdk.services`:
  `MLRetrofitInstance`, `MLApiService`, `MLRepository`, `MLV2Repository`,
  `MLPredictRequest`, `MLPredictResponse`, `MLBoundingBox`, `MLConfidence`, `MLQualitySignals`,
  `MLVerifyBurstRequest`, `MLVerifyBurstResponse`, `MLSpoofResult`, `MLDetectPresenceRequest`,
  `MLDetectPresenceResponse`, `MLModelInfo`, `MLModelsResponse`, `MLHealthResponse`,
  `MLDocumentType`, `MLDocumentSide`, `MLReason`, `MLNextAction`, `MLDecision`, `MLSpoofType`,
  `sanitizeMLHint`, `MLCaptureFrame`, `MLDeviceSignals`, `MLCaptureVerifyRequest`,
  `MLCaptureVerifyResponse`, `MLSpoofVerdict`, `MLTamperVerdict`, `MLCaptureDecision`,
  `MLRetryGuidance`, `MLCaptureState`, `MLPairCheckRequest`, `MLPairCheckResponse`, `MLPairState`.
- `com.example.veritypro_sdk.ui.verification.V2CaptureConfig`.
- `VerityProViewModel`: constructor parameter `mlRepository`; `configureMLBackend`,
  `checkMLBackendHealth`, `mlPredictDocument`, `mlPredictDocumentBitmap`, `mlVerifyBurst`,
  `mlVerifyBurstBitmaps`, `resetMLPredictState`, `resetMLVerifyBurstState`, `mlPredictState`,
  `mlVerifyBurstState`, `mlBackendAvailable`.
- `com.example.veritypro_sdk.utils.BurstCaptureUtils` and the commented-out TFLite document detector.
- `CaptureRuntimeData.antiSpoofBurstScore` (nothing ever set it; the key was never present in
  `SecurityAssessmentJson`, so the JSON is unchanged) and its risk-score term.
- `CameraUtils.bindSmartCamera(frameCollector = …)` (the PAD frame collector existed only for DocAI).
- Dependencies `org.tensorflow:tensorflow-lite`, `tensorflow-lite-gpu`, `tensorflow-lite-support`.

### Changed

- "Check your photo": no network call and no verdict. "Looks good" is enabled once the photo is
  saved, "Retake" is always available, there is no automatic retake, and the screen says
  "Photo captured" (never "verified"). An advisory quality hint (blur, glare, exposure; thresholds
  UNCALIBRATED) may be shown; it never blocks.
- Each document side records its own clip. The front clip is uploaded as `DocumentVideo` (unchanged)
  and the back clip as the new `DocumentBackVideo`. FULL/LEVEL_3 devices still record concurrently;
  LIMITED/LEGACY devices still record first and then take the still.
- A recording is no longer treated as an anti-spoof signal; the shutter waits only for the camera.

### Added

- `update-kyc-verification` sends `CaptureAttemptId` (UUID, new per document submission) and
  `CaptureMetadataJson` (schema `veritypro.capture.v1`: per side retake count, image size and
  sha256, video status/timing/sha256/failure reason, quality advisory).
- Response handling: `RECAPTURE_REQUIRED` (HTTP 400) shows the reason and returns to capture under a
  new attempt; `TECHNICAL_UNAVAILABLE` (HTTP 503) is retried up to 3 times (2 s, 4 s, 8 s) with the
  same attempt id, then the host receives `status=FAILED` with error `SERVER_UNAVAILABLE`
  (recoverable). Backends that do not send `captureStatus` behave exactly as before.
- `BuildConfig.SDK_VERSION`, reported in the capture metadata.

### Fixed

- Choosing a different document, or a recapture request, starts a new attempt and discards every
  photo and clip of the previous one (an old document's clip could be uploaded for a new one).
- A still that fails while recording (FULL devices) abandons that clip and starts a new one for the
  next shot.
- A clip that ended before the shutter (duration/size cap) is reported as `FAILED` /
  `ENDED_BEFORE_STILL` and not uploaded.
- A recording that failed to start is reported as `FAILED`, never as present.
