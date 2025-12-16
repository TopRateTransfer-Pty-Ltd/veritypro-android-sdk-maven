# VerityPro Android SDK - Implementation Guide

## Backend Configuration
```kotlin
const val BASE_URL = "http://localhost:8001"
```

## API Endpoints Used
| Endpoint | Purpose |
|----------|---------|
| `POST /v1/kyc/doc/predict` | Single frame detection + classification |
| `POST /v1/kyc/doc/verify-burst` | Multi-frame anti-spoof verification |

---

## Project Structure

```
veritypro-sdk/
├── src/main/java/com/example/veritypro_sdk/
│   ├── models/
│   │   ├── DocumentType.kt
│   │   ├── VerificationResult.kt
│   │   └── ApiModels.kt
│   ├── network/
│   │   ├── VerificationApi.kt
│   │   └── ApiClient.kt
│   ├── ui/
│   │   ├── theme/
│   │   │   └── Theme.kt
│   │   └── verification/
│   │       ├── DocumentSelectionScreen.kt
│   │       ├── DocumentCaptureScreen.kt
│   │       ├── DocumentPreviewScreen.kt
│   │       ├── LivenessCheckScreen.kt
│   │       └── ConfirmationScreen.kt
│   ├── utils/
│   │   ├── CameraManager.kt
│   │   └── ImageUtils.kt
│   └── VerityPro.kt
└── src/main/assets/
    └── best.tflite (optional local fallback)
```

---

## Dependencies (build.gradle)

```gradle
dependencies {
    // Compose
    implementation "androidx.compose.ui:ui:1.6.0"
    implementation "androidx.compose.material3:material3:1.2.0"
    implementation "androidx.activity:activity-compose:1.8.2"

    // Camera
    implementation "androidx.camera:camera-core:1.3.1"
    implementation "androidx.camera:camera-camera2:1.3.1"
    implementation "androidx.camera:camera-lifecycle:1.3.1"
    implementation "androidx.camera:camera-view:1.3.1"

    // Networking
    implementation "com.squareup.retrofit2:retrofit:2.9.0"
    implementation "com.squareup.retrofit2:converter-gson:2.9.0"
    implementation "com.squareup.okhttp3:okhttp:4.12.0"
    implementation "com.squareup.okhttp3:logging-interceptor:4.12.0"

    // Coroutines
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
}
```

---

## Models

### DocumentType.kt
```kotlin
package com.example.veritypro_sdk.models

enum class DocumentType(
    val apiValue: String,
    val displayName: String,
    val icon: String,
    val requiresBack: Boolean
) {
    PASSPORT("PASSPORT", "Passport", "🛂", false),
    DRIVERS_LICENSE("DRIVERS_LICENSE", "Driver's License", "🚗", true),
    ID_CARD("ID_CARD", "National ID Card", "🪪", true)
}

enum class DocumentSide(val apiValue: String) {
    FRONT("FRONT"),
    BACK("BACK")
}
```

### ApiModels.kt
```kotlin
package com.example.veritypro_sdk.models

import com.google.gson.annotations.SerializedName

// Predict Request/Response
data class PredictRequest(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("docTypeExpected") val docTypeExpected: String,
    @SerializedName("sideExpected") val sideExpected: String,
    @SerializedName("imageJpegBase64") val imageJpegBase64: String
)

data class PredictResponse(
    @SerializedName("docOk") val docOk: Boolean,
    @SerializedName("bbox") val bbox: BoundingBox?,
    @SerializedName("docType") val docType: String?,
    @SerializedName("side") val side: String?,
    @SerializedName("nextAction") val nextAction: String?,
    @SerializedName("hint") val hint: String?,
    @SerializedName("confidence") val confidence: ConfidenceScores?,
    @SerializedName("latencyMs") val latencyMs: Double?
)

data class BoundingBox(
    @SerializedName("x") val x: Double,
    @SerializedName("y") val y: Double,
    @SerializedName("w") val w: Double,
    @SerializedName("h") val h: Double
)

data class ConfidenceScores(
    @SerializedName("detection") val detection: Double?,
    @SerializedName("classification") val classification: Double?
)

// Verify Burst Request/Response
data class VerifyBurstRequest(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("frames") val frames: List<String>,
    @SerializedName("docTypeExpected") val docTypeExpected: String,
    @SerializedName("sideExpected") val sideExpected: String
)

data class VerifyBurstResponse(
    @SerializedName("decision") val decision: String,
    @SerializedName("spoof") val spoof: SpoofResult?,
    @SerializedName("hint") val hint: String?,
    @SerializedName("confidence") val confidence: Double?,
    @SerializedName("latencyMs") val latencyMs: Double?
)

data class SpoofResult(
    @SerializedName("score") val score: Double,
    @SerializedName("reason") val reason: String
)
```

