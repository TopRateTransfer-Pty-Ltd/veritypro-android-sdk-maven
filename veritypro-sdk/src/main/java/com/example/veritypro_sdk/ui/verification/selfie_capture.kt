package com.example.veritypro_sdk.ui.verification


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.ui.liveness.model.FaceLivenessDetectionException
import com.amplifyframework.ui.liveness.ui.FaceLivenessDetector
import com.amplifyframework.ui.liveness.ui.LivenessColorScheme
import com.example.veritypro_sdk.services.BeginLivenessCredentials
import com.example.veritypro_sdk.services.LivenessCredentialsProvider
import com.amplifyframework.auth.AWSCredentialsProvider
import com.example.veritypro_sdk.utils.CameraUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay

import com.amplifyframework.core.Action
import com.amplifyframework.core.Consumer


// Animated face scan visualization with pulsing rings
@Composable
private fun FaceScanAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")

    // Three concentric ring pulses at staggered phases
    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ), label = "ring1"
    )
    val ring1Scale by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ), label = "ring1s"
    )

    val ring2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 800, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ), label = "ring2"
    )
    val ring2Scale by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 800, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ), label = "ring2s"
    )

    val ring3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 1600, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ), label = "ring3"
    )
    val ring3Scale by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 1600, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ), label = "ring3s"
    )

    // Slow rotation for the dashed scanning arc
    val scanRotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "scanRot"
    )

    val accentBlue = Color(0xFF3B82F6)
    val accentCyan = Color(0xFF06B6D4)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(ScaleUtil.scaleWidth(200.dp))) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f

            // Pulsing rings
            listOf(
                ring1Scale to ring1Alpha,
                ring2Scale to ring2Alpha,
                ring3Scale to ring3Alpha
            ).forEach { (scale, alpha) ->
                drawCircle(
                    color = accentBlue.copy(alpha = alpha * 0.5f),
                    radius = maxRadius * scale,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Static inner circle (face area)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentBlue.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxRadius * 0.5f
                ),
                radius = maxRadius * 0.5f,
                center = center
            )

            // Dashed rotating scan arc
            drawArc(
                color = accentCyan,
                startAngle = scanRotation,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                topLeft = Offset(center.x - maxRadius * 0.55f, center.y - maxRadius * 0.55f),
                size = androidx.compose.ui.geometry.Size(maxRadius * 1.1f, maxRadius * 1.1f)
            )
        }

        // Face icon in center
        Box(
            modifier = Modifier
                .size(ScaleUtil.scaleWidth(72.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(ScaleUtil.scaleWidth(40.dp))
            )
        }
    }
}

// Single step row with animated appearance
@Composable
private fun PreparationStep(
    icon: @Composable () -> Unit,
    text: String,
    visible: Boolean,
    delayMs: Int = 0
) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(delayMs.toLong())
            show = true
        }
    }

    AnimatedVisibility(
        visible = show,
        enter = fadeIn(tween(400)) + slideInHorizontally(tween(400)) { -40 }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ScaleUtil.scaleHeight(6.dp))
        ) {
            Box(
                modifier = Modifier
                    .size(ScaleUtil.scaleWidth(32.dp))
                    .background(Color(0xFF1E3A5F), RoundedCornerShape(ScaleUtil.scaleWidth(8.dp))),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Spacer(modifier = Modifier.width(ScaleUtil.scaleWidth(12.dp)))
            Text(
                text = text,
                color = Color(0xFFD1D5DB),
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                fontWeight = FontWeight.W400
            )
        }
    }
}


