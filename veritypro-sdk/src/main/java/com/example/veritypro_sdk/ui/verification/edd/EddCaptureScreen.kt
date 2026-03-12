package com.example.veritypro_sdk.ui.verification.edd

import ScaleUtil
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.veritypro_sdk.ui.theme.customColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun EddCaptureScreen(
    docType: EddDocType,
    onBack: () -> Unit,
    onFileCaptured: (File, String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var tempPhotoFile by remember { mutableStateOf<File?>(null) }

    val maxSize = 10 * 1024 * 1024 // 10MB

    // File/document picker launcher (PDF, JPG, PNG)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessing = true
            coroutineScope.launch(Dispatchers.IO) {
                val result = copyUriToFile(context, uri)
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    if (result != null) {
                        if (result.length() > maxSize) {
                            fileError = "File is too large. Maximum file size is 10MB."
                        } else {
                            onFileCaptured(result, result.name)
                        }
                    } else {
                        fileError = "Failed to read the selected file. Please try again."
                    }
                }
            }
        }
    }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = tempPhotoFile
        if (success && file != null && file.exists() && file.length() > 0) {
            if (file.length() > maxSize) {
                fileError = "Image is too large. Maximum file size is 10MB."
            } else {
                onFileCaptured(file, "edd_photo.jpg")
            }
        }
    }

    fun prepareCameraFile(): Uri? {
        val file = File.createTempFile("edd_doc_", ".jpg", context.cacheDir)
        tempPhotoFile = file
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
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
            text = "Upload ${docType.displayName}",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

        Text(
            text = "Upload a clear document or take a photo. Accepted formats: PDF, JPG, PNG (max 10MB).",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            fontWeight = FontWeight.W400,
            color = MaterialTheme.customColors.description,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScaleUtil.scaleWidth(10.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))

        // Upload area with dashed border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ScaleUtil.scaleHeight(180.dp))
                .border(
                    width = 2.dp,
                    color = MaterialTheme.customColors.containerBorder,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = "Upload placeholder",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.customColors.subTitle
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Tap to upload or capture",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    color = MaterialTheme.customColors.subTitle
                )
                Text(
                    text = docType.acceptedFormats,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W500,
                    color = Color(0xFF2B7AEF)
                )
            }
        }

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(40.dp)))

        // Error message
        fileError?.let { error ->
            Text(
                text = error,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(13.dp).toSp() },
                color = Color(0xFFF44336),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = ScaleUtil.scaleHeight(12.dp))
            )
        }

        if (isProcessing) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color(0xFF2B7AEF)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Processing file...",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                color = MaterialTheme.customColors.description
            )
        } else {
            // Upload File button
            Button(
                onClick = {
                    fileError = null
                    documentPickerLauncher.launch("*/*")
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
                    text = "Upload File",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.customColors.content
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

            // Take Photo button
            OutlinedButton(
                onClick = {
                    fileError = null
                    val uri = prepareCameraFile()
                    if (uri != null) cameraLauncher.launch(uri)
                },
                shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScaleUtil.scaleHeight(44.dp))
            ) {
                Text(
                    text = "Take Photo",
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

/** Copy content URI to a local cache file. */
private fun copyUriToFile(context: android.content.Context, uri: Uri): File? {
    return try {
        // Determine extension from mime type
        val mimeType = context.contentResolver.getType(uri)
        val ext = when {
            mimeType?.contains("pdf") == true -> ".pdf"
            mimeType?.contains("png") == true -> ".png"
            else -> ".jpg"
        }
        val file = File(context.cacheDir, "edd_upload_${System.currentTimeMillis()}$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        if (file.exists() && file.length() > 0) file else null
    } catch (e: Exception) {
        Log.e("EddCapture", "Failed to copy URI to file", e)
        null
    }
}
