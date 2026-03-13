package com.example.veritypro_sdk.ui.verification.address

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.veritypro_sdk.ui.verification.flow.ProcessExplainerScreen
import com.example.veritypro_sdk.utils.VerityMode

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
 * @param authToken Bearer token for the API.
 * @param apiBaseUrl Base URL for the VerityPro API (e.g. "https://api.skylinefare.com").
 * @param onFinish Called when the user finishes the flow. `true` = success, `false` = cancelled/failed.
 * @param onCancel Called when the user cancels out of the flow.
 */
@Composable
fun AddressVerificationScreen(
    authToken: String?,
    apiBaseUrl: String?,
    onFinish: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var stage by remember { mutableStateOf(AddressVerificationStage.INTRO) }
    var selectedDocType by remember { mutableStateOf<AddressDocType?>(null) }
    var capturedFileData by remember { mutableStateOf<ByteArray?>(null) }
    var capturedFileName by remember { mutableStateOf("") }

    when (stage) {
        AddressVerificationStage.INTRO -> {
            AddressIntroScreen(
                onCancel = onCancel,
                onGetStarted = { stage = AddressVerificationStage.PROCESS_EXPLAINER }
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
                // Guard: no data, go back to capture
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
                    authToken = authToken,
                    apiBaseUrl = apiBaseUrl,
                    onComplete = { success, error ->
                        if (success) {
                            stage = AddressVerificationStage.COMPLETE
                        } else {
                            // Upload failed - go back to capture for retry
                            capturedFileData = null
                            capturedFileName = ""
                            stage = AddressVerificationStage.CAPTURE
                        }
                    }
                )
            } else {
                // Guard: no data, go back to capture
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
