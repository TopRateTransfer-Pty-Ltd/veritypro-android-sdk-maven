package com.example.veritypro_sdk.ui.verification.flow

import ScaleUtil
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.ui.theme.customColors
import com.example.veritypro_sdk.ui.verification.VerificationScreen
import com.example.veritypro_sdk.ui.verification.address.AddressVerificationScreen
import com.example.veritypro_sdk.ui.verification.edd.EddVerificationScreen
import com.example.veritypro_sdk.utils.VerityMode
import com.example.veritypro_sdk.utils.VerityOption

/**
 * Combined flow stages.
 * Runs: launch -> document -> biometric -> biometricToAddress -> address -> addressToEdd -> edd -> allComplete
 */
enum class CombinedFlowStage {
    LAUNCH,
    DOCUMENT,
    DOCUMENT_TO_BIOMETRIC,
    BIOMETRIC,
    BIOMETRIC_TO_ADDRESS,
    ADDRESS,
    ADDRESS_TO_EDD,
    EDD,
    ALL_COMPLETE
}

/**
 * Combined verification flow orchestrator.
 *
 * Manages a multi-step verification: document -> biometric -> address -> EDD.
 * Steps are controlled by `requestedSteps` list (e.g. ["document", "biometric", "address", "edd"]).
 *
 * Session linkage: All steps share the same [VerityOption] which contains `vendorData`
 * (the external user ID). The backend uses vendorData to link all verification sessions
 * for the same user — document, biometric, address, and EDD all reference the same user.
 *
 * - Document + Biometric: Share a KYC session (created via createKyc with vendorData)
 * - Address: Creates its own address session (linked via vendorData + integrationId)
 * - EDD: Creates its own EDD case (linked via vendorData as subjectId)
 *
 * @param options VerityOption with apiKey, integrationId, user info. Required for all steps.
 * @param themeMode Light or dark theme.
 * @param authToken Legacy bearer token (unused when options is provided).
 * @param apiBaseUrl Legacy base URL (unused when options is provided).
 * @param requestedSteps List of step identifiers: "document", "biometric", "address", "edd".
 * @param onFinish Called with result map when all steps complete.
 * @param onCancel Called when the user cancels the flow.
 */
