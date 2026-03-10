package com.example.veritypro_sdk.ui.verification

import ScaleUtil
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.veritypro_sdk.utils.ImageSharpeningUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Shared document upload screen used by both ADDRESS and EDD modules.
 *
 * Provides:
 * 1. Document type selection (chips)
 * 2. Capture method: "Take Photo" (CameraX) or "Choose from Gallery" (image picker)
 * 3. Image preview with Retake/Continue buttons
 * 4. Applies image sharpening on camera captures
 */
@Composable
fun DocumentUploadScreen(
    title: String,
    subtitle: String,
    documentTypes: List<Pair<Int, String>>,
    onBack: () -> Unit,
    onDocumentSubmitted: (file: File, documentType: Int) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedDocType by remember { mutableStateOf<Int?>(null) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    // Temp file for camera capture
    var tempPhotoFile by remember { mutableStateOf<File?>(null) }
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }

    fun prepareCameraFile(): Uri? {
        val file = File.createTempFile("doc_upload_", ".jpg", context.cacheDir)
        tempPhotoFile = file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        tempPhotoUri = uri
        return uri
    }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = tempPhotoFile
        if (success && file != null && file.exists() && file.length() > 0) {
            isProcessing = true
            coroutineScope.launch(Dispatchers.IO) {
                val bitmap = loadAndProcessBitmap(file)
                withContext(Dispatchers.Main) {
                    previewBitmap = bitmap
                    capturedFile = file
                    isProcessing = false
                }
            }
        }
    }

    // Gallery picker launcher
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
                        previewBitmap = bitmap
                        capturedFile = file
                        isProcessing = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF373D4B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar with back button
            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScaleUtil.scaleWidth(16.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
                    fontWeight = FontWeight.W600
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(24.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

            // Document type selection
            Text(
                text = "Select document type",
                color = Color.White,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                fontWeight = FontWeight.W500,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

            // Document type chips (wrapped in a flow)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp)),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                documentTypes.chunked(2).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        row.forEach { (id, name) ->
                            val isSelected = selectedDocType == id
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) Color(0xFF2B7AEF) else Color.White.copy(alpha = 0.1f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFF2B7AEF) else Color.White.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedDocType = id }
                                    .padding(
                                        horizontal = 12.dp,
                                        vertical = 10.dp
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = name,
                                    color = Color.White,
                                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                                    fontWeight = if (isSelected) FontWeight.W600 else FontWeight.W400,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2
                                )
                            }
                        }
                        // Pad odd rows
                        if (row.size < 2) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

            if (capturedFile != null && previewBitmap != null) {
                // Preview mode
                Text(
                    text = "Document preview",
                    color = Color.White,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    fontWeight = FontWeight.W500,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

                Image(
                    bitmap = previewBitmap!!.asImageBitmap(),
                    contentDescription = "Captured document",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

                // Retake / Continue buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScaleUtil.scaleWidth(24.dp)),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            capturedFile = null
                            previewBitmap = null
                        },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF374151)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(ScaleUtil.scaleHeight(48.dp))
                    ) {
                        Text(
                            text = "Retake",
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                            fontWeight = FontWeight.W500
                        )
                    }

                    Button(
                        onClick = {
                            val file = capturedFile
                            val docType = selectedDocType
                            if (file != null && docType != null) {
                                onDocumentSubmitted(file, docType)
                            }
                        },
                        enabled = selectedDocType != null,
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2B7AEF),
                            contentColor = Color.White,
                            disabledContainerColor = Color.Gray,
                            disabledContentColor = Color.White.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(ScaleUtil.scaleHeight(48.dp))
                    ) {
                        Text(
                            text = "Continue",
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                            fontWeight = FontWeight.W500
                        )
                    }
                }
            } else {
                // Capture mode — show two options
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color.White,
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Processing image...",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() }
                    )
                } else {
                    Text(
                        text = "Upload your document",
                        color = Color.White,
                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                        fontWeight = FontWeight.W500,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                    )

                    Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

                    // Take Photo button
                    Button(
                        onClick = {
                            val uri = prepareCameraFile()
                            if (uri != null) cameraLauncher.launch(uri)
                        },
                        enabled = selectedDocType != null,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2B7AEF),
                            contentColor = Color.White,
                            disabledContainerColor = Color.Gray,
                            disabledContentColor = Color.White.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                            .height(ScaleUtil.scaleHeight(56.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Take Photo",
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                            fontWeight = FontWeight.W500
                        )
                    }

                    Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

                    // Choose from Gallery button
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        enabled = selectedDocType != null,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                            .height(ScaleUtil.scaleHeight(56.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Photo,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Choose from Gallery",
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                            fontWeight = FontWeight.W500
                        )
                    }

                    if (selectedDocType == null) {
                        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))
                        Text(
                            text = "Please select a document type first",
                            color = Color(0xFFFFB74D),
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(32.dp)))
        }
    }
}

/** Load bitmap from file and apply sharpening pipeline (for camera captures). */
private fun loadAndProcessBitmap(file: File): Bitmap? {
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

        val oriented = if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } else {
            bitmap
        }

        ImageSharpeningUtils.applySharpeningPipeline(oriented)
    } catch (e: Exception) {
        Log.e("DocumentUpload", "Failed to process bitmap", e)
        null
    }
}

/** Load bitmap from file for preview only (gallery picks — no sharpening). */
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
        Log.e("DocumentUpload", "Failed to load preview bitmap", e)
        null
    }
}

/** Copy content URI to a local cache file. */
private fun copyUriToFile(context: Context, uri: Uri): File? {
    return try {
        val file = File(context.cacheDir, "gallery_upload_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        if (file.exists() && file.length() > 0) file else null
    } catch (e: Exception) {
        Log.e("DocumentUpload", "Failed to copy URI to file", e)
        null
    }
}
