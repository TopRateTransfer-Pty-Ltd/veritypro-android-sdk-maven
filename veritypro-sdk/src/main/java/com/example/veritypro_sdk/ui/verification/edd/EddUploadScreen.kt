package com.example.veritypro_sdk.ui.verification.edd

import ScaleUtil
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.ui.theme.customColors
import com.example.veritypro_sdk.utils.VerityOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun EddUploadScreen(
    file: File,
    fileName: String,
    docType: EddDocType,
    options: VerityOption?,
    onComplete: (success: Boolean, error: String?) -> Unit
) {
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var isUploading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val repository = remember { ApiRepository() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    fun performUpload() {
        val opts = options
        if (opts == null) {
            errorMessage = "Missing verification options. Please restart the flow."
            isUploading = false
            return
        }

        isUploading = true
        errorMessage = null
        uploadProgress = 0f

        coroutineScope.launch {
            // Animate progress while uploading
            launch {
                while (uploadProgress < 0.8f) {
                    delay(100)
                    uploadProgress = (uploadProgress + 0.05f).coerceAtMost(0.8f)
                }
            }

            // File size validation (10MB max)
            val maxSize = 10 * 1024 * 1024
            if (file.length() > maxSize) {
                isUploading = false
                errorMessage = "File is too large. Maximum file size is 10MB."
                return@launch
            }

            val result = repository.createEddCase(
                subjectId = opts.vendorData,
                subjectName = "${opts.firstName} ${opts.lastName}",
                file = file,
                documentType = docType.id,
                apiKey = opts.apiKey,
                context = context
            )

            when (result) {
                is Resource.Success -> {
                    uploadProgress = 1f
                    isUploading = false
                    Log.d("EddUpload", "EDD case created via KYC Integration API: ${result.data.caseId}")
                    onComplete(true, null)
                }
                is Resource.Error -> {
                    isUploading = false
                    errorMessage = result.message ?: "Upload failed. Please try again."
                    Log.e("EddUpload", "EDD case creation failed: ${result.message}")
                }
                else -> {
                    isUploading = false
                    errorMessage = "Unexpected response. Please try again."
                }
            }

            // Clean up temp file
            file.delete()
        }
    }

    // Start upload on appear
    LaunchedEffect(Unit) {
        performUpload()
    }

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

        if (isUploading && errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color(0xFF034EBE),
                strokeWidth = 4.dp
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

            Text(
                text = "Uploading document...",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
                fontWeight = FontWeight.W700,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

            Text(
                text = "Please wait while we securely upload your ${docType.displayName}.",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                color = MaterialTheme.customColors.description,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(20.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(20.dp)))

            LinearProgressIndicator(
                progress = { uploadProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScaleUtil.scaleWidth(20.dp)),
                color = Color(0xFF2B7AEF),
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

            Text(
                text = "${(uploadProgress * 100).toInt()}%",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                fontWeight = FontWeight.W500,
                color = MaterialTheme.customColors.subTitle
            )
        } else if (errorMessage != null) {
            Icon(
                imageVector = Icons.Default.Cancel,
                contentDescription = "Error",
                modifier = Modifier.size(72.dp),
                tint = Color(0xFFF44336)
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

            Text(
                text = "Upload failed",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(22.dp).toSp() },
                fontWeight = FontWeight.W700,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

            Text(
                text = errorMessage ?: "Unknown error",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                color = MaterialTheme.customColors.description,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(20.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(20.dp)))

            Button(
                onClick = { performUpload() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2B7AEF)
                ),
                shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScaleUtil.scaleHeight(48.dp))
            ) {
                Text(
                    text = "Try again",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.customColors.content
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

            OutlinedButton(
                onClick = { onComplete(false, errorMessage) },
                shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScaleUtil.scaleHeight(44.dp))
            ) {
                Text(
                    text = "Cancel",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    fontWeight = FontWeight.W600,
                    color = Color(0xFF2B7AEF)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        EddPoweredByFooter()
    }
}
