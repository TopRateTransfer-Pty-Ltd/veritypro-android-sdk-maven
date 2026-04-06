package com.example.veritypro_sdk.ui.verification.edd

import ScaleUtil
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.SwapHorizontalCircle
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.ui.theme.customColors

enum class EddDocType(
    val id: Int,
    val displayName: String,
    val subtitle: String,
    val acceptedFormats: String,
    val acceptedDocuments: List<String>,
    val icon: ImageVector,
    val documentTypeValue: String
) {
    SOURCE_OF_FUNDS(
        id = 0,
        displayName = "Source of Funds",
        subtitle = "Bank statements, payslips, or tax returns showing income source",
        acceptedFormats = "PDF, JPG, or PNG (max 10MB)",
        acceptedDocuments = listOf(
            "Bank statements (last 3 months)",
            "Payslips or salary certificates",
            "Tax returns or assessment notices"
        ),
        icon = Icons.Default.AccountBalanceWallet,
        documentTypeValue = "0"      // Backend: BankStatement = 0
    ),
    EMPLOYMENT_PROOF(
        id = 4,
        displayName = "Employment Proof",
        subtitle = "Employment letter, contract, or business registration",
        acceptedFormats = "PDF, JPG, or PNG (max 10MB)",
        acceptedDocuments = listOf(
            "Employment letter or contract",
            "Business registration certificate",
            "Recent payslip with employer details"
        ),
        icon = Icons.Default.Work,
        documentTypeValue = "4"      // Backend: EmploymentLetter = 4
    ),
    PURPOSE_OF_TRANSACTION(
        id = 99,
        displayName = "Purpose of Transaction",
        subtitle = "Invoice, property documents, or purpose declaration",
        acceptedFormats = "PDF, JPG, or PNG (max 10MB)",
        acceptedDocuments = listOf(
            "Invoice or purchase agreement",
            "Property sale/settlement documents",
            "Tuition or medical payment receipts"
        ),
        icon = Icons.Default.SwapHorizontalCircle,
        documentTypeValue = "99"     // Backend: Other = 99
    );
}

@Composable
fun EddDocTypeScreen(
    onBack: () -> Unit,
    onSelect: (EddDocType) -> Unit
) {
    var selected by remember { mutableStateOf<EddDocType?>(null) }
    val isDarkMode = isSystemInDarkTheme()
    val selectedColor = if (isDarkMode) Color(0xFF034EBE) else Color(0xFF034EBE)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.background)
            .statusBarsPadding()
            .padding(
                horizontal = ScaleUtil.scaleWidth(24.dp),
                vertical = ScaleUtil.scaleHeight(16.dp)
            )
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Back button
        Box(
            modifier = Modifier
                .align(Alignment.Start)
                .clickable(onClick = onBack)
                .padding(ScaleUtil.scaleWidth(4.dp))
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.size(ScaleUtil.scaleWidth(24.dp))
            )
        }

        Text(
            text = "VERITYPRO",
            fontWeight = FontWeight.W700,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(24.dp).toSp() },
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = ScaleUtil.scaleHeight((-16).dp))
        )

        Text(
            text = "Select document type",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

        Text(
            text = "Choose which regulatory document you want to submit.",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            fontWeight = FontWeight.W400,
            color = MaterialTheme.customColors.description,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScaleUtil.scaleWidth(10.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

        // Document type cards
        EddDocType.entries.forEach { docType ->
            val isSelected = selected == docType
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected = docType }
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) selectedColor else MaterialTheme.customColors.containerBorder,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = ScaleUtil.scaleWidth(16.dp),
                            vertical = ScaleUtil.scaleHeight(14.dp)
                        ),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = docType.icon,
                        contentDescription = docType.displayName,
                        modifier = Modifier.size(ScaleUtil.scaleWidth(24.dp)),
                        tint = MaterialTheme.customColors.icon
                    )
                    Spacer(modifier = Modifier.width(ScaleUtil.scaleWidth(12.dp)))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = docType.displayName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = docType.subtitle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.W400,
                            color = MaterialTheme.customColors.subTitle
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Accepted:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.customColors.subTitle
                        )
                        docType.acceptedDocuments.forEach { doc ->
                            Text(
                                text = "• $doc",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.W400,
                                color = MaterialTheme.customColors.subTitle
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = docType.acceptedFormats,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.W500,
                            color = Color(0xFF2B7AEF)
                        )
                    }
                    RadioButton(
                        selected = isSelected,
                        onClick = { selected = docType },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = selectedColor
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))
        }

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))

        // Continue button
        Button(
            onClick = {
                selected?.let { onSelect(it) }
            },
            enabled = selected != null,
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

        EddPoweredByFooter()
    }
}
