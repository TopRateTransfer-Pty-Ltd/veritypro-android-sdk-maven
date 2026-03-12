package com.example.veritypro_sdk.ui.verification.address

import ScaleUtil
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.veritypro_sdk.ui.theme.customColors
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Composable
fun AddressUploadScreen(
    file: File,
    docType: AddressDocType,
    authToken: String?,
    apiBaseUrl: String?,
    onComplete: (success: Boolean, error: String?) -> Unit
) {
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var isUploading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activeCall by remember { mutableStateOf<Call?>(null) }

    // Animated progress
    LaunchedEffect(isUploading) {
        if (isUploading) {
            while (uploadProgress < 0.8f) {
                delay(100)
                uploadProgress = (uploadProgress + 0.05f).coerceAtMost(0.8f)
            }
        }
    }

    // Start upload on appear
    LaunchedEffect(Unit) {
        performAddressUpload(
            file = file,
            authToken = authToken,
            apiBaseUrl = apiBaseUrl,
            onProgress = { uploadProgress = it },
            onSuccess = {
                uploadProgress = 1f
                isUploading = false
                onComplete(true, null)
            },
            onError = { error ->
                isUploading = false
                errorMessage = error
            },
            onCallCreated = { activeCall = it }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            activeCall?.cancel()
        }
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
            // Uploading state
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
            // Error state
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
                onClick = {
                    errorMessage = null
                    isUploading = true
                    uploadProgress = 0f
                    performAddressUpload(
                        file = file,
                        authToken = authToken,
                        apiBaseUrl = apiBaseUrl,
                        onProgress = { uploadProgress = it },
                        onSuccess = {
                            uploadProgress = 1f
                            isUploading = false
                            onComplete(true, null)
                        },
                        onError = { error ->
                            isUploading = false
                            errorMessage = error
                        },
                        onCallCreated = { activeCall = it }
                    )
                },
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

        PoweredByFooter()
    }
}

private fun performAddressUpload(
    file: File,
    authToken: String?,
    apiBaseUrl: String?,
    onProgress: (Float) -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    onCallCreated: (Call) -> Unit
) {
    if (apiBaseUrl.isNullOrEmpty()) {
        onError("No API URL configured")
        return
    }

    val url = "$apiBaseUrl/identityserver/tier/documents/submit"

    // File size validation (10MB max)
    val maxSize = 10 * 1024 * 1024
    if (file.length() > maxSize) {
        onError("Image is too large. Maximum file size is 10MB.")
        return
    }

    val mimeType = when {
        file.name.lowercase().endsWith(".png") -> "image/png"
        file.name.lowercase().endsWith(".pdf") -> "application/pdf"
        else -> "image/jpeg"
    }

    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("tier", "3")
        .addFormDataPart("documentType", "1")
        .addFormDataPart(
            "file",
            "address_doc.jpg",
            file.asRequestBody(mimeType.toMediaType())
        )
        .build()

    val requestBuilder = Request.Builder()
        .url(url)
        .post(requestBody)

    if (!authToken.isNullOrEmpty()) {
        requestBuilder.addHeader("Authorization", "Bearer $authToken")
    }

    val call = client.newCall(requestBuilder.build())
    onCallCreated(call)

    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (call.isCanceled()) return
            Log.e("AddressUpload", "Upload failed", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onError(e.message ?: "Upload failed. Please try again.")
            }
        }

        override fun onResponse(call: Call, response: Response) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (response.isSuccessful) {
                    onProgress(1f)
                    onSuccess()
                } else {
                    onError("Server error (${response.code}). Please try again.")
                }
            }
        }
    })
}
