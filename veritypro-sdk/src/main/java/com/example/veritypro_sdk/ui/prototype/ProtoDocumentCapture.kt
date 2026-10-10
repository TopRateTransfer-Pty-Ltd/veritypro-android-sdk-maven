package com.example.veritypro_sdk.ui.prototype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.veritypro_sdk.capture.CaptureTag
import com.example.veritypro_sdk.capture.ExifOrientation
import com.example.veritypro_sdk.capture.QualityAdvisory
import com.example.veritypro_sdk.capture.RawSideCapture
import com.example.veritypro_sdk.capture.RecordingTimeline
import com.example.veritypro_sdk.capture.VideoBinding
import com.example.veritypro_sdk.capture.VideoEvidence
import com.example.veritypro_sdk.utils.CameraUtils
import com.example.veritypro_sdk.utils.VerityVideoModule
import com.example.veritypro_sdk.utils.VerityVideoRecorder
import kotlinx.coroutines.delay
import java.io.File

/**
 * How long the camera may take to start streaming before the screen reports a camera error
 * instead of "STARTING CAMERA…".
 */
private const val CAMERA_STALL_TIMEOUT_MS = 8_000L

/**
 * Decode a captured JPEG for DISPLAY and rotate it upright per its EXIF orientation. CameraX
 * writes the sensor-oriented frame with an EXIF tag; BitmapFactory ignores it. The uploaded file
 * is never touched — this bitmap is only the on-screen preview. Returns null if it can't decode.
 */
private fun decodeUprightBitmap(path: String): Bitmap? {
    val raw = BitmapFactory.decodeFile(path) ?: return null
    val degrees = try {
        ExifOrientation.degrees(path)
    } catch (e: Exception) {
        Log.w("ProtoDocPreview", "EXIF read failed, showing raw orientation: ${e.message}")
        0
    }
    if (degrees == 0) return raw
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
        if (it !== raw) raw.recycle()
    }
}

/**
 * Screen 5 — live document capture (CameraX), rendered neo-brutalist.
 *
 * Produces one [RawSideCapture] for [tag]: the original JPEG plus a [RecordingTimeline] of this
 * side's own clip. Hardware decides how the clip is made (CameraUtils.DocumentCaptureStrategy):
 *  - CONCURRENT (FULL / LEVEL_3): Preview + ImageCapture + VideoCapture; the still is taken while
 *    the clip records, then the clip stops.
 *  - SEQUENTIAL (LIMITED / LEGACY): phase 1 records Preview + VideoCapture only; the shutter stops
 *    the clip and rebinds Preview + ImageCapture at full resolution for the still.
 *
 * The clip is supporting evidence only. Nothing here treats a recording as an anti-spoof pass:
 * the shutter opens once the camera streams, and a missing or failed clip is reported as such
 * (VideoEvidenceResolver), never silently dropped.
 */