@Composable
fun SelfieCaptureScreen(
    sessionIdFromCreateKyc: String?,
    awsSessionId: String?,
    livenessId: String?,
    region: String = "us-east-1",
    credentials: BeginLivenessCredentials? = null,
    viewModel: VerityProViewModel,
    onBack: () -> Unit,
    onLivenessComplete: (livenessId: String?) -> Unit,
) {
    val context = LocalContext.current

    var hasPermission by remember { mutableStateOf(CameraUtils.hasCameraPermissions(context)) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    val cameraPermissionLauncher = CameraUtils.createCameraLauncher { granted -> hasPermission = granted }

    var isProcessingServerResult by remember { mutableStateOf(false) }
    var error: FaceLivenessDetectionException? by remember { mutableStateOf(null) }

    // Memoize credentials provider to avoid unnecessary recompositions of FaceLivenessDetector
    val credentialsProvider = remember(credentials) {
        credentials?.let { LivenessCredentialsProvider(it) }
    }

    var started by rememberSaveable { mutableStateOf(false) }

    // request permission once
    LaunchedEffect(key1 = hasPermission) {
        if (!hasPermission && !askedPermission) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            askedPermission = true
        }
    }


    Box(
        modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF0F1724))) {

            when {
                !hasPermission -> {
                    PermissionDeniedScreen(context = context) { granted -> hasPermission = granted }
                }

                awsSessionId.isNullOrBlank() -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                        ) {
                        Text("Preparing liveness...", color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            viewModel.resetLivenessState()
                            sessionIdFromCreateKyc?.let { viewModel.startBeginLiveness(it, forceRetry = true) }
                        }) {
                            Text("Retry")
                        }
                    }
                }

                error != null -> {
                    // Reset processing state via LaunchedEffect to avoid side effects during composition
                    LaunchedEffect(error) {
                        isProcessingServerResult = false
                    }
                    val isCameraError = error?.message?.contains("camera", ignoreCase = true) == true
                    val displayMessage = if (isCameraError) {
                        "Camera could not start. Please ensure no other apps are using the camera, then try again."
                    } else {
                        error?.message ?: "Liveness verification failed"
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp)
                        ) {
                        Text(
                            displayMessage,
                            color = Color(0xFFEF4444),
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                error = null
                                started = false
                                isProcessingServerResult = false
                                viewModel.resetLivenessState()
                                sessionIdFromCreateKyc?.let { viewModel.startBeginLiveness(it, forceRetry = true) }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                            shape = RoundedCornerShape(24.dp)
                        ) { Text("Retry") }
                    }
                }

                started -> {
                        // Custom branded color scheme: start from AWS defaults, override surface colors
                        val brandedScheme = LivenessColorScheme.default().copy(
                            background = Color(0xFF0F1724),       // App dark background
                            surface = Color(0xFF1A2744),          // Elevated surface
                            primary = Color(0xFF3B82F6),          // Brand blue
                            onPrimary = Color.White,
                            secondary = Color(0xFF10B981),        // Success green
                            onBackground = Color.White,
                            onSurface = Color.White,
                            error = Color(0xFFEF4444)             // Error red
                        )
                        MaterialTheme(colorScheme = brandedScheme) {
                            FaceLivenessDetector(
                                sessionId = awsSessionId,
                                region = region,
                                disableStartView = true,
                                credentialsProvider = credentialsProvider,

                                onComplete = Action {
                                    isProcessingServerResult = true
                                    onLivenessComplete(livenessId)
                                },
                                onError = Consumer { ex ->
                                    error = ex
                                }
                            )
                        }
                    }

                else -> {
                    // --- Liveness pre-start screen ---
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                            .padding(
                                top = ScaleUtil.scaleHeight(70.dp),
                                bottom = ScaleUtil.scaleHeight(24.dp)
                            )
                    ) {
                        // Animated face scan visualization
                        FaceScanAnimation(
                            modifier = Modifier.height(ScaleUtil.scaleHeight(200.dp))
                        )

                        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

                        // Title
                        Text(
                            text = "Liveness Verification",
                            color = Color.White,
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(22.dp).toSp() },
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

                        // Subtitle
                        Text(
                            text = "We need to verify you're a real person.\nThis is a quick and secure process.",
                            color = Color(0xFF9CA3AF),
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                            fontWeight = FontWeight.W400,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(16.dp))
                        )

                        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(28.dp)))

                        // Preparation steps with staggered animation
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Color(0xFF1A2744),
                                    RoundedCornerShape(ScaleUtil.scaleWidth(12.dp))
                                )
                                .padding(ScaleUtil.scaleWidth(16.dp))
                        ) {
                            PreparationStep(
                                icon = {
                                    Icon(
                                        Icons.Default.Person, contentDescription = null,
                                        tint = Color(0xFF3B82F6),
                                        modifier = Modifier.size(ScaleUtil.scaleWidth(18.dp))
                                    )
                                },
                                text = "Face detection will start automatically",
                                visible = true,
                                delayMs = 200
                            )
                            PreparationStep(
                                icon = {
                                    Icon(
                                        Icons.Default.Check, contentDescription = null,
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(ScaleUtil.scaleWidth(18.dp))
                                    )
                                },
                                text = "Quick verification, only a few seconds",
                                visible = true,
                                delayMs = 500
                            )
                            PreparationStep(
                                icon = {
                                    Icon(
                                        Icons.Default.Lock, contentDescription = null,
                                        tint = Color(0xFF8B5CF6),
                                        modifier = Modifier.size(ScaleUtil.scaleWidth(18.dp))
                                    )
                                },
                                text = "Secure and encrypted video stream",
                                visible = true,
                                delayMs = 800
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Start button with gradient
                        Button(
                            onClick = { started = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent
                            ),
                            contentPadding = PaddingValues(),
                            shape = RoundedCornerShape(ScaleUtil.scaleWidth(14.dp)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ScaleUtil.scaleHeight(56.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                Color(0xFF3B82F6),
                                                Color(0xFF8B5CF6)
                                            )
                                        ),
                                        shape = RoundedCornerShape(ScaleUtil.scaleWidth(14.dp))
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Start Video Check",
                                    color = Color.White,
                                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

                        // Security note
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF6B7280),
                                modifier = Modifier.size(ScaleUtil.scaleWidth(14.dp))
                            )
                            Spacer(modifier = Modifier.width(ScaleUtil.scaleWidth(6.dp)))
                            Text(
                                text = "End-to-end encrypted",
                                color = Color(0xFF6B7280),
                                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                                fontWeight = FontWeight.W400
                            )
                        }

                        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))
                    }
                }
            }

        // Processing overlay (shown on top of FaceLivenessDetector when done)
        if (isProcessingServerResult) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(ScaleUtil.scaleWidth(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF3B82F6))
                    Spacer(Modifier.height(8.dp))
                    Text("Processing result...", color = Color.White)
                }
            }
        }

        // Floating close button — drawn LAST so it's always on top and tappable
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 8.dp)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
        ) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Back", tint = Color.White)
        }
    }
}
