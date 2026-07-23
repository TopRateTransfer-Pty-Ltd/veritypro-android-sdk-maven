package com.example.veritypro_sdk.ui.redesign.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.veritypro_sdk.ui.redesign.components.VerityStatusCard
import com.example.veritypro_sdk.ui.redesign.components.VerityStatusKind
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

/**
 * D2 Screens 14–16 — terminal result (approved / pending_manual_review / rejected). Generic,
 * reassuring copy; the decision *reason* (EDD/PEP/device/screening) is never shown (audit §10).
 */
@Composable
fun VerityResultScreen(
    kind: VerityStatusKind,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = MaterialTheme.verityColors
    val (title, message) = when (kind) {
        VerityStatusKind.Success -> "You're verified!" to
            "Thanks — you're all set."
        VerityStatusKind.Review -> "We're reviewing your details" to
            "This can take a little while. You'll be notified when it's done — you can close this."
        VerityStatusKind.Error -> "We couldn't verify you" to
            "We weren't able to complete verification. Contact the business you're verifying with if you think this is a mistake."
        VerityStatusKind.Processing -> "Finishing your verification…" to
            "This usually takes a few seconds."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgCanvas)
            .padding(VerityDim.space6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VerityStatusCard(
            kind = kind,
            title = title,
            message = message,
            primaryText = if (kind == VerityStatusKind.Processing) null else "Done",
            onPrimary = if (kind == VerityStatusKind.Processing) null else onDone
        )
    }
}
