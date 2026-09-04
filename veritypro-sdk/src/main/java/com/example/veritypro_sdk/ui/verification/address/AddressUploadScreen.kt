package com.example.veritypro_sdk.ui.verification.address

import ScaleUtil
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.ui.theme.customColors
import com.example.veritypro_sdk.utils.LocationHelper
import com.example.veritypro_sdk.utils.VerityOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AddressUploadScreen(
    fileData: ByteArray,
    fileName: String,
    docType: AddressDocType,
    options: VerityOption?,
    sessionId: String?,
    onComplete: (success: Boolean, error: String?) -> Unit
) {
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var isUploading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val repository = remember { ApiRepository() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val maxFileSizeBytes = 10 * 1024 * 1024

    fun performUpload() {
        if (sessionId.isNullOrEmpty()) {
            errorMessage = "No address verification session. Please try again."
            isUploading = false
            return
        }

        if (fileData.size > maxFileSizeBytes) {
            val sizeMb = String.format("%.1f", fileData.size / (1024.0 * 1024.0))
            errorMessage = "File is too large (${sizeMb} MB). Maximum allowed size is 10 MB."
            isUploading = false
            return
        }

        isUploading = true
        errorMessage = null
        uploadProgress = 0f

        coroutineScope.launch {
            launch {
                while (uploadProgress < 0.8f) {
                    delay(100)
                    uploadProgress = (uploadProgress + 0.05f).coerceAtMost(0.8f)
                }
            }

            val tempFile = File(context.cacheDir, "addr_upload_${System.currentTimeMillis()}_$fileName")
            try {
                tempFile.writeBytes(fileData)
            } catch (e: Exception) {
                Log.e("AddressUpload", "Failed to write temp file", e)
                isUploading = false
                errorMessage = "Failed to prepare file for upload."
                return@launch
            }

            val locationHelper = LocationHelper(context)
            val ipAddress = locationHelper.getLocalIpAddress() ?: ""
            val ipLocation = try {
                locationHelper.getCurrentLocation()?.let { "${it.latitude},${it.longitude}" } ?: ""
            } catch (_: Exception) { "" }

            val result = repository.submitAddressDocument(
                sessionId = sessionId,
                file = tempFile,
                documentType = docType.id,
                ipAddress = ipAddress,
                ipLocation = ipLocation,
                apiKey = options?.apiKey ?: "",
                context = context
            )

            tempFile.delete()

            when (result) {
                is Resource.Success -> {
                    uploadProgress = 1f
                    isUploading = false
                    Log.d("AddressUpload", "Document submitted via KYC Integration API")
                    onComplete(true, null)
                }
                is Resource.Error -> {
                    isUploading = false
                    errorMessage = result.message ?: "Upload failed. Please try again."
                    Log.e("AddressUpload", "Upload failed: ${result.message}")
                }
                else -> {
                    isUploading = false
                    errorMessage = "Unexpected response. Please try again."
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        performUpload()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.background)
    ) {
        if (isUploading && errorMessage == null) {
            // iOS-style uploading overlay: label, large headline, subtitle, progress bar
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = ScaleUtil.scaleWidth(24.dp),
                        vertical = ScaleUtil.scaleHeight(48.dp)
                    ),
                horizontalAlignment = Alignment.Start
            ) {
                // Module label
                Text(
                    text = "ADDRESS · UPLOADING",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(11.dp).toSp() },
                    fontWeight = FontWeight.W700,
                    color = Color(0xFF2B7AEF),
                    letterSpacing = LocalDensity.current.run { ScaleUtil.scaleTextSize(1.5.dp).toSp() }
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(32.dp)))

                // Large headline
                Text(
                    text = "Uploading &\nverifying",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(40.dp).toSp() },
                    fontWeight = FontWeight.W700,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = LocalDensity.current.run { ScaleUtil.scaleTextSize(46.dp).toSp() }
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

                // Subtitle
                Text(
                    text = "Sending your document securely…",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    fontWeight = FontWeight.W400,
                    color = MaterialTheme.customColors.description
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

                // Progress bar
                LinearProgressIndicator(
                    progress = { uploadProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF2B7AEF),
                    trackColor = Color(0xFF2B7AEF).copy(alpha = 0.18f)
                )
            }
        } else if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = ScaleUtil.scaleWidth(24.dp),
                        vertical = ScaleUtil.scaleHeight(48.dp)
                    ),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "ADDRESS · UPLOAD FAILED",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(11.dp).toSp() },
                    fontWeight = FontWeight.W700,
                    color = Color(0xFFF44336),
                    letterSpacing = LocalDensity.current.run { ScaleUtil.scaleTextSize(1.5.dp).toSp() }
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(32.dp)))

                Text(
                    text = "Upload failed",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(36.dp).toSp() },
                    fontWeight = FontWeight.W700,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

                Text(
                    text = errorMessage ?: "Unknown error",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    color = MaterialTheme.customColors.description,
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(32.dp)))

                Button(
                    onClick = { performUpload() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B7AEF)),
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
        }

        // Footer pinned to bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ScaleUtil.scaleHeight(24.dp))
        ) {
            PoweredByFooter()
        }
    }
}
