package com.example.veritypro_sdk.ui.verification.edd

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.veritypro_sdk.ui.verification.flow.ProcessExplainerScreen
import com.example.veritypro_sdk.utils.VerityMode
import java.io.File

/**
 * EDD verification flow stages.
 */
enum class EddVerificationStage {
    INTRO,
    PROCESS_EXPLAINER,
    DOC_TYPE,
    CAPTURE,
    UPLOAD,
    COMPLETE
}

/**
 * EDD verification orchestrator composable.
 *
 * Manages the flow: Intro -> Doc Type -> Capture -> Upload -> Complete.
 * Note: EDD does not have a preview step (unlike Address) because EDD
 * accepts PDFs and other file types, not just images.
 *
 * @param authToken Bearer token for the API.
 * @param apiBaseUrl Base URL for the VerityPro API (e.g. "https://api.skylinefare.com").
 * @param onFinish Called when the user finishes the flow. `true` = success, `false` = cancelled/failed.
 * @param onCancel Called when the user cancels out of the flow.
 */
@Composable
fun EddVerificationScreen(
    authToken: String?,
    apiBaseUrl: String?,
    onFinish: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var stage by remember { mutableStateOf(EddVerificationStage.INTRO) }
    var selectedDocType by remember { mutableStateOf<EddDocType?>(null) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var capturedFileName by remember { mutableStateOf("") }

    when (stage) {
        EddVerificationStage.INTRO -> {
            EddIntroScreen(
                onCancel = onCancel,
                onGetStarted = { stage = EddVerificationStage.PROCESS_EXPLAINER }
            )
        }

        EddVerificationStage.PROCESS_EXPLAINER -> {
            ProcessExplainerScreen(
                mode = VerityMode.EDD,
                onBack = { stage = EddVerificationStage.INTRO },
                onContinue = { stage = EddVerificationStage.DOC_TYPE }
            )
        }

        EddVerificationStage.DOC_TYPE -> {
            EddDocTypeScreen(
                onBack = { stage = EddVerificationStage.INTRO },
                onSelect = { docType ->
                    selectedDocType = docType
                    stage = EddVerificationStage.CAPTURE
                }
            )
        }

        EddVerificationStage.CAPTURE -> {
            EddCaptureScreen(
                docType = selectedDocType ?: EddDocType.SOURCE_OF_FUNDS,
                onBack = { stage = EddVerificationStage.DOC_TYPE },
                onFileCaptured = { file, filename ->
                    capturedFile = file
                    capturedFileName = filename
                    stage = EddVerificationStage.UPLOAD
                }
            )
        }

        EddVerificationStage.UPLOAD -> {
            val file = capturedFile
            if (file != null) {
                EddUploadScreen(
                    file = file,
                    fileName = capturedFileName,
                    docType = selectedDocType ?: EddDocType.SOURCE_OF_FUNDS,
                    authToken = authToken,
                    apiBaseUrl = apiBaseUrl,
                    onComplete = { success, error ->
                        if (success) {
                            stage = EddVerificationStage.COMPLETE
                        } else {
                            // Upload failed - go back to capture for retry
                            capturedFile = null
                            capturedFileName = ""
                            stage = EddVerificationStage.CAPTURE
                        }
                    }
                )
            } else {
                // Guard: no file, go back to capture
                stage = EddVerificationStage.CAPTURE
            }
        }

        EddVerificationStage.COMPLETE -> {
            EddCompleteScreen(
                onFinish = { onFinish(true) }
            )
        }
    }
}
