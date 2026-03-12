package com.example.veritypro_sdk.ui.verification.address

import ScaleUtil
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.compose.material.icons.filled.DocumentScanner
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.veritypro_sdk.ui.theme.customColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun AddressCaptureScreen(
    docType: AddressDocType,
    onBack: () -> Unit,
    onImageCaptured: (Bitmap, File) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    var tempPhotoFile by remember { mutableStateOf<File?>(null) }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = tempPhotoFile
        if (success && file != null && file.exists() && file.length() > 0) {
            isProcessing = true
            coroutineScope.launch(Dispatchers.IO) {
                val bitmap = loadAndCorrectBitmap(file)
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    if (bitmap != null) {
                        onImageCaptured(bitmap, file)
                    }
                }
            }
        }
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessing = true
            coroutineScope.launch(Dispatchers.IO) {
                val file = copyUriToFile(context, uri)
                if (file != null) {
                    val bitmap = loadPreviewBitmap(file)
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        if (bitmap != null) {
                            onImageCaptured(bitmap, file)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                    }
                }
            }
        }
    }

    fun prepareCameraFile(): Uri? {
        val file = File.createTempFile("address_doc_", ".jpg", context.cacheDir)
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
            text = "Capture your ${docType.displayName}",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

        Text(
            text = "Take a clear photo of your document. Ensure all text is readable and the full document is visible.",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            fontWeight = FontWeight.W400,
            color = MaterialTheme.customColors.description,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScaleUtil.scaleWidth(10.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))

        // Document placeholder area with dashed border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ScaleUtil.scaleHeight(200.dp))
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
                    imageVector = Icons.Default.DocumentScanner,
                    contentDescription = "Document placeholder",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.customColors.subTitle
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Position document here",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    color = MaterialTheme.customColors.subTitle
                )
            }
        }

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(40.dp)))

        if (isProcessing) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color(0xFF2B7AEF)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Processing image...",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                color = MaterialTheme.customColors.description
            )
        } else {
            // Take Photo button
            Button(
                onClick = {
                    val uri = prepareCameraFile()
                    if (uri != null) cameraLauncher.launch(uri)
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
                    text = "Take Photo",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.customColors.content
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

            // Choose from Gallery button
            OutlinedButton(
                onClick = { galleryLauncher.launch("image/*") },
                shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScaleUtil.scaleHeight(44.dp))
            ) {
                Text(
                    text = "Choose from Gallery",
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

/** Load bitmap from file, applying EXIF rotation correction. */
private fun loadAndCorrectBitmap(file: File): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)

        val maxDimension = 1920
        val longestSide = maxOf(options.outWidth, options.outHeight)
        var inSampleSize = 1
        while (longestSide / inSampleSize > maxDimension) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        val bitmap = BitmapFactory.decodeFile(file.path, decodeOptions) ?: return null

        val exif = ExifInterface(file.path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } else {
            bitmap
        }
    } catch (e: Exception) {
        Log.e("AddressCapture", "Failed to process bitmap", e)
        null
    }
}

/** Load bitmap from file for preview only (gallery picks). */
private fun loadPreviewBitmap(file: File): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)

        val maxDimension = 1920
        val longestSide = maxOf(options.outWidth, options.outHeight)
        var inSampleSize = 1
        while (longestSide / inSampleSize > maxDimension) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        BitmapFactory.decodeFile(file.path, decodeOptions)
    } catch (e: Exception) {
        Log.e("AddressCapture", "Failed to load preview bitmap", e)
        null
    }
}

/** Copy content URI to a local cache file. */
private fun copyUriToFile(context: android.content.Context, uri: Uri): File? {
    return try {
        val file = File(context.cacheDir, "address_gallery_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        if (file.exists() && file.length() > 0) file else null
    } catch (e: Exception) {
        Log.e("AddressCapture", "Failed to copy URI to file", e)
        null
    }
}
