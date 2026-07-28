package com.example.veritypro_sdk.ui.verification

import LoadingScreen
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.NextAction
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.SessionStateResponse
import com.example.veritypro_sdk.ui.verification.address.AddressVerificationScreen
import com.example.veritypro_sdk.ui.verification.edd.EddVerificationScreen
import com.example.veritypro_sdk.utils.LivenessResult
import com.example.veritypro_sdk.utils.VerityOption
import kotlinx.coroutines.launch

/**
 * Server-driven verification screen.
 *
 * Flow:
 * 1. Creates or fetches a v2 session
 * 2. Reads [NextAction] from server to determine which UI to show
 * 3. On step completion, calls complete-step API
 * 4. Server returns next action or null (all done)
 */
@Composable
fun ServerDrivenScreen(
    options: VerityOption,
    onFinish: (LivenessResult) -> Unit,
    onCancel: () -> Unit
) {
    val repository = remember { ApiRepository() }
    val coroutineScope = rememberCoroutineScope()

    var sessionId by remember { mutableStateOf(options.serverSessionId) }
    var nextAction by remember { mutableStateOf<NextAction?>(null) }
    var completedSteps by remember { mutableStateOf<List<String>>(emptyList()) }
    var sessionStatus by remember { mutableStateOf("Created") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun fetchSessionState(sid: String) {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            when (val result = repository.getV2SessionState(sid, options.apiKey)) {
                is Resource.Success -> {
                    nextAction = result.data.nextAction
                    completedSteps = result.data.completedSteps
                    sessionStatus = result.data.status
                    isLoading = false
                }
                is Resource.Error -> {
                    errorMessage = result.message
                    isLoading = false
                }
                else -> {}
            }
        }
    }

    fun completeStep(stepName: String) {
        val sid = sessionId ?: return
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            when (val result = repository.completeV2Step(sid, stepName, options.apiKey)) {
                is Resource.Success -> {
                    nextAction = result.data.nextAction
                    completedSteps = result.data.completedSteps
                    sessionStatus = result.data.status
                    isLoading = false
                }
                is Resource.Error -> {
                    errorMessage = result.message
                    isLoading = false
                }
                else -> {}
            }
        }
    }

    // Initialize: create session or fetch existing state
    LaunchedEffect(Unit) {
        if (sessionId != null) {
            fetchSessionState(sessionId!!)
        } else {
            when (val result = repository.createV2Session(options)) {
                is Resource.Success -> {
                    sessionId = result.data.id
                    nextAction = result.data.nextAction
                    completedSteps = result.data.completedSteps
                    sessionStatus = result.data.status
                    isLoading = false
                }
                is Resource.Error -> {
                    errorMessage = result.message
                    isLoading = false
                }
                else -> {}
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                LoadingScreen(
                    text = "Setting up verification...",
                    poweredByText = "Powered by VERITYPRO"
                )
            }

            errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Something went wrong",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage ?: "",
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        val sid = sessionId
                        if (sid != null) fetchSessionState(sid)
                        else {
                            // Retry session creation
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                when (val result = repository.createV2Session(options)) {
                                    is Resource.Success -> {
                                        sessionId = result.data.id
                                        nextAction = result.data.nextAction
                                        completedSteps = result.data.completedSteps
                                        sessionStatus = result.data.status
                                        isLoading = false
                                    }
                                    is Resource.Error -> {
                                        errorMessage = result.message
                                        isLoading = false
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }) {
                        Text("Retry")
                    }
                }
            }

            nextAction != null -> {
                val action = nextAction!!
                Log.d("ServerDriven", "Rendering step: ${action.step} type: ${action.type}")

                // Render appropriate UI based on server's nextAction.type.
                // Reuse existing capture screens; only orchestration is server-driven.
                when (action.type) {
                    "SDK_CAPTURE", "DOCUMENT_CAPTURE" -> {
                        // Reuse existing VerificationScreen for document capture.
                        // Configure options with the engine session ID from the server.
                        val docOptions = remember(action) {
                            options.copy(
                                requiredModules = listOf("DOCUMENT"),
                                serverSessionId = null // Force v1 capture flow for document
                            )
                        }
                        VerificationScreen(
                            options = docOptions,
                            onFinish = { result ->
                                if (result.success) {
                                    completeStep(action.step)
                                } else {
                                    errorMessage = result.error ?: "Document capture failed"
                                }
                            },
                            onCancel = onCancel
                        )
                    }

                    "LIVENESS", "LIVENESS_CHECK" -> {
                        // Reuse existing VerificationScreen for liveness check.
                        val livenessOptions = remember(action) {
                            options.copy(
                                requiredModules = listOf("BIOMETRIC"),
                                previousEngineSessionId = action.engineSessionId,
                                serverSessionId = null
                            )
                        }
                        VerificationScreen(
                            options = livenessOptions,
                            onFinish = { result ->
                                if (result.success) {
                                    completeStep(action.step)
                                } else {
                                    errorMessage = result.error ?: "Liveness check failed"
                                }
                            },
                            onCancel = onCancel
                        )
                    }

                    "ADDRESS_UPLOAD" -> {
                        AddressVerificationScreen(
                            options = options,
                            onFinish = { success ->
                                if (success) {
                                    completeStep(action.step)
                                } else {
                                    errorMessage = "Address verification failed"
                                }
                            },
                            onCancel = onCancel
                        )
                    }

                    "EDD_UPLOAD" -> {
                        EddVerificationScreen(
                            options = options,
                            onFinish = { success, _ ->
                                if (success) {
                                    completeStep(action.step)
                                } else {
                                    errorMessage = "EDD verification failed"
                                }
                            },
                            onCancel = onCancel
                        )
                    }

                    else -> {
                        Text(
                            text = "Unknown step type: ${action.type}",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            else -> {
                // All steps completed
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = Color(0xFF4A93FF)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("✓", fontSize = 32.sp, color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Thank you",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "You have successfully completed all required verification steps.",
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(30.dp))
                    Button(onClick = {
                        onFinish(
                            LivenessResult(
                                success = true,
                                sessionToken = null,
                                error = null,
                                sessionId = sessionId
                            )
                        )
                    }) {
                        Text("Finish")
                    }
                }
            }
        }

        // Cancel button (top-left)
        IconButton(
            onClick = { onCancel() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
