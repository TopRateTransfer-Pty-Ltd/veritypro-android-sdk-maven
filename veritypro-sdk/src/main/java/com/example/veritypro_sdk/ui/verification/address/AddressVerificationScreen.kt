package com.example.veritypro_sdk.ui.verification.address

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.veritypro_sdk.ui.verification.flow.ProcessExplainerScreen
import com.example.veritypro_sdk.utils.VerityMode
import java.io.File

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
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var capturedFile by remember { mutableStateOf<File?>(null) }

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
                onImageCaptured = { bitmap, file ->
                    capturedBitmap = bitmap
                    capturedFile = file
                    stage = AddressVerificationStage.PREVIEW
                }
            )
        }

        AddressVerificationStage.PREVIEW -> {
            val bitmap = capturedBitmap
            if (bitmap != null) {
                AddressPreviewScreen(
                    bitmap = bitmap,
                    docType = selectedDocType ?: AddressDocType.UTILITY_BILL,
                    onRetake = {
                        capturedBitmap = null
                        capturedFile = null
                        stage = AddressVerificationStage.CAPTURE
                    },
                    onConfirm = { stage = AddressVerificationStage.UPLOAD }
                )
            } else {
                // Guard: no bitmap, go back to capture
                stage = AddressVerificationStage.CAPTURE
            }
        }

        AddressVerificationStage.UPLOAD -> {
            val file = capturedFile
            if (file != null) {
                AddressUploadScreen(
                    file = file,
                    docType = selectedDocType ?: AddressDocType.UTILITY_BILL,
                    authToken = authToken,
                    apiBaseUrl = apiBaseUrl,
                    onComplete = { success, error ->
                        if (success) {
                            stage = AddressVerificationStage.COMPLETE
                        } else {
                            // Upload failed - go back to capture for retry
                            capturedBitmap = null
                            capturedFile = null
                            stage = AddressVerificationStage.CAPTURE
                        }
                    }
                )
            } else {
                // Guard: no file, go back to capture
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
