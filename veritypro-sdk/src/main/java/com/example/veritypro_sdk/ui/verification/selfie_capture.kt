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
import com.example.veritypro_sdk.utils.CameraUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*

import com.amplifyframework.core.Action
import com.amplifyframework.core.Consumer



@Composable
fun SelfieCaptureScreen(
    sessionIdFromCreateKyc: String?,
    awsSessionId: String?,
    livenessId: String?,
    viewModel: VerityProViewModel,
    onBack: () -> Unit,
    onLivenessComplete: (livenessId: String?) -> Unit,
) {
    val context = LocalContext.current
    //val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(CameraUtils.hasCameraPermissions(context)) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    val cameraPermissionLauncher = CameraUtils.createCameraLauncher { granted -> hasPermission = granted }

    var isCompleted by remember { mutableStateOf(false) }
    var isProcessingServerResult by remember { mutableStateOf(false) }
    var error: FaceLivenessDetectionException? by remember { mutableStateOf(null) }

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
        // Top header/back
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
        ) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Back", tint = Color.White)
        }



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
                        Button(onClick = { sessionIdFromCreateKyc?.let { viewModel.startBeginLiveness(it) } }) {
                            Text("Retry")
                        }
                    }
                }

                error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                        ) {
                        Text(
                            "Error: ${error?.message ?: "Liveness failed"}",
                            color = Color.Red,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            sessionIdFromCreateKyc?.let { viewModel.startBeginLiveness(it) }
                            error = null
                        }) { Text("Retry") }
                    }
                }

                started -> {
                        MaterialTheme(colorScheme = LivenessColorScheme.default()) {
                            FaceLivenessDetector(
                                sessionId = awsSessionId,
                                region = "us-east-1",
                                disableStartView = true,
                                credentialsProvider = null,

                                onComplete = Action {
                                    isCompleted = true
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                            .align(Alignment.TopCenter)
                            .padding(top = ScaleUtil.scaleHeight(80.dp))
                    ) {
                        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(20.dp)))

                        Text(
                            text = "Position your face in the guide and follow the instructions on-screen.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(8.dp))
                        )
                        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(200.dp)))

                        Button(
                            onClick = { started = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2B7AEF)
                            ),
                            shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ScaleUtil.scaleHeight(58.dp))
//                            modifier = Modifier
//                                .height(48.dp)
//                                .widthIn(min = 200.dp)
                        ) {
                            Text(
                                "Start video check",
                                color = Color(0xFFFFFFFF),

                            )
                        }
                        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))

                        Text(
                            "The liveness video will stream securely. Keep your face in the guide.",
                            color = Color(0xFF9CA3AF),
                            textAlign = TextAlign.Center
                        )
                        //Spacer(1f)
                    }
                }
            }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(ScaleUtil.scaleWidth(16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isProcessingServerResult) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Processing result...", color = Color.White)
            } else if (error == null && !started) {
                Text(
                    text = "Tap Start to begin the liveness check.",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))


            }
        }
    }
}