---

## Network Layer

### VerificationApi.kt
```kotlin
package com.example.veritypro_sdk.network

import com.example.veritypro_sdk.models.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface VerificationApi {
    @POST("/v1/kyc/doc/predict")
    suspend fun predict(@Body request: PredictRequest): Response<PredictResponse>

    @POST("/v1/kyc/doc/verify-burst")
    suspend fun verifyBurst(@Body request: VerifyBurstRequest): Response<VerifyBurstResponse>

    @GET("/v1/kyc/doc/health")
    suspend fun healthCheck(): Response<Map<String, Any>>
}
```

### ApiClient.kt
```kotlin
package com.example.veritypro_sdk.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "http://localhost:8001"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val api: VerificationApi by lazy {
        retrofit.create(VerificationApi::class.java)
    }
}
```

---

## Utility Classes

### ImageUtils.kt
```kotlin
package com.example.veritypro_sdk.utils

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageUtils {
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    fun resizeBitmap(bitmap: Bitmap, maxWidth: Int = 1920, maxHeight: Int = 1080): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
```

---

## UI Components (Jetpack Compose)

### Step 1: DocumentSelectionScreen.kt
```kotlin
package com.example.veritypro_sdk.ui.verification

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.models.DocumentType

@Composable
fun DocumentSelectionScreen(
    onDocumentSelected: (DocumentType) -> Unit,
    onBack: () -> Unit
) {
    var selectedDocument by remember { mutableStateOf<DocumentType?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = "Select Your Document",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Choose the type of identity document you want to verify",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Document Options
        DocumentType.values().forEach { docType ->
            DocumentCard(
                docType = docType,
                isSelected = selectedDocument == docType,
                onClick = { selectedDocument = docType }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        // Continue Button
        Button(
            onClick = { selectedDocument?.let { onDocumentSelected(it) } },
            enabled = selectedDocument != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = Color.Gray
            )
        ) {
            Text(
                text = "Continue",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DocumentCard(
    docType: DocumentType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 2.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f)
        ),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = docType.icon,
                fontSize = 40.sp
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = docType.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                if (docType.requiresBack) {
                    Text(
                        text = "Front + Back required",
                        fontSize = 12.sp,
                        color = Color(0xFFE65100)
                    )
                }
            }

            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
```

