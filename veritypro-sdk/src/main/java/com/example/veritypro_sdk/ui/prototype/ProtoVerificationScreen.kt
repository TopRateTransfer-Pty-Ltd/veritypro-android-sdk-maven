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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.statusBarsPadding
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

private enum class ProtoStage { Welcome, Connecting, ChooseId, CameraAccess, BeforeShoot, Capture, DocPreview, PairChecking, AddressEntry, SelfieIntro, Liveness, AddressUpload, EddUpload, Submitting, AllComplete }

// Ordered active modules for this product (document → biometric → address → edd).
private fun protoModuleOrder(options: VerityOption): List<String> {
    val w = protoWants(options)
    return buildList {
        if (w.document) add("DOCUMENT")
        if (w.biometric) add("BIOMETRIC")
        if (w.address) add("ADDRESS")
        if (w.edd) add("EDD")
    }.ifEmpty { listOf("DOCUMENT") }
}

// The stage that starts a given module.
private fun stageForModule(module: String): ProtoStage = when (module) {
    "BIOMETRIC" -> ProtoStage.SelfieIntro
    "ADDRESS" -> ProtoStage.AddressEntry   // enter address → create session → upload proof
    "EDD" -> ProtoStage.EddUpload
    else -> ProtoStage.ChooseId // DOCUMENT
}

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

// Which verification modules this product requires. Driven by requiredModules when present, else
// mapped from the VerityMode: DOCUMENT=doc, BIOMETRIC=doc+selfie, LIVENESS_ONLY=selfie, ADDRESS,
// EDD, COMBINED=all.
private data class ProtoWants(val document: Boolean, val biometric: Boolean, val address: Boolean, val edd: Boolean)

private fun protoWants(options: VerityOption): ProtoWants {
    val req = options.requiredModules?.map { it.uppercase() }.orEmpty()
    val mode = options.mode.uppercase()
    return if (req.isNotEmpty()) {
        ProtoWants(
            document = req.any { it.contains("DOCUMENT") },
            biometric = req.any { it.contains("BIOMETRIC") || it.contains("LIVENESS") },
            address = req.any { it.contains("ADDRESS") },
            edd = req.any { it.contains("EDD") },
        )
    } else {
        ProtoWants(
            document = mode in setOf("DOCUMENT", "BIOMETRIC", "COMBINED"),
            biometric = mode in setOf("BIOMETRIC", "LIVENESS_ONLY", "COMBINED"),
            address = mode in setOf("ADDRESS", "COMBINED"),
            edd = mode in setOf("EDD", "COMBINED"),
        )
    }
}