@Composable
fun CombinedFlowScreen(
    options: VerityOption? = null,
    themeMode: ThemeMode = ThemeMode.LIGHT,
    authToken: String?,
    apiBaseUrl: String?,
    requestedSteps: List<String>,
    onFinish: (Map<String, Any>) -> Unit,
    onCancel: () -> Unit
) {
    var stage by remember { mutableStateOf(CombinedFlowStage.LAUNCH) }
    val completedSteps = remember { mutableStateListOf<String>() }

    val hasDocument = requestedSteps.contains("document")
    val hasBiometric = requestedSteps.contains("biometric")
    val hasAddress = requestedSteps.contains("address")
    val hasEdd = requestedSteps.contains("edd")

    fun buildFlowSteps(): List<FlowStep> {
        val steps = mutableListOf<FlowStep>()
        if (hasDocument) {
            steps.add(
                FlowStep(
                    title = "Document Verification",
                    subtitle = statusText("document", completedSteps, stage),
                    status = stepStatus("document", completedSteps, stage)
                )
            )
        }
        if (hasBiometric) {
            steps.add(
                FlowStep(
                    title = "Identity Verification",
                    subtitle = statusText("biometric", completedSteps, stage),
                    status = stepStatus("biometric", completedSteps, stage)
                )
            )
        }
        if (hasAddress) {
            steps.add(
                FlowStep(
                    title = "Address Verification",
                    subtitle = statusText("address", completedSteps, stage),
                    status = stepStatus("address", completedSteps, stage)
                )
            )
        }
        if (hasEdd) {
            steps.add(
                FlowStep(
                    title = "EDD Documents",
                    subtitle = statusText("edd", completedSteps, stage),
                    status = stepStatus("edd", completedSteps, stage)
                )
            )
        }
        return steps
    }

    fun advanceToFirstStep() {
        stage = when {
            hasDocument -> CombinedFlowStage.DOCUMENT
            hasBiometric -> CombinedFlowStage.BIOMETRIC
            hasAddress -> CombinedFlowStage.ADDRESS
            hasEdd -> CombinedFlowStage.EDD
            else -> CombinedFlowStage.ALL_COMPLETE
        }
    }

    fun advanceAfterDocument() {
        completedSteps.add("document")
        stage = when {
            hasBiometric -> CombinedFlowStage.DOCUMENT_TO_BIOMETRIC
            hasAddress -> CombinedFlowStage.ADDRESS
            hasEdd -> CombinedFlowStage.EDD
            else -> CombinedFlowStage.ALL_COMPLETE
        }
    }

    fun advanceAfterBiometric() {
        completedSteps.add("biometric")
        stage = when {
            hasAddress -> CombinedFlowStage.BIOMETRIC_TO_ADDRESS
            hasEdd -> CombinedFlowStage.EDD
            else -> CombinedFlowStage.ALL_COMPLETE
        }
    }

    fun advanceAfterAddress() {
        stage = when {
            hasEdd -> CombinedFlowStage.ADDRESS_TO_EDD
            else -> CombinedFlowStage.ALL_COMPLETE
        }
    }

    when (stage) {
        CombinedFlowStage.LAUNCH -> {
            FlowLaunchScreen(
                steps = buildFlowSteps(),
                onCancel = onCancel,
                onStart = { advanceToFirstStep() }
            )
        }

        CombinedFlowStage.DOCUMENT -> {
            if (options != null) {
                VerificationScreen(
                    options = options.copy(mode = VerityMode.DOCUMENT.name),
                    onFinish = { result ->
                        advanceAfterDocument()
                    },
                    onCancel = onCancel,
                    themeMode = themeMode
                )
            }
        }

        CombinedFlowStage.DOCUMENT_TO_BIOMETRIC -> {
            TransitionScreen(
                flowSteps = buildFlowSteps(),
                completedTitle = "Document Captured",
                nextTitle = "Identity Verification",
                onContinue = { stage = CombinedFlowStage.BIOMETRIC }
            )
        }

        CombinedFlowStage.BIOMETRIC -> {
            if (options != null) {
                VerificationScreen(
                    options = options.copy(mode = VerityMode.BIOMETRIC.name),
                    onFinish = { result ->
                        advanceAfterBiometric()
                    },
                    onCancel = onCancel,
                    themeMode = themeMode
                )
            }
        }

        CombinedFlowStage.BIOMETRIC_TO_ADDRESS -> {
            TransitionScreen(
                flowSteps = buildFlowSteps(),
                completedTitle = "Identity Verified",
                nextTitle = "Address Verification",
                onContinue = { stage = CombinedFlowStage.ADDRESS }
            )
        }

        CombinedFlowStage.ADDRESS -> {
            // Pass options so address creates its own session linked via vendorData
            AddressVerificationScreen(
                options = options,
                onFinish = { success ->
                    if (success) {
                        completedSteps.add("address")
                        advanceAfterAddress()
                    }
                },
                onCancel = onCancel
            )
        }

        CombinedFlowStage.ADDRESS_TO_EDD -> {
            TransitionScreen(
                flowSteps = buildFlowSteps(),
                completedTitle = "Address Verified",
                nextTitle = "EDD Documents",
                onContinue = { stage = CombinedFlowStage.EDD }
            )
        }

        CombinedFlowStage.EDD -> {
            // Pass options so EDD creates case with vendorData as subjectId
            EddVerificationScreen(
                options = options,
                onFinish = { success ->
                    if (success) completedSteps.add("edd")
                    stage = CombinedFlowStage.ALL_COMPLETE
                },
                onCancel = onCancel
            )
        }

        CombinedFlowStage.ALL_COMPLETE -> {
            AllCompleteScreen(
                flowSteps = buildFlowSteps(),
                onFinish = {
                    onFinish(
                        mapOf(
                            "success" to true,
                            "completedSteps" to completedSteps.toList()
                        )
                    )
                }
            )
        }
    }
}

// MARK: - Transition screen between steps

