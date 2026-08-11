package com.example.veritypro_sdk.ui.verification.edd

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.veritypro_sdk.ui.verification.flow.ProcessExplainerScreen
import com.example.veritypro_sdk.utils.VerityMode
import com.example.veritypro_sdk.utils.VerityOption
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
 *
 * When [options] is provided, the EDD case is created via the KYC Integration API
 * using vendorData as subjectId to link it to the same user session.
 *
 * @param options VerityOption containing apiKey, user info (vendorData links sessions).
 * @param authToken Bearer token (legacy, only used if options is null).
 * @param apiBaseUrl Base URL (legacy, only used if options is null).
 * @param onFinish Called when the user finishes the flow. `true` = success, `false` = cancelled/failed.
 * @param onCancel Called when the user cancels out of the flow.
 */
@Composable
fun EddVerificationScreen(
    options: VerityOption? = null,
    authToken: String? = null,
    apiBaseUrl: String? = null,
    onFinish: (success: Boolean, eddCaseId: String?) -> Unit,
    onCancel: () -> Unit
) {
    var stage by remember { mutableStateOf(EddVerificationStage.INTRO) }
    var selectedDocType by remember { mutableStateOf<EddDocType?>(null) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var capturedFileName by remember { mutableStateOf("") }
    var completedCaseId by remember { mutableStateOf<String?>(null) }

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
                    options = options,
                    onComplete = { success, error, caseId ->
                        if (success) {
                            completedCaseId = caseId
                            stage = EddVerificationStage.COMPLETE
                        } else {
                            capturedFile = null
                            capturedFileName = ""
                            stage = EddVerificationStage.CAPTURE
                        }
                    }
                )
            } else {
                stage = EddVerificationStage.CAPTURE
            }
        }

        EddVerificationStage.COMPLETE -> {
            EddCompleteScreen(
                onFinish = { onFinish(true, completedCaseId) }
            )
        }
    }
}
