package com.example.veritypro_sdk.ui.verification

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import com.example.veritypro_sdk.ui.theme.customColors

@Composable
fun IdSelectionScreen(
    onBack: () -> Unit,
    onContinue: (Int?) -> Unit
) {
    var selectedOption by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.background)

            .padding(horizontal = ScaleUtil.scaleWidth(24.dp), vertical = ScaleUtil.scaleHeight(40.dp))
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(32.dp)))

        Text(
            text = "Select your document type",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

        Text(
            text = "Please select the type of document you are using:",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            fontWeight = FontWeight.W400,
            color = MaterialTheme.customColors.description,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScaleUtil.scaleWidth(35.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

        DocumentTypeOption(
            iconRes = R.drawable.document,
            title = "ID card",
            isSelected = selectedOption == 1,
            onSelect = { selectedOption = 1 }
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

        DocumentTypeOption(
            iconRes = R.drawable.document,
            title = "Passport",
            isSelected = selectedOption == 2,
            onSelect = { selectedOption = 2 }
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

        DocumentTypeOption(
            iconRes = R.drawable.document,
            title = "Driver's license",
            isSelected = selectedOption == 3,
            onSelect = { selectedOption = 3 }
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(44.dp)))

        Button(
            onClick = {
//                val docType = when (selectedOption) {
////                    1 -> "ID card"
////                    2 -> "Passport"
////                    3 -> "Driver's license"
////                    else -> ""
//                }
                onContinue(selectedOption)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2B7AEF)
            ),
            shape = MaterialTheme.shapes.small,

            modifier = Modifier
                .fillMaxWidth()
                .height(ScaleUtil.scaleHeight(48.dp)),
            enabled = selectedOption != null
        ) {
            Text(
                "Continue",
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

@Composable
fun DocumentTypeOption(
    iconRes: Int,
    title: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {

    val isDarkMode = isSystemInDarkTheme()
    val selectedColor = if (isDarkMode) Color(0xFF034EBE) else MaterialTheme.colorScheme.primary

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .border(
                width = 1.dp,
                color = MaterialTheme.customColors.containerBorder,
                shape = MaterialTheme.shapes.medium
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.customColors.icon

            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.W600,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                modifier = Modifier.padding(horizontal = 0.dp),
                colors = RadioButtonDefaults.colors(
                    selectedColor = selectedColor
                )
            )
        }
    }
}