### Step 2: DocumentCaptureScreen.kt
```kotlin
package com.example.veritypro_sdk.ui.verification

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.veritypro_sdk.models.*
import com.example.veritypro_sdk.network.ApiClient
import com.example.veritypro_sdk.utils.ImageUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Composable
fun DocumentCaptureScreen(
    docType: DocumentType,
    side: DocumentSide,
    sessionId: String,
    onCapture: (Bitmap, List<Bitmap>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var isDocumentDetected by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("Position document within frame") }
    var confidence by remember { mutableStateOf(0f) }
    var isCapturing by remember { mutableStateOf(false) }

    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val collectedFrames = remember { mutableListOf<Bitmap>() }

    // Camera setup
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        imageCapture = capture

        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    // Convert to bitmap and run detection
                    val bitmap = imageProxy.toBitmap()

                    // Collect frames for anti-spoof (max 10)
                    if (collectedFrames.size < 10) {
                        collectedFrames.add(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    } else {
                        collectedFrames.removeAt(0)
                        collectedFrames.add(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    }

                    // Run detection every ~800ms (controlled by analyzer)
                    coroutineScope.launch {
                        try {
                            val base64 = ImageUtils.bitmapToBase64(
                                ImageUtils.resizeBitmap(bitmap, 640, 640)
                            )

                            val response = ApiClient.api.predict(
                                PredictRequest(
                                    sessionId = sessionId,
                                    docTypeExpected = docType.apiValue,
                                    sideExpected = side.apiValue,
                                    imageJpegBase64 = base64
                                )
                            )

                            if (response.isSuccessful) {
                                response.body()?.let { result ->
                                    isDocumentDetected = result.docOk
                                    hint = result.hint ?: "Position document within frame"
                                    confidence = result.confidence?.detection?.toFloat() ?: 0f
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("DocumentCapture", "Detection failed", e)
                        }
                    }

                    imageProxy.close()
                }
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
                imageAnalyzer
            )
        } catch (e: Exception) {
            Log.e("DocumentCapture", "Camera binding failed", e)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview
        AndroidView(
            factory = { previewView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }},
            modifier = Modifier.fillMaxSize()
        )

        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "${if (side == DocumentSide.FRONT) "Front" else "Back"} of ${docType.displayName}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(48.dp))
        }

        // Document Frame Overlay
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.85f)
                .aspectRatio(1.586f) // ID card ratio
                .border(
                    width = 3.dp,
                    color = if (isDocumentDetected) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            // Corner indicators
            CornerIndicators(isDetected = isDocumentDetected)
        }

        // Hint Banner
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 120.dp),
            shape = RoundedCornerShape(24.dp),
            color = if (isDocumentDetected) Color(0xFF4CAF50) else Color(0xFFFF9800)
        ) {
            Text(
                text = hint,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(24.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Capture Button
            Button(
                onClick = {
                    if (!isCapturing && isDocumentDetected) {
                        isCapturing = true
                        imageCapture?.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = image.toBitmap()
                                    image.close()
                                    onCapture(bitmap, collectedFrames.toList())
                                    isCapturing = false
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e("DocumentCapture", "Capture failed", exception)
                                    isCapturing = false
                                }
                            }
                        )
                    }
                },
                enabled = isDocumentDetected && !isCapturing,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDocumentDetected) Color(0xFF4CAF50) else Color.Gray,
                    disabledContainerColor = Color.Gray.copy(alpha = 0.5f)
                )
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "CAPTURE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Confidence Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(200.dp)
            ) {
                LinearProgressIndicator(
                    progress = { confidence },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color(0xFF4CAF50),
                    trackColor = Color.White.copy(alpha = 0.2f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "${(confidence * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun CornerIndicators(isDetected: Boolean) {
    val color = if (isDetected) Color(0xFF4CAF50) else Color.White

    Box(modifier = Modifier.fillMaxSize()) {
        // Top Left
        Box(
            modifier = Modifier
                .size(30.dp)
                .align(Alignment.TopStart)
                .offset(x = (-2).dp, y = (-2).dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(color)
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(color)
            )
        }

        // Top Right
        Box(
            modifier = Modifier
                .size(30.dp)
                .align(Alignment.TopEnd)
                .offset(x = 2.dp, y = (-2).dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(color)
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(color)
                    .align(Alignment.TopEnd)
            )
        }

        // Bottom Left
        Box(
            modifier = Modifier
                .size(30.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-2).dp, y = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(color)
                    .align(Alignment.BottomStart)
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(color)
            )
        }

        // Bottom Right
        Box(
            modifier = Modifier
                .size(30.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(color)
                    .align(Alignment.BottomEnd)
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(color)
                    .align(Alignment.BottomEnd)
            )
        }
    }
}

// Extension function to convert ImageProxy to Bitmap
private fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
}
```