@Composable
private fun TransitionScreen(
    flowSteps: List<FlowStep>,
    completedTitle: String,
    nextTitle: String,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.background)
            .padding(
                horizontal = ScaleUtil.scaleWidth(24.dp),
                vertical = ScaleUtil.scaleHeight(40.dp)
            )
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(60.dp)))

        Text(
            text = "VERITYPRO",
            fontWeight = FontWeight.W700,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(24.dp).toSp() },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .background(
                    color = Color(0xFF4A93FF),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Completed",
                modifier = Modifier.size(24.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

        Text(
            text = completedTitle,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
            fontWeight = FontWeight.W700,
            color = Color(0xFF4A93FF)
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))

        FlowTimelineView(steps = flowSteps)

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))

        Text(
            text = "Next: $nextTitle",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            fontWeight = FontWeight.W500,
            color = MaterialTheme.customColors.subTitle,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(20.dp)))

        Button(
            onClick = onContinue,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2B7AEF)
            ),
            shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
            modifier = Modifier
                .fillMaxWidth()
                .height(ScaleUtil.scaleHeight(48.dp))
        ) {
            Text(
                text = "Continue",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                fontWeight = FontWeight.W600,
                color = MaterialTheme.customColors.content
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Powered by ",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                fontWeight = FontWeight.W400,
                color = MaterialTheme.customColors.powered
            )
            Text(
                text = " VERITYPRO",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                fontWeight = FontWeight.W600,
                color = MaterialTheme.customColors.powered
            )
        }
    }
}

// MARK: - All complete screen

@Composable
private fun AllCompleteScreen(
    flowSteps: List<FlowStep>,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.background)
            .padding(
                horizontal = ScaleUtil.scaleWidth(24.dp),
                vertical = ScaleUtil.scaleHeight(40.dp)
            )
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(60.dp)))

        Text(
            text = "VERITYPRO",
            fontWeight = FontWeight.W700,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(24.dp).toSp() },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(40.dp)))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .background(
                    color = Color(0xFF4A93FF),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "All Complete",
                modifier = Modifier.size(32.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

        Text(
            text = "Thank you",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(22.dp).toSp() },
            fontWeight = FontWeight.W700,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

        Text(
            text = "You have successfully completed all required verification steps.",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
            fontWeight = FontWeight.W500,
            color = MaterialTheme.customColors.description,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScaleUtil.scaleWidth(16.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

        FlowTimelineView(steps = flowSteps)

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))

        Button(
            onClick = onFinish,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2B7AEF)
            ),
            shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
            modifier = Modifier
                .fillMaxWidth()
                .height(ScaleUtil.scaleHeight(48.dp))
        ) {
            Text(
                text = "Finish",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                fontWeight = FontWeight.W600,
                color = MaterialTheme.customColors.content
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Powered by ",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                fontWeight = FontWeight.W400,
                color = MaterialTheme.customColors.powered
            )
            Text(
                text = " VERITYPRO",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                fontWeight = FontWeight.W600,
                color = MaterialTheme.customColors.powered
            )
        }
    }
}

// MARK: - Helper functions

private fun stepStatus(
    step: String,
    completedSteps: List<String>,
    currentStage: CombinedFlowStage
): FlowStepStatus {
    if (completedSteps.contains(step)) return FlowStepStatus.COMPLETED
    if (isCurrentStep(step, currentStage)) return FlowStepStatus.CURRENT
    return FlowStepStatus.REMAINING
}

private fun statusText(
    step: String,
    completedSteps: List<String>,
    currentStage: CombinedFlowStage
): String {
    if (completedSteps.contains(step)) return "Completed"
    if (isCurrentStep(step, currentStage)) return "In progress"
    return "Pending"
}

private fun isCurrentStep(step: String, stage: CombinedFlowStage): Boolean {
    return when (stage) {
        CombinedFlowStage.DOCUMENT -> step == "document"
        CombinedFlowStage.BIOMETRIC -> step == "biometric"
        CombinedFlowStage.ADDRESS -> step == "address"
        CombinedFlowStage.EDD -> step == "edd"
        else -> false
    }
}