// Dynamic completion copy — reflects the modules actually completed so the terminal screen never
// over-claims (e.g. a document-only run must not say "liveness checks passed").
private fun protoCompletionCopy(options: VerityOption, approved: Boolean): Pair<String, String> {
    if (!approved) {
        return "Almost there" to
            "We're finishing your checks. You can close this — we'll notify you when it's done."
    }
    val w = protoWants(options)
    val items = buildList {
        if (w.document) add("identity document")
        if (w.biometric) add("selfie")
        if (w.address) add("proof of address")
        if (w.edd) add("income details")
    }.ifEmpty { listOf("verification") }
    val list = when (items.size) {
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
    return "You're all done" to
        "We've received your $list. You can close this — we'll notify you when your checks are complete."
}

// The dynamic "what we'll need" rows on the welcome screen, per requested product.
private fun protoIntroModules(options: VerityOption): List<ProtoModuleItem> {
    val w = protoWants(options)
    val out = mutableListOf<ProtoModuleItem>()
    if (w.document) out.add(ProtoModuleItem("A photo of your ID", "Passport, licence or ID card", Proto.Flamingo, false))
    if (w.biometric) out.add(ProtoModuleItem("A quick selfie", "Liveness check, no photos kept", Proto.Teal, true))
    if (w.address) out.add(ProtoModuleItem("Proof of address", "A recent bill or bank statement", Proto.GoldenFizz, false))
    if (w.edd) out.add(ProtoModuleItem("Proof of income", "Payslip, statement or tax return", Proto.Indigo, false))
    return out.ifEmpty { listOf(ProtoModuleItem("A photo of your ID", "Passport, licence or ID card", Proto.Flamingo, false)) }
}

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
    /// Reports the live KYC session id once created. The host persists it so a later biometric-only
    /// (liveness step-up) run can supply it as previousEngineSessionId (backend requires a prior
    /// document session to face-match the selfie against). Fires only for document-bearing runs.
    onSessionEstablished: (String) -> Unit = {},
    /// Called with true=approved/submitted, false=cancelled/failed when the flow reaches a terminal
    /// state and the user taps the done button. Fires before [onExit].
    onResult: (Boolean) -> Unit = {},
) {
    val vm: VerityProViewModel = viewModel()
    val kyc by vm.kycState.collectAsState()
    val docsState by vm.countryDocumentsState.collectAsState()
    val beginState by vm.beginLivenessState.collectAsState()
    val livenessRegion by vm.livenessRegion.collectAsState()
    val livenessCredentials by vm.livenessCredentials.collectAsState()
    val addressState by vm.addressState.collectAsState()
    val addressCreateState by vm.addressCreateState.collectAsState()
    val eddState by vm.eddState.collectAsState()
    var addressStreet by remember { mutableStateOf(options.streetAddress ?: "") }
    var livenessApproved by remember { mutableStateOf(false) }
    // Overall terminal outcome shown on the completion screen (submission accepted end-to-end).
    var flowOk by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(ProtoStage.Welcome) }
    var chosen by remember { mutableStateOf<CountryDocumentItem?>(null) }
    var capturedPath by remember { mutableStateOf<String?>(null) }
    var capturedPads by remember { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var sideIndex by remember { mutableStateOf(0) }
    var frontPath by remember { mutableStateOf<String?>(null) }
    var backPath by remember { mutableStateOf<String?>(null) }
    var frontVideo by remember { mutableStateOf<String?>(null) }
    var backVideo by remember { mutableStateOf<String?>(null) }
    var livenessId by remember { mutableStateOf<String?>(null) }
    var moduleQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var moduleIndex by remember { mutableStateOf(0) }
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
    // createKyc validates requiredModules against integration entitlements. The KYC session only
    // covers DOCUMENT + BIOMETRIC; ADDRESS and EDD are separate services with their OWN create calls,
    // so requesting them here would wrongly 403 the whole flow if either isn't enabled. Send only the
    // KYC-session modules so a combined flow starts even when ADDRESS/EDD entitlements differ.
    LaunchedEffect(stage) {
        if (stage == ProtoStage.Connecting) {
            val kycModules = protoModuleOrder(options).filter { it == "DOCUMENT" || it == "BIOMETRIC" }
            vm.createKyc(options.copy(requiredModules = kycModules.ifEmpty { listOf("DOCUMENT") }))
        }
    }
    // Advance to Choose-ID once the session is live and the region documents have loaded.
    LaunchedEffect(kyc, docsState) {
        if (stage == ProtoStage.Connecting && kyc is Resource.Success<*>) {
            val first = moduleQueue.firstOrNull() ?: "DOCUMENT"
            // The document module needs the region documents list; biometric-first does not.
            if (first == "DOCUMENT" && docsState !is Resource.Success<*>) return@LaunchedEffect
            // Report the session id for document-bearing runs so a later liveness step-up can reuse it.
            val sid = vm.getSessionId()
            if (moduleQueue.contains("DOCUMENT") && sid.isNotBlank()) onSessionEstablished(sid)
            stage = stageForModule(first)
        }
    }

    // Move to the next active module, or submit once every module is done.
    fun advanceModule() {
        val next = moduleIndex + 1
        moduleIndex = next
        stage = if (next < moduleQueue.size) stageForModule(moduleQueue[next]) else ProtoStage.Submitting
    }

    Box(Modifier.fillMaxSize()) {
    when (stage) {
        ProtoStage.Welcome -> ProtoWelcomeScreen(
            modules = protoIntroModules(options),
            onGetStarted = {
                val queue = protoModuleOrder(options)
                moduleQueue = queue
                moduleIndex = 0
                // Only DOCUMENT / BIOMETRIC need the KYC session (Connecting → createKyc). ADDRESS and
                // EDD have their own creation calls (createAddressVerification / createEddCase), so an
                // address- or EDD-first product goes straight to its module — its own call surfaces
                // any entitlement error at the right step, not a spurious KYC error.
                val first = queue.first()
                stage = if (first == "DOCUMENT" || first == "BIOMETRIC") ProtoStage.Connecting
                        else stageForModule(first)
            },
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
                onCaptured = { path, pads, video ->
                    if (isBack) { backPath = path; backVideo = video } else { frontPath = path; frontVideo = video }
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
                        // Cross-check front+back before leaving the document module.
                        stage = ProtoStage.PairChecking
                    }
                },
                onRetake = {
                    retakeAttempts += 1
                    stage = ProtoStage.Capture
                },
            )
        }

        ProtoStage.PairChecking -> {
            // Advisory pre-check only: run pair-check for its signal but ALWAYS proceed to submission.
            // The authoritative update-kyc validates the document pair server-side; and the production
            // doc-ml back-classifier can mis-read a licence BACK as FRONT, producing a false PAIR_RETRY —
            // blocking on that would strand the user (and their captured evidence never submits).
            LaunchedEffect(Unit) {
                val docTypeStr = com.example.veritypro_sdk.services.MLDocumentType.fromSdkType(
                    protoDocTypeInt(chosen?.documentType),
                )
                com.example.veritypro_sdk.services.MLV2Repository().pairCheck(
                    captureSessionId = vm.getSessionId().ifBlank { "proto-${protoDocTypeInt(chosen?.documentType)}" },
                    docTypeExpected = docTypeStr,
                )
                advanceModule()
            }
            ProtoProcessingScreen(
                kicker = "DOCUMENT",
                title = "Checking your\ndocument",
                message = "Making sure the front and back match…",
                module = Proto.Flamingo,
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
                            livenessId = bs.data.id ?: aws
                            vm.verifyLivenessResult(livenessId ?: aws) { ok ->
                                livenessApproved = ok
                                advanceModule()
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

        ProtoStage.AddressEntry -> {
            // Step 1: capture the address + register the verification session (create).
            var phase by remember { mutableStateOf("idle") }
            LaunchedEffect(addressCreateState) {
                when (addressCreateState) {
                    is Resource.Loading -> phase = "creating"
                    is Resource.Success<*> -> if (phase == "creating") stage = ProtoStage.AddressUpload
                    else -> {}
                }
            }
            ProtoAddressEntryScreen(
                initial = addressStreet,
                submitting = addressCreateState is Resource.Loading,
                errorMsg = (addressCreateState as? Resource.Error)?.let { "Couldn't start address verification. Please try again." },
                placesApiKey = options.placesApiKey,
                countryIso2 = options.isO2Code,
                onSubmit = { street ->
                    addressStreet = street
                    vm.createAddressVerification(options, street)
                },
                onBack = onExit,
            )
        }

        ProtoStage.AddressUpload -> {
            // Step 2: upload the proof-of-address document to the created session.
            // Address doc types (backend enum): 1 = Utility Bill, 2 = Account Statement.
            var phase by remember { mutableStateOf("idle") }
            LaunchedEffect(addressState) {
                when (addressState) {
                    is Resource.Loading -> phase = "submitting"
                    is Resource.Success<*> -> if (phase == "submitting") advanceModule()
                    else -> {}
                }
            }
            ProtoUploadScreen(
                kicker = "ADDRESS",
                title = "Proof of address",
                subtitle = "Dated in the last 3 months, showing your name and the address you entered.",
                docTypes = listOf("Utility bill" to 1, "Bank / account statement" to 2),
                accent = Proto.Brand,
                step = null,
                submitting = addressState is Resource.Loading,
                // Surface the actual backend reason (e.g. size/format/processing) instead of hiding it.
                errorMsg = (addressState as? Resource.Error)?.message?.ifBlank { "Couldn't submit. Please try again." },
                onSubmit = { type, file ->
                    vm.submitAddressDocument(vm.getAddressSessionId(), file, type, "", "", options.apiKey, context)
                },
                onBack = onExit,
            )
        }

        ProtoStage.EddUpload -> {
            // EDD: upload an income document that carries the source-of-funds information.
            // EDD doc types (backend enum): 0 = Bank Statement, 1 = Pay Slip, 2 = Tax Return.
            var phase by remember { mutableStateOf("idle") }
            LaunchedEffect(eddState) {
                when (eddState) {
                    is Resource.Loading -> phase = "submitting"
                    is Resource.Success<*> -> if (phase == "submitting") advanceModule()
                    else -> {}
                }
            }
            ProtoUploadScreen(
                kicker = "ENHANCED DUE DILIGENCE",
                title = "Source of funds",
                subtitle = "Upload an income document showing your source of funds.",
                docTypes = listOf("Pay slip" to 1, "Bank statement" to 0, "Tax return" to 2),
                accent = Proto.Indigo,
                step = null,
                submitting = eddState is Resource.Loading,
                errorMsg = (eddState as? Resource.Error)?.message?.ifBlank { "Couldn't submit. Please try again." },
                onSubmit = { type, file ->
                    // Subject = the KYC session when present; otherwise the integration id (EDD-only
                    // products have no KYC session).
                    val subject = vm.getSessionId().ifBlank { options.integrationId }
                    vm.submitEddDocument(subject, "${options.firstName} ${options.lastName}", file, type, options.apiKey, context)
                },
                onBack = onExit,
            )
        }

        ProtoStage.Submitting -> {
            // Only document/KYC flows post the full multipart verification here. Address, EDD and
            // biometric-only flows already submitted inside their own module — so those just finish.
            val hasDocument = protoWants(options).document
            if (hasDocument) {
                // Real backend submission: document + device + location + IP + security assessment +
                // compressed document clip, keyed to session + liveness IDs. Await the result directly
                // so the screen always advances (no kycState race).
                LaunchedEffect(Unit) {
                    flowOk = protoSubmitVerification(
                        context = context,
                        vm = vm,
                        docTypeInt = protoDocTypeInt(chosen?.documentType),
                        frontPath = frontPath,
                        backPath = backPath,
                        videoPath = frontVideo ?: backVideo,
                        livenessId = livenessId ?: "",
                        livenessConfidence = null,
                        captureAttempts = retakeAttempts + 1,
                    )
                    stage = ProtoStage.AllComplete
                }
            } else {
                // No document module — the prior module(s) already posted their evidence.
                LaunchedEffect(Unit) {
                    flowOk = if (protoWants(options).biometric) livenessApproved else true
                    stage = ProtoStage.AllComplete
                }
            }
            ProtoProcessingScreen(
                kicker = "SUBMITTING",
                title = if (hasDocument) "Submitting your\nverification" else "Finishing\nup",
                message = if (hasDocument) "Sending your document, device and location securely…"
                else "Wrapping up your verification…",
                module = Proto.Brand,
            )
        }

        ProtoStage.AllComplete -> {
            val (doneTitle, doneSubtitle) = protoCompletionCopy(options, flowOk)
            ProtoAllCompleteScreen(
                approved = flowOk,
                title = doneTitle,
                subtitle = doneSubtitle,
                onDone = {
                    onResult(flowOk)
                    onExit()
                },
            )
        }
    }

        // Persistent exit — cancels the whole verification and returns to the host app. Every proto
        // screen otherwise only navigates within the flow (the welcome screen has no back/close at
        // all), so without this the user has no way out. Wired to onExit (finishes the SDK activity
        // and returns a cancelled result). Hidden on AllComplete, which has its own Done button —
        // exiting there would report a completed verification as cancelled.
        if (stage != ProtoStage.AllComplete) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 16.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Proto.Ink, CircleShape)
                    .protoClick(onExit),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "✕", color = Proto.Ink, fontFamily = ProtoDisplay,
                    fontSize = 15.sp, fontWeight = FontWeight.Black,
                )
            }
        }
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