### Step 2.1 & 4.1: DocumentPreviewScreen.kt
```kotlin
package com.example.veritypro_sdk.ui.verification

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.models.*
import com.example.veritypro_sdk.network.ApiClient
import com.example.veritypro_sdk.utils.ImageUtils
import kotlinx.coroutines.launch

data class ClassificationResult(
    val passed: Boolean,
    val docType: String,
    val side: String,
    val confidence: Float
)

data class AntiSpoofResult(
    val passed: Boolean,
    val reason: String,
    val confidence: Float
)

@Composable
fun DocumentPreviewScreen(
    image: Bitmap,
    burstFrames: List<Bitmap>,
    docType: DocumentType,
    side: DocumentSide,
    sessionId: String,
    onContinue: () -> Unit,
    onRetake: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var isVerifying by remember { mutableStateOf(true) }
    var classificationResult by remember { mutableStateOf<ClassificationResult?>(null) }
    var antiSpoofResult by remember { mutableStateOf<AntiSpoofResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val isVerified = classificationResult?.passed == true && antiSpoofResult?.passed == true

    // Run verification on mount
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            isVerifying = true
            error = null

            try {
                // Step 1: Classification
                val base64 = ImageUtils.bitmapToBase64(
                    ImageUtils.resizeBitmap(image, 1920, 1080)
                )

                val predictResponse = ApiClient.api.predict(
                    PredictRequest(
                        sessionId = sessionId,
                        docTypeExpected = docType.apiValue,
                        sideExpected = side.apiValue,
                        imageJpegBase64 = base64
                    )
                )

                if (predictResponse.isSuccessful) {
                    predictResponse.body()?.let { result ->
                        classificationResult = ClassificationResult(
                            passed = result.docOk,
                            docType = result.docType ?: "Unknown",
                            side = result.side ?: "Unknown",
                            confidence = result.confidence?.classification?.toFloat() ?: 0f
                        )

                        if (!result.docOk) {
                            error = result.hint ?: "Document validation failed"
                            isVerifying = false
                            return@launch
                        }
                    }
                } else {
                    error = "Classification request failed"
                    isVerifying = false
                    return@launch
                }

                // Step 2: Anti-Spoof
                val framesToUse = if (burstFrames.isNotEmpty()) burstFrames else listOf(image)
                val frameBase64List = framesToUse.map { frame ->
                    ImageUtils.bitmapToBase64(ImageUtils.resizeBitmap(frame, 640, 640), 70)
                }

                val spoofResponse = ApiClient.api.verifyBurst(
                    VerifyBurstRequest(
                        sessionId = sessionId,
                        frames = frameBase64List,
                        docTypeExpected = docType.apiValue,
                        sideExpected = side.apiValue
                    )
                )

                if (spoofResponse.isSuccessful) {
                    spoofResponse.body()?.let { result ->
                        antiSpoofResult = AntiSpoofResult(
                            passed = result.decision == "PASS",
                            reason = result.spoof?.reason ?: "Unknown",
                            confidence = 1f - (result.spoof?.score?.toFloat() ?: 0f)
                        )

                        if (result.decision != "PASS") {
                            error = result.hint ?: "Anti-spoof check failed"
                        }
                    }
                } else {
                    error = "Anti-spoof request failed"
                }
            } catch (e: Exception) {
                Log.e("DocumentPreview", "Verification failed", e)
                error = "Verification failed: ${e.message}"
            } finally {
                isVerifying = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${if (side == DocumentSide.FRONT) "Front" else "Back"} of Document",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Review your captured image",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }

        // Image Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Captured document",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            )

            // Verification Overlay
            if (isVerifying) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Verifying document...",
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Verification Results
        if (!isVerifying) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                classificationResult?.let { result ->
                    VerificationResultRow(
                        title = "Document Classification",
                        subtitle = "${result.docType} - ${result.side}",
                        passed = result.passed,
                        confidence = result.confidence
                    )
                }

                antiSpoofResult?.let { result ->
                    VerificationResultRow(
                        title = "Authenticity Check",
                        subtitle = if (result.passed) "Genuine document" else "Detected: ${result.reason}",
                        passed = result.passed,
                        confidence = result.confidence
                    )
                }
            }
        }

        // Error Banner
        error?.let { errorMessage ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFFFF3E0)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFE65100)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = errorMessage,
                        color = Color(0xFFE65100)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Retake Photo")
            }

            Button(
                onClick = onContinue,
                enabled = isVerified && !isVerifying,
                modifier = Modifier.weight(2f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = Color.Gray
                )
            ) {
                Text(
                    text = when {
                        isVerifying -> "Verifying..."
                        isVerified -> "Continue"
                        else -> "Retry Required"
                    }
                )
            }
        }
    }
}

@Composable
private fun VerificationResultRow(
    title: String,
    subtitle: String,
    passed: Boolean,
    confidence: Float
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (passed) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (passed) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    tint = if (passed) Color(0xFF4CAF50) else Color(0xFFF44336),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            Text(
                text = "${(confidence * 100).toInt()}%",
                fontWeight = FontWeight.Bold,
                color = if (passed) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
        }
    }
}
```

---

## Main Flow Coordinator

