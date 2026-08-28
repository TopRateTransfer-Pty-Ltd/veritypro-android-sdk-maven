package com.example.veritypro_sdk.ui.prototype

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amplifyframework.AmplifyException
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Amplify
import com.example.veritypro_sdk.services.CountryDocumentItem
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.ui.verification.VerityProViewModel
import com.example.veritypro_sdk.utils.VerityOption

private enum class ProtoStage { Welcome, Connecting, ChooseId, CameraAccess, BeforeShoot, Capture, DocPreview, SelfieIntro, Liveness, AllComplete }

// SDK document-type int (MLDocumentType.fromSdkType): 1=ID Card, 2=Passport, 3=Driver's Licence.
private fun protoDocTypeInt(name: String?): Int {
    val n = name?.lowercase() ?: ""
    return when {
        "passport" in n -> 2
        "driver" in n || "licence" in n || "license" in n -> 3
        else -> 1
    }
}

// Passport = front only; Driver's Licence & ID Card = front + back. List of isBack flags.
private fun protoSides(name: String?): List<Boolean> =
    if (protoDocTypeInt(name) == 2) listOf(false) else listOf(false, true)

// Capture-frame aspect ratio: passport data page (ID-3, ~125×88mm → 1.42) is chunkier and gets a
// taller/bigger box; licence & ID card (ID-1, ~85.6×54mm → 1.586) are wider.
private fun protoFrameAspect(name: String?): Float =
    if (protoDocTypeInt(name) == 2) 1.42f else 1.586f

/**
 * API-CONNECTED prototype flow (neo-brutalist, VerityPro KYC SDK.dc.html).
 * Real wiring via [VerityProViewModel]: `createKyc(options)` creates the backend session and the
 * Choose-ID screen is populated from the live `countryDocumentsState`, not static data.
 * Camera capture + liveness screens follow (reuse the existing capture pipeline).
 */
