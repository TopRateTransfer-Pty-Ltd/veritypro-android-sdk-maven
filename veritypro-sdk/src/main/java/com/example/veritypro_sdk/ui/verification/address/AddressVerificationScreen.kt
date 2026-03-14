package com.example.veritypro_sdk.ui.verification.address

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.ui.verification.flow.ProcessExplainerScreen
import com.example.veritypro_sdk.utils.VerityMode
import com.example.veritypro_sdk.utils.VerityOption
import kotlinx.coroutines.launch

/**
 * Address verification flow stages.
 */
enum class AddressVerificationStage {
    INTRO,
    PROCESS_EXPLAINER,
    DOC_TYPE,
    CAPTURE,
    PREVIEW,
    UPLOAD,
    COMPLETE
}

/**
 * Address verification orchestrator composable.
 *
 * Manages the flow: Intro -> Doc Type -> Capture -> Preview -> Upload -> Complete.
 *
 * When [options] is provided, a VerityPro address session is created via the
 * KYC Integration API. The returned sessionId is used for document submission.
 *
 * When launched standalone (ADDRESS mode), the caller must provide [options].
 * When launched from CombinedFlowScreen, [options] is passed through from the parent.
 *
 * @param options VerityOption containing apiKey, integrationId, user info (vendorData links sessions).
 * @param authToken Bearer token (legacy, only used if options is null).
 * @param apiBaseUrl Base URL (legacy, only used if options is null).
 * @param onFinish Called when the user finishes the flow. `true` = success, `false` = cancelled/failed.
 * @param onCancel Called when the user cancels out of the flow.
 */
@Composable
fun AddressVerificationScreen(
    options: VerityOption? = null,
    authToken: String? = null,
    apiBaseUrl: String? = null,
    onFinish: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var stage by remember { mutableStateOf(AddressVerificationStage.INTRO) }
    var selectedDocType by remember { mutableStateOf<AddressDocType?>(null) }
    var capturedFileData by remember { mutableStateOf<ByteArray?>(null) }
    var capturedFileName by remember { mutableStateOf("") }

    // Session state from VerityPro KYC Integration API
    var addressSessionId by remember { mutableStateOf<String?>(null) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var isCreatingSession by remember { mutableStateOf(false) }

    val repository = remember { ApiRepository() }
    val coroutineScope = rememberCoroutineScope()

    // Create address verification session when options are available
    fun createAddressSession() {
        val opts = options ?: return
        isCreatingSession = true
        sessionError = null
        coroutineScope.launch {
            val result = repository.createAddressVerification(opts)
            isCreatingSession = false
            when (result) {
                is Resource.Success -> {
                    addressSessionId = result.data.sessionId
                    Log.d("AddressVerification", "Session created: ${result.data.sessionId}")
                }
                is Resource.Error -> {
                    sessionError = result.message
                    Log.e("AddressVerification", "Failed to create session: ${result.message}")
                }
                else -> {
                    sessionError = "Unexpected response"
                }
            }
        }
    }

    when (stage) {
        AddressVerificationStage.INTRO -> {
            AddressIntroScreen(
                onCancel = onCancel,
                onGetStarted = {
                    // Create session when user starts the flow
                    if (options != null && addressSessionId == null && !isCreatingSession) {
                        createAddressSession()
                    }
                    stage = AddressVerificationStage.PROCESS_EXPLAINER
                }
            )
        }

        AddressVerificationStage.PROCESS_EXPLAINER -> {
            ProcessExplainerScreen(
                mode = VerityMode.ADDRESS,
                onBack = { stage = AddressVerificationStage.INTRO },
                onContinue = { stage = AddressVerificationStage.DOC_TYPE }
            )
        }

        AddressVerificationStage.DOC_TYPE -> {
            AddressDocTypeScreen(
                onBack = { stage = AddressVerificationStage.INTRO },
                onSelect = { docType ->
                    selectedDocType = docType
                    stage = AddressVerificationStage.CAPTURE
                }
            )
        }

        AddressVerificationStage.CAPTURE -> {
            AddressCaptureScreen(
                docType = selectedDocType ?: AddressDocType.UTILITY_BILL,
                onBack = { stage = AddressVerificationStage.DOC_TYPE },
                onFileCaptured = { bytes, filename ->
                    capturedFileData = bytes
                    capturedFileName = filename
                    stage = AddressVerificationStage.PREVIEW
                }
            )
        }

        AddressVerificationStage.PREVIEW -> {
            val data = capturedFileData
            if (data != null) {
                AddressPreviewScreen(
                    fileData = data,
                    fileName = capturedFileName,
                    docType = selectedDocType ?: AddressDocType.UTILITY_BILL,
                    onReselect = {
                        capturedFileData = null
                        capturedFileName = ""
                        stage = AddressVerificationStage.CAPTURE
                    },
                    onConfirm = { stage = AddressVerificationStage.UPLOAD }
                )
            } else {
                stage = AddressVerificationStage.CAPTURE
            }
        }

        AddressVerificationStage.UPLOAD -> {
            val data = capturedFileData
            if (data != null) {
                AddressUploadScreen(
                    fileData = data,
                    fileName = capturedFileName,
                    docType = selectedDocType ?: AddressDocType.UTILITY_BILL,
                    options = options,
                    sessionId = addressSessionId,
                    onComplete = { success, error ->
                        if (success) {
                            stage = AddressVerificationStage.COMPLETE
                        } else {
                            capturedFileData = null
                            capturedFileName = ""
                            stage = AddressVerificationStage.CAPTURE
                        }
                    }
                )
            } else {
                stage = AddressVerificationStage.CAPTURE
            }
        }

        AddressVerificationStage.COMPLETE -> {
            AddressCompleteScreen(
                onFinish = { onFinish(true) }
            )
        }
    }
}