@Composable
fun ProtoDocumentCaptureScreen(
    docLabel: String,
    sideLabel: String,
    tag: CaptureTag,
    frameAspect: Float = 1.586f,
    /** Shown above the guidance pill, e.g. after a photo could not be saved. */
    notice: String? = null,
    onCaptured: (RawSideCapture) -> Unit,
    onClose: () -> Unit,
    onMotionCollected: (com.example.veritypro_sdk.utils.CaptureRuntimeData) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val stopMotion = com.example.veritypro_sdk.utils.rememberCaptureMotion(onCollected = onMotionCollected)
    val videoRecorder = remember { VerityVideoRecorder(context) }
    val videoTier = remember { CameraUtils.getDocumentVideoTier(context) }
    val videoCapture = remember { videoRecorder.buildVideoCapture(videoTier) }
    val strategy = remember { CameraUtils.getDocumentCaptureStrategy(context) }
    val sequential = strategy == CameraUtils.DocumentCaptureStrategy.SEQUENTIAL
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Concurrent binds ImageCapture alongside video, so it is capped at RECORD size. Sequential
    // never binds them together, so the still phase can use the sensor's full resolution.
    val imageCapture = remember {
        CameraUtils.createSmartImageCapture(context, withVideoCapture = !sequential)
    }
    var capturing by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var shootTriggered by remember { mutableStateOf(false) }
    var bindFailed by remember { mutableStateOf(false) }
    var videoBound by remember { mutableStateOf(false) }
    // Sequential only: phase 1 (video) failed to bind, so a still-only camera was bound instead.
    var stillOnly by remember { mutableStateOf(false) }
    // Incremented to re-run the recording effect for a fresh clip (see restart paths below).
    var attemptGen by remember { mutableIntStateOf(0) }

    // The shutter needs a streaming camera — nothing else. Video is not an anti-spoof signal.
    val readyToShoot = cameraReady && !bindFailed

    var cameraStalled by remember { mutableStateOf(false) }
    LaunchedEffect(readyToShoot) {
        cameraStalled = false
        if (!readyToShoot) {
            delay(CAMERA_STALL_TIMEOUT_MS)
            Log.e(
                "ProtoDocCapture",
                "Camera did not start after ${CAMERA_STALL_TIMEOUT_MS}ms " +
                    "(cameraReady=$cameraReady, strategy=$strategy, bindFailed=$bindFailed) — surfacing camera error",
            )
            cameraStalled = true
        }
    }

    /**
     * Pairs this side's still with this side's clip. Every callback carries the generation it was
     * issued under and is dropped when stale: `stopRecording()` only REQUESTS finalisation, so an
     * abandoned clip's Finalize can land after a restart and must not pair with the new still.
     */
    val coord = remember {
        object {
            var generation = 0
            var binding: VideoBinding = VideoBinding.NOT_REQUESTED
            var bindFailureReason: String? = null
            var recordingRequested = false
            var filePath: String? = null
            var startedAtMs: Long? = null
            var startFailure: String? = null
            var endedAtMs: Long? = null
            var finalizeError: String? = null
            var videoDone = false
            /** One delivery per generation, however the still and clip callbacks interleave. */
            var delivered = false

            var shutterAtMs = 0L
            var stillPath: String? = null
            var stillSavedAtMs = 0L
            /** Generation whose clip is being stopped so a fresh one can start for the next shot. */
            var restartWhenGenFinalizes: Int? = null
            var onRestartReady: (() -> Unit)? = null
            var notify: ((RawSideCapture) -> Unit)? = null

            fun timeline() = RecordingTimeline(
                binding = binding, filePath = filePath, startedAtMs = startedAtMs, startFailure = startFailure,
                endedAtMs = endedAtMs, finalizeError = finalizeError, bindFailureReason = bindFailureReason,
            )

            /** Abandon the current pairing; in-flight callbacks from it are ignored from now on. */
            fun reset() {
                generation++
                recordingRequested = false
                filePath = null; startedAtMs = null; startFailure = null
                endedAtMs = null; finalizeError = null; videoDone = false; delivered = false
                stillPath = null; shutterAtMs = 0L; stillSavedAtMs = 0L
            }

            fun onStill(gen: Int, path: String, savedAt: Long) {
                if (gen != generation) {
                    Log.w("ProtoDocCapture", "Dropping still from stale generation $gen (current $generation)")
                    return
                }
                stillPath = path; stillSavedAtMs = savedAt; check()
            }

            fun onVideoStarted(gen: Int, at: Long) {
                if (gen == generation) startedAtMs = at
            }

            fun onVideoFinalized(gen: Int, file: File, at: Long, error: String?) {
                if (gen != generation) {
                    Log.w("ProtoDocCapture", "Discarding clip from stale generation $gen (current $generation)")
                    runCatching { file.delete() }
                    if (restartWhenGenFinalizes == gen) {
                        restartWhenGenFinalizes = null
                        onRestartReady?.invoke()
                    }
                    return
                }
                filePath = file.absolutePath; endedAtMs = at; finalizeError = error
                if (shutterAtMs == 0L) {
                    // Finalised before the shutter was pressed: the clip ended on its own
                    // (duration or size cap, encoder error). Kept as a fact; the resolver reports
                    // it as ENDED_BEFORE_STILL. (SEQUENTIAL finalises after the shutter but before
                    // the still by design — that is not this case.)
                    Log.w("ProtoDocCapture", "Clip ended before the shutter (error=$error)")
                }
                videoDone = true; check()
            }

            fun onVideoStartFailed(gen: Int, reason: String, permissionDenied: Boolean) {
                if (gen != generation) return
                if (permissionDenied) { binding = VideoBinding.PERMISSION_DENIED; bindFailureReason = reason }
                startFailure = reason
                videoDone = true; check()
            }

            /** No clip is coming for this generation (none requested, or binding is not BOUND). */
            fun noVideoForThisShot() { if (!videoDone) { videoDone = true; check() } }

            private fun check() {
                val path = stillPath ?: return
                if (!videoDone || delivered) return
                delivered = true
                val raw = RawSideCapture(
                    tag = tag, stillPath = path, shutterAtMs = shutterAtMs, stillSavedAtMs = stillSavedAtMs,
                    recording = timeline(),
                )
                Log.i(
                    "ProtoDocCapture",
                    "Side ready: ${tag.side} retake=${tag.retakeIndex} strategy=$strategy binding=$binding " +
                        "started=${startedAtMs != null} ended=${endedAtMs != null} error=$finalizeError startFailure=$startFailure",
                )
                notify?.invoke(raw)
            }
        }
    }
    coord.notify = onCaptured

    DisposableEffect(Unit) {
        onDispose { videoRecorder.stopRecording() }
    }

    fun startClip() {
        coord.recordingRequested = true
        val gen = coord.generation
        videoRecorder.startRecording(
            videoCapture = videoCapture,
            module = VerityVideoModule.DOCUMENT,
            sessionId = "proto_${tag.side.name.lowercase()}_r${tag.retakeIndex}",
            tier = videoTier,
            listener = object : VerityVideoRecorder.Listener {
                override fun onStarted(atMs: Long) = coord.onVideoStarted(gen, atMs)
                override fun onFinalized(file: File, atMs: Long, error: String?) = coord.onVideoFinalized(gen, file, atMs, error)
                override fun onStartFailed(reason: String, permissionDenied: Boolean) =
                    coord.onVideoStartFailed(gen, reason, permissionDenied)
            },
        )
    }

    // Start this shot's clip once the camera streams with VideoCapture bound. Keyed on attemptGen
    // so a restart always re-runs even if the flags coalesce in one recomposition.
    LaunchedEffect(cameraReady, videoBound, attemptGen) {
        if (cameraReady && videoBound && coord.binding == VideoBinding.BOUND && !coord.recordingRequested) {
            Log.i("ProtoDocCapture", "Starting ${tag.side} clip (strategy=$strategy, tier=$videoTier, gen=${coord.generation})")
            startClip()
        }
    }

    /** Sequential: send a failed phase 2 back to phase 1 so the retry records a FRESH clip. */
    fun restartPhaseOneAfterFailure() {
        val pv = previewViewRef
        if (pv == null) {
            bindFailed = true
            return
        }
        coord.reset()
        attemptGen++
        videoBound = false
        cameraReady = false
        shootTriggered = false
        capturing = false
        CameraUtils.bindVideoRecording(
            context, lifecycleOwner, pv, videoCapture,
            onReady = {
                coord.binding = VideoBinding.BOUND
                videoBound = true
                cameraReady = true
            },
            onError = { msg ->
                Log.e("ProtoDocCapture", "Phase 1 restart failed: $msg")
                videoBound = false
                bindFailed = true
            },
        )
    }

    /**
     * Concurrent: the still failed while the clip was recording. That clip no longer brackets a
     * still, so it is abandoned and a fresh clip starts for the next shot (stale risk b).
     */
    fun restartClipAfterPhotoFailure() {
        val oldGen = coord.generation
        val hadClip = coord.recordingRequested && coord.startFailure == null && coord.endedAtMs == null
        coord.reset()
        shootTriggered = false
        capturing = false
        if (coord.binding != VideoBinding.BOUND) return
        if (hadClip) {
            coord.restartWhenGenFinalizes = oldGen
            coord.onRestartReady = { attemptGen++ }
            videoRecorder.stopRecording()
        } else {
            attemptGen++
        }
    }

    fun capturePhoto(videoAlreadyStopped: Boolean) {
        val gen = coord.generation
        val file = File(
            context.cacheDir,
            "proto_doc_${tag.attemptId.take(8)}_${tag.side.name.lowercase()}_r${tag.retakeIndex}.jpg",
        )
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            opts, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val savedAt = System.currentTimeMillis()
                    stopMotion()
                    Log.i(
                        "ProtoDocCapture",
                        "Still saved at ${imageCapture.resolutionInfo?.resolution} " +
                            "(strategy=$strategy, videoAlreadyStopped=$videoAlreadyStopped)",
                    )
                    coord.onStill(gen, file.absolutePath, savedAt)
                    when {
                        videoAlreadyStopped -> Unit // the clip was stopped at the shutter; Finalize delivers it
                        coord.binding == VideoBinding.BOUND && coord.recordingRequested && !coord.videoDone ->
                            videoRecorder.stopRecording() // Finalize -> coord.onVideoFinalized
                        else -> coord.noVideoForThisShot()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("ProtoDocCapture", "takePicture failed: ${exc.imageCaptureError}", exc)
                    runCatching { file.delete() }
                    if (sequential && !stillOnly) {
                        restartPhaseOneAfterFailure() // the clip is already spent; record a new one
                    } else if (!sequential) {
                        restartClipAfterPhotoFailure()
                    } else {
                        coord.reset()
                        capturing = false
                        shootTriggered = false
                    }
                }
            },
        )
    }

    fun shootStill() {
        if (shootTriggered) return
        shootTriggered = true
        coord.shutterAtMs = System.currentTimeMillis()

        if (!sequential || stillOnly) {
            // Concurrent: take the still WHILE the clip records; it is stopped after the JPEG saves.
            capturePhoto(videoAlreadyStopped = false)
            return
        }

        // SEQUENTIAL PHASE 2 — finalise the clip, then rebind still-only at full resolution.
        // The recording must stop BEFORE ImageCapture is bound or we recreate the very
        // concurrent combination this device cannot serve.
        val pv = previewViewRef
        if (pv == null) {
            Log.e("ProtoDocCapture", "Sequential shutter: no PreviewView — aborting")
            capturing = false
            shootTriggered = false
            return
        }
        if (coord.recordingRequested && !coord.videoDone) {
            videoRecorder.stopRecording() // Finalize -> coord.onVideoFinalized
        } else if (!coord.videoDone) {
            coord.noVideoForThisShot()
        }
        CameraUtils.bindSmartCamera(
            context, lifecycleOwner, pv, imageCapture,
            videoCapture = null,
            useViewPort = false,
            onCameraReady = { capturePhoto(videoAlreadyStopped = true) },
            onCameraError = { msg ->
                Log.e("ProtoDocCapture", "Sequential phase 2 bind failed: $msg")
                restartPhaseOneAfterFailure()
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Camera preview — stays visible during "CAPTURING…" just like iOS.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewStreamState.observe(lifecycleOwner) { st ->
                        if (st == PreviewView.StreamState.STREAMING) cameraReady = true
                    }
                }.also { pv ->
                    previewViewRef = pv
                    Log.i("ProtoDocCapture", "Document video strategy=$strategy tier=$videoTier side=${tag.side}")
                    if (sequential) {
                        // PHASE 1 — record only. No ImageCapture is bound, so the clip cannot
                        // collapse the still.
                        CameraUtils.bindVideoRecording(
                            ctx, lifecycleOwner, pv, videoCapture,
                            onReady = {
                                coord.binding = VideoBinding.BOUND
                                videoBound = true
                                cameraReady = true
                            },
                            onError = { msg ->
                                Log.e("ProtoDocCapture", "Sequential phase 1 bind failed: $msg — binding still-only")
                                coord.binding = VideoBinding.BIND_FAILED
                                coord.bindFailureReason = VideoEvidence.REASON_BIND_FAILED
                                videoBound = false
                                stillOnly = true
                                CameraUtils.bindSmartCamera(
                                    ctx, lifecycleOwner, pv, imageCapture,
                                    videoCapture = null,
                                    useViewPort = false,
                                    onCameraError = { err ->
                                        Log.e("ProtoDocCapture", "Still-only bind failed too: $err")
                                        bindFailed = true
                                    },
                                )
                            },
                        )
                    } else {
                        CameraUtils.bindSmartCamera(
                            ctx, lifecycleOwner, pv, imageCapture,
                            videoCapture = videoCapture,
                            onVideoCaptureBound = { bound ->
                                coord.binding = if (bound) VideoBinding.BOUND else VideoBinding.UNSUPPORTED
                                if (!bound) coord.bindFailureReason = VideoEvidence.REASON_IMAGE_QUALITY_GUARD
                                videoBound = bound
                            },
                            onCameraError = { msg ->
                                Log.e("ProtoDocCapture", "Concurrent bind failed: $msg")
                                bindFailed = true
                            },
                            // No viewport crop: keep the still at the sensor's native 4:3 like iOS `.photo`.
                            useViewPort = false,
                        )
                    }
                }
            },
        )

        // Top bar — close + document · side mono label.
        Row(
            Modifier.fillMaxWidth().background(Color(0xCC120037)).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✕", color = Color.White, fontFamily = ProtoDisplay, fontSize = 20.sp,
                fontWeight = FontWeight.Black, modifier = Modifier.protoClick(onClose))
            Spacer(Modifier.width(16.dp))
            MonoLabel("${docLabel.uppercase()} · ${sideLabel.uppercase()}", Color.White, size = 12)
        }

        // Bottom: guidance pill + shutter button — matches iOS layout.
        Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (notice != null) {
                    Box(Modifier.background(Color(0xE6171717)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(notice, color = Color.White, fontFamily = ProtoDisplay, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Box(Modifier.background(Color(0xB3171717)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    MonoLabel(
                        when {
                            bindFailed || cameraStalled -> "CAMERA UNAVAILABLE · CLOSE AND RETRY"
                            !readyToShoot -> "STARTING CAMERA…"
                            capturing     -> "CAPTURING…"
                            else          -> "FIT YOUR ${sideLabel.uppercase()} IN VIEW · HOLD STEADY"
                        },
                        Color.White, size = 12,
                    )
                }
                Spacer(Modifier.height(16.dp))
                BrutalBox(
                    background = Color.White,
                    borderColor = Color.White,
                    modifier = Modifier.width(120.dp),
                ) {
                    Text(
                        if (!readyToShoot || capturing) "…" else "CAPTURE",
                        color = if (readyToShoot && !capturing) Proto.Ink else Color(0xFF9AA0A6),
                        fontFamily = ProtoDisplay, fontSize = 15.sp, fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().protoClick {
                            if (readyToShoot && !capturing) {
                                capturing = true
                                shootStill()
                            }
                        }.padding(vertical = 18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Screen 6 — check your photo.
 *
 * No network call and no verdict. "Looks good" is enabled as soon as the still exists; "Retake" is
 * always available. The [advisory] (blur / glare / exposure, UNCALIBRATED) may show a hint and
 * never blocks. The document is checked by the server after upload.
 */
@Composable
fun ProtoDocumentPreviewScreen(
    imagePath: String,
    advisory: QualityAdvisory,
    frameAspect: Float = 1.586f,
    onLooksGood: () -> Unit,
    onRetake: () -> Unit,
    onChangeDocumentType: (() -> Unit)? = null,
) {
    val bmp = remember(imagePath) { decodeUprightBitmap(imagePath) }
    val stillExists = remember(imagePath) { File(imagePath).let { it.exists() && it.length() > 0 } }
    val hint = advisory.hint()

    Column(Modifier.fillMaxSize().background(Proto.Canvas)) {
        ProtoTopBar(step = null, onBack = onRetake)
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp)) {
            Text(
                "Check your photo", color = Proto.Ink, fontFamily = ProtoDisplay,
                fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, lineHeight = 36.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text("Is everything clear and readable?", color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))
            BrutalBox {
                // The image keeps its intrinsic aspect (ContentScale.Fit): the whole captured
                // document shows and the box height follows the photo.
                val imgAspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else frameAspect
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val imgHeight = maxWidth / imgAspect
                    Box(Modifier.fillMaxWidth().height(imgHeight)) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Captured document",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(Color(0xFFEEF0F4)))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            MonoLabel(if (stillExists) "PHOTO CAPTURED" else "PHOTO NOT SAVED · PLEASE RETAKE", Proto.Ink, size = 11)
            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    hint, color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 20.sp,
                )
            }
        }
        Column(Modifier.padding(24.dp)) {
            ProtoPrimaryButton("Looks good", enabled = stillExists, onClick = onLooksGood)
            Spacer(Modifier.height(10.dp))
            Text(
                "Retake", color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().protoClick(onRetake).padding(12.dp), textAlign = TextAlign.Center,
            )
            if (onChangeDocumentType != null) {
                Text(
                    "Choose a different ID", color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().protoClick(onChangeDocumentType).padding(8.dp), textAlign = TextAlign.Center,
                )
            }
        }
    }
}