@Composable
fun ProtoVerificationScreen(
    options: VerityOption,
    onExit: () -> Unit = {},
) {
    val vm: VerityProViewModel = viewModel()
    val kyc by vm.kycState.collectAsState()
    val docsState by vm.countryDocumentsState.collectAsState()
    val beginState by vm.beginLivenessState.collectAsState()
    val livenessRegion by vm.livenessRegion.collectAsState()
    val livenessCredentials by vm.livenessCredentials.collectAsState()
    var livenessApproved by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(ProtoStage.Welcome) }
    var chosen by remember { mutableStateOf<CountryDocumentItem?>(null) }
    var capturedPath by remember { mutableStateOf<String?>(null) }
    var capturedPads by remember { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var sideIndex by remember { mutableStateOf(0) }
    var frontPath by remember { mutableStateOf<String?>(null) }
    var backPath by remember { mutableStateOf<String?>(null) }
    // Cap the auto-retake loop: after this many consecutive failed attempts on a side, stop
    // auto-returning to the camera and show a manual failure (so a persistently-failing capture
    // — e.g. a document the server won't accept — can never loop forever).
    var retakeAttempts by remember { mutableStateOf(0) }
    val maxAutoRetakes = 3
    val context = LocalContext.current

    // Ensure Amplify is configured for the liveness detector (idempotent). The real SDK Activity
    // configures it, but the prototype may be hosted by a plain Activity that doesn't.
    LaunchedEffect(Unit) {
        try {
            Amplify.addPlugin(AWSCognitoAuthPlugin())
            Amplify.configure(context.applicationContext)
        } catch (e: AmplifyException) {
            // already configured, or config missing — safe to continue
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // Camera is required to advance; coarse location consent is requested alongside it (optional).
        if (grants[Manifest.permission.CAMERA] == true) stage = ProtoStage.BeforeShoot
    }

    // Fire the real backend session creation when the user consents and continues.
    LaunchedEffect(stage) {
        if (stage == ProtoStage.Connecting) {
            vm.createKyc(options)
        }
    }
    // Advance to Choose-ID once the session is live and the region documents have loaded.
    LaunchedEffect(kyc, docsState) {
        if (stage == ProtoStage.Connecting && kyc is Resource.Success<*> && docsState is Resource.Success<*>) {
            stage = ProtoStage.ChooseId
        }
    }

    when (stage) {
        ProtoStage.Welcome -> ProtoWelcomeScreen(
            onGetStarted = { stage = ProtoStage.Connecting },
            onPrivacy = {},
        )

        ProtoStage.Connecting -> when (val s = kyc) {
            is Resource.Error -> ProtoErrorScreen(
                kicker = "COULDN'T START",
                title = "Something went wrong",
                message = s.message,
                onRetry = { stage = ProtoStage.Welcome },
                onExit = onExit,
            )
            else -> ProtoProcessingScreen(
                kicker = "SECURE SESSION",
                title = "Setting up your\nverification",
                message = (s as? Resource.Loading)?.message ?: "Connecting to VerityPro…",
            )
        }

        ProtoStage.ChooseId -> {
            val docs = (docsState as? Resource.Success<List<CountryDocumentItem>>)?.data ?: emptyList()
            ProtoChooseIdScreen(
                documents = docs,
                onBack = { stage = ProtoStage.Welcome },
                onPick = { chosen = it; sideIndex = 0; frontPath = null; backPath = null; retakeAttempts = 0; stage = ProtoStage.CameraAccess },
            )
        }

        ProtoStage.CameraAccess -> ProtoCameraAccessScreen(
            onAllow = {
                val camGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                if (camGranted && locGranted) {
                    stage = ProtoStage.BeforeShoot
                } else {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }
            },
            onNotNow = onExit,
            onBack = { stage = ProtoStage.ChooseId },
        )

        ProtoStage.BeforeShoot -> ProtoBeforeShootScreen(
            docLabel = chosen?.documentType ?: "Document",
            sideLabel = "Front",
            onOpenCamera = { stage = ProtoStage.Capture },
            onSkip = { stage = ProtoStage.Capture },
            onBack = { stage = ProtoStage.CameraAccess },
        )

        ProtoStage.Capture -> {
            val sides = protoSides(chosen?.documentType)
            val isBack = sides.getOrElse(sideIndex) { false }
            ProtoDocumentCaptureScreen(
                docLabel = chosen?.documentType ?: "Document",
                sideLabel = if (isBack) "Back" else "Front",
                frameAspect = protoFrameAspect(chosen?.documentType),
                onCaptured = { path, pads ->
                    if (isBack) backPath = path else frontPath = path
                    capturedPath = path
                    capturedPads = pads
                    stage = ProtoStage.DocPreview
                },
                onClose = { stage = ProtoStage.BeforeShoot },
            )
        }

        ProtoStage.DocPreview -> {
            val sides = protoSides(chosen?.documentType)
            val isBack = sides.getOrElse(sideIndex) { false }
            ProtoDocumentPreviewScreen(
                vm = vm,
                imagePath = capturedPath ?: "",
                docTypeInt = protoDocTypeInt(chosen?.documentType),
                isBack = isBack,
                padFrames = capturedPads,
                autoRetake = retakeAttempts < maxAutoRetakes,
                frameAspect = protoFrameAspect(chosen?.documentType),
                onLooksGood = {
                    retakeAttempts = 0
                    if (sideIndex < sides.lastIndex) {
                        // more sides to capture (e.g. the back of a licence / ID card)
                        sideIndex += 1
                        stage = ProtoStage.Capture
                    } else {
                        vm.setCapturedDocumentPaths(front = frontPath, back = backPath, video = null)
                        stage = ProtoStage.SelfieIntro
                    }
                },
                onRetake = {
                    retakeAttempts += 1
                    stage = ProtoStage.Capture
                },
            )
        }

        ProtoStage.SelfieIntro -> ProtoSelfieIntroScreen(
            onReady = {
                vm.startBeginLiveness(vm.getSessionId(), forceRetry = true)
                stage = ProtoStage.Liveness
            },
            onBack = { stage = ProtoStage.Welcome },
        )

        ProtoStage.Liveness -> when (val bs = beginState) {
            is Resource.Success -> {
                val aws = bs.data.awsSessionId
                if (aws.isNullOrBlank()) {
                    ProtoProcessingScreen("BIOMETRIC", "Preparing the\nliveness check", "One moment…", Proto.Teal)
                } else {
                    // Call the liveness detector directly in a neo-brutalist wrapper (no legacy prep UI).
                    ProtoLivenessScreen(
                        awsSessionId = aws,
                        region = livenessRegion,
                        credentials = livenessCredentials,
                        onComplete = {
                            vm.verifyLivenessResult(bs.data.id ?: aws) { ok ->
                                livenessApproved = ok
                                stage = ProtoStage.AllComplete
                            }
                        },
                        onError = { stage = ProtoStage.SelfieIntro },
                    )
                }
            }
            is Resource.Error -> ProtoErrorScreen(
                kicker = "BIOMETRIC",
                title = "Couldn't start the check",
                // Generic copy — never surface backend/vendor identifiers from the raw error.
                message = "We couldn't start the liveness check. Please try again.",
                onRetry = { stage = ProtoStage.SelfieIntro },
                onExit = onExit,
            )
            else -> ProtoProcessingScreen("BIOMETRIC · LIVENESS", "Starting the\nliveness check", "One moment…", Proto.Teal)
        }

        ProtoStage.AllComplete -> ProtoAllCompleteScreen(approved = livenessApproved, onDone = onExit)
    }
}

/** Screen 2 — Choose your ID, populated from the live backend document list. */
@Composable
private fun ProtoChooseIdScreen(
    documents: List<CountryDocumentItem>,
    onBack: () -> Unit,
    onPick: (CountryDocumentItem) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Proto.Canvas).verticalScroll(rememberScrollState())
    ) {
        ProtoTopBar(step = "1/4", onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(
                "Choose your ID",
                color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 34.sp,
                fontWeight = FontWeight.Black, letterSpacing = (-1).sp, lineHeight = 36.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Only documents accepted in your region are shown.",
                color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                documents.forEach { doc ->
                    BrutalBox {
                        Row(
                            Modifier.protoClick { onPick(doc) }.padding(horizontal = 16.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(28.dp).background(Proto.Flamingo))
                            Spacer(Modifier.width(14.dp))
                            Text(
                                doc.documentType,
                                color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 17.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                            )
                            Text("›", color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
                if (documents.isEmpty()) {
                    MonoLabel("NO DOCUMENTS RETURNED FOR THIS REGION", Proto.Sub, size = 12)
                }
            }
            Spacer(Modifier.height(16.dp))
            MonoLabel("SELECTINGDOCUMENT · allowedDocumentTypes = ${documents.size}", Proto.Sub, size = 11)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Neo-brutalist processing screen with an animated hard-edged progress bar (vpBar). */
@Composable
private fun ProtoProcessingScreen(
    kicker: String,
    title: String,
    message: String,
    module: Color = Proto.Brand,
) {
    Column(
        Modifier.fillMaxSize().background(Proto.Canvas).padding(26.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        MonoLabel(kicker, module)
        Spacer(Modifier.height(14.dp))
        Text(
            title, color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 40.sp,
            fontWeight = FontWeight.Black, lineHeight = 42.sp, letterSpacing = (-1.2).sp,
        )
        Spacer(Modifier.height(18.dp))
        Text(message, color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        // hard-edged animated fill inside an ink-bordered track
        val t = rememberInfiniteTransition(label = "bar")
        val frac by t.animateFloat(
            initialValue = 0.08f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "frac",
        )
        BrutalBox(background = Color.White, shadow = false) {
            Box(Modifier.fillMaxWidth().height(18.dp)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(module))
            }
        }
    }
}

/** Neo-brutalist terminal error card with retry. */
@Composable
private fun ProtoErrorScreen(
    kicker: String,
    title: String,
    message: String,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Proto.Canvas).padding(26.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        BrutalBox(background = Color.White) {
            Column(Modifier.padding(20.dp)) {
                MonoLabel(kicker, Proto.Danger)
                Spacer(Modifier.height(10.dp))
                Text(title, color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text(message, color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        ProtoPrimaryButton("Try again", onClick = onRetry)
        Spacer(Modifier.height(10.dp))
        Text(
            "Close", color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 14.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().protoClick(onExit).padding(12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
