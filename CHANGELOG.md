# Changelog — VerityPro Android SDK (`com.example.veritypro:veritypro-sdk`)

## 1.9.5 — Unreleased

- **HD document video at 700 kbps / 15 MiB** (#74), the iOS SDK policy. The HD tier (Camera2 FULL /
  LEVEL_3) set no bitrate and a 30 MiB cap, so CameraX encoded 720p at its default (several Mbit/s):
  clips passed the KYC engine's 20 MB per-file limit (413 "document_video exceeds 20MB", prod
  2026-10-28/29), and 20-55 MB multipart bodies were cut off on slow links (400/408/499). Every tier
  now sets its bitrate; SD is unchanged (800 kbps / 8 MiB / 90 s). The size cap finalizes a valid MP4
  (`ERROR_FILE_SIZE_LIMIT_REACHED`, reported in the capture, not hidden). TCL T442M (Camera2 LIMITED,
  so SD tier), 2026-10-30: 10.3 s clip 633 KB at 490 kbps, uploaded 202 on staging. The HD tier has
  not been run on a FULL / LEVEL_3 device.

## 1.9.4 — Unreleased

- **A failed face scan ends the attempt** (#71). When liveness returns a scored failure, the flow
  finishes with "Done" only; there is no "Try again" inside the same attempt. Retry is kept only for
  a scan that never finished (camera/network/timeout). The backend allows 5 attempts per customer
  per 24 h (KYC #514).
- **Document photos are smaller** (#72). Stills over 2560 px on the long edge are downscaled to
  2560 px at JPEG quality 90 before upload, with EXIF rotation baked in. The sha256 in the capture
  metadata is computed after resizing. TCL T442M, 2026-10-27: passport still 2.3 MB → 0.77 MB, read
  and tamper-checked normally by the engine. A still that cannot be resized fails the side (retake).

## 1.9.3 — Unreleased

Document capture cues, ported from the hosted verification page (Customer.Web #838, #840).

- After the front of a licence or ID card is accepted, a short screen shows the card turning over
  ("Now turn your licence over"), with "I'm ready" or an automatic continue after 3.4 s.
- Before a passport photo, a screen shows the booklet opening to the photo page.
- Camera: the title counts sides ("FRONT · 1 OF 2" / "BACK · 2 OF 2"), the back side has a yellow
  top bar, and a framing guide with faint artwork of the side to photograph is drawn.
- "Before you shoot" says the back comes after the front for two-sided documents.
- "Check your photo" opens zoomed to the framed document, with "See full photo" / "Zoom to
  document". The zoom is display only: the full photo is uploaded and the crop is not in metadata.
- No framing gate: the Android shutter stays manual, as before (the hosted page's tolerant
  framing check has no Android counterpart to relax).
- Found on a TCL T442M: the longer camera title ran under the flow's round exit button. The bar
  now keeps clear of it and the title only wraps after the document name.
- Found on a TCL T442M: "See full photo" on a portrait photo pushed "Zoom to document" and the
  quality hint off screen. The photo now shrinks to the space left above the buttons.

## 1.9.0 — Unreleased

Document capture no longer calls DocAI. Version **1.9.0** (minor bump above 1.8.1, the SVG-logo
release in PR #62). `veritySdkVersion` in `veritypro-sdk/build.gradle` is the single source for the
published Maven version and for `BuildConfig.SDK_VERSION`, which is what `CaptureMetadataJson`
reports as `sdk.version`. This release is a **source and binary breaking change** for any integrator
that used the public ML classes listed below. No integrator in the VerityPro workspace uses them (the Flutter SDK
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