### VerityProActivity.kt
```kotlin
package com.example.veritypro_sdk

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.example.veritypro_sdk.models.*
import com.example.veritypro_sdk.ui.verification.*
import java.util.UUID

class VerityProActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VerityProFlow(
                onComplete = { result ->
                    setResult(RESULT_OK, intent.putExtra("verification_result", result))
                    finish()
                },
                onCancel = {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            )
        }
    }
}

enum class VerificationStep {
    SELECT_DOCUMENT,
    CAPTURE_FRONT,
    PREVIEW_FRONT,
    CAPTURE_BACK,
    PREVIEW_BACK,
    LIVENESS,
    CONFIRMATION
}

@Composable
fun VerityProFlow(
    onComplete: (VerificationResult) -> Unit,
    onCancel: () -> Unit
) {
    var currentStep by remember { mutableStateOf(VerificationStep.SELECT_DOCUMENT) }
    var selectedDocType by remember { mutableStateOf<DocumentType?>(null) }
    var frontImage by remember { mutableStateOf<Bitmap?>(null) }
    var frontBurstFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var backImage by remember { mutableStateOf<Bitmap?>(null) }
    var backBurstFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

    val sessionId = remember { UUID.randomUUID().toString() }

    when (currentStep) {
        VerificationStep.SELECT_DOCUMENT -> {
            DocumentSelectionScreen(
                onDocumentSelected = { docType ->
                    selectedDocType = docType
                    currentStep = VerificationStep.CAPTURE_FRONT
                },
                onBack = onCancel
            )
        }

        VerificationStep.CAPTURE_FRONT -> {
            selectedDocType?.let { docType ->
                DocumentCaptureScreen(
                    docType = docType,
                    side = DocumentSide.FRONT,
                    sessionId = sessionId,
                    onCapture = { image, frames ->
                        frontImage = image
                        frontBurstFrames = frames
                        currentStep = VerificationStep.PREVIEW_FRONT
                    },
                    onBack = { currentStep = VerificationStep.SELECT_DOCUMENT }
                )
            }
        }

        VerificationStep.PREVIEW_FRONT -> {
            frontImage?.let { image ->
                selectedDocType?.let { docType ->
                    DocumentPreviewScreen(
                        image = image,
                        burstFrames = frontBurstFrames,
                        docType = docType,
                        side = DocumentSide.FRONT,
                        sessionId = sessionId,
                        onContinue = {
                            currentStep = if (docType.requiresBack) {
                                VerificationStep.CAPTURE_BACK
                            } else {
                                VerificationStep.LIVENESS
                            }
                        },
                        onRetake = { currentStep = VerificationStep.CAPTURE_FRONT }
                    )
                }
            }
        }

        VerificationStep.CAPTURE_BACK -> {
            selectedDocType?.let { docType ->
                DocumentCaptureScreen(
                    docType = docType,
                    side = DocumentSide.BACK,
                    sessionId = sessionId,
                    onCapture = { image, frames ->
                        backImage = image
                        backBurstFrames = frames
                        currentStep = VerificationStep.PREVIEW_BACK
                    },
                    onBack = { currentStep = VerificationStep.PREVIEW_FRONT }
                )
            }
        }

        VerificationStep.PREVIEW_BACK -> {
            backImage?.let { image ->
                selectedDocType?.let { docType ->
                    DocumentPreviewScreen(
                        image = image,
                        burstFrames = backBurstFrames,
                        docType = docType,
                        side = DocumentSide.BACK,
                        sessionId = sessionId,
                        onContinue = { currentStep = VerificationStep.LIVENESS },
                        onRetake = { currentStep = VerificationStep.CAPTURE_BACK }
                    )
                }
            }
        }

        VerificationStep.LIVENESS -> {
            LivenessCheckScreen(
                onComplete = { currentStep = VerificationStep.CONFIRMATION },
                onBack = {
                    currentStep = if (selectedDocType?.requiresBack == true) {
                        VerificationStep.PREVIEW_BACK
                    } else {
                        VerificationStep.PREVIEW_FRONT
                    }
                }
            )
        }

        VerificationStep.CONFIRMATION -> {
            ConfirmationScreen(
                success = true,
                verificationId = sessionId,
                onDone = {
                    onComplete(VerificationResult(
                        success = true,
                        verificationId = sessionId,
                        documentType = selectedDocType?.apiValue ?: ""
                    ))
                }
            )
        }
    }
}
```

---

## AndroidManifest.xml Permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />

<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.camera.autofocus" />
```

---

## Quality Check Recommendation

**Skip client-side blur/brightness checks.** The ML backend handles quality implicitly through detection confidence. Low-quality images won't detect well. Keep the client simple.

---

## API Integration Summary

| Step | Endpoint | Frequency | Purpose |
|------|----------|-----------|---------|
| 2 (Capture) | `POST /predict` | Per frame analysis | Real-time detection |
| 2.1 (Preview) | `POST /predict` | Once | Classification |
| 2.1 (Preview) | `POST /verify-burst` | Once | Anti-spoof |
| 3 (Back Capture) | `POST /predict` | Per frame analysis | Real-time detection |
| 4.1 (Back Preview) | `POST /predict` | Once | Classification |
| 4.1 (Back Preview) | `POST /verify-burst` | Once | Anti-spoof |

---

## Network Configuration for Local Testing

For testing with `localhost`, add to `res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

Reference in `AndroidManifest.xml`:
```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

**Note:** Use `10.0.2.2` instead of `localhost` when running on Android emulator.
