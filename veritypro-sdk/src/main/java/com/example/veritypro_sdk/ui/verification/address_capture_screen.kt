package com.example.veritypro_sdk.ui.verification

import androidx.compose.runtime.Composable
import java.io.File

@Composable
fun AddressCaptureScreen(
    onBack: () -> Unit,
    onDocumentCaptured: (file: File, documentType: Int) -> Unit
) {
    DocumentUploadScreen(
        title = "Address Verification",
        subtitle = "Upload a proof of address document",
        documentTypes = listOf(
            1 to "Utility Bill",
            2 to "Account Statement"
        ),
        onBack = onBack,
        onDocumentSubmitted = onDocumentCaptured
    )
}
