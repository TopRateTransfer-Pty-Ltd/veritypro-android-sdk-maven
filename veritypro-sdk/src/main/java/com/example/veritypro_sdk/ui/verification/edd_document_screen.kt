package com.example.veritypro_sdk.ui.verification

import androidx.compose.runtime.Composable
import java.io.File

@Composable
fun EddDocumentScreen(
    onBack: () -> Unit,
    onDocumentCaptured: (file: File, documentType: Int) -> Unit
) {
    DocumentUploadScreen(
        title = "Enhanced Due Diligence",
        subtitle = "Upload a supporting document",
        documentTypes = listOf(
            0 to "Bank Statement",
            1 to "Pay Slip",
            2 to "Tax Return",
            3 to "Utility Bill",
            4 to "Employment Letter",
            5 to "Business Registration",
            6 to "Investment Statement",
            7 to "Rental Agreement",
            8 to "Government Benefit",
            99 to "Other"
        ),
        onBack = onBack,
        onDocumentSubmitted = onDocumentCaptured
    )
}
