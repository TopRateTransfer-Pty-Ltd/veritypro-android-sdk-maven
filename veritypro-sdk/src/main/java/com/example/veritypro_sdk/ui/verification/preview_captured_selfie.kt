package com.example.veritypro_sdk.ui.verification

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PreviewCapturedSelfieScreen(
    file: File,
    onRetake: () -> Unit,
    onContinue: (File) -> Unit
) {
    val bitmap = remember(file.path) {
        try {
            BitmapFactory.decodeFile(file.path)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    var faceDetected by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(bitmap) {
        if (bitmap != null) {
            scope.launch {
                faceDetected = detectFace(file)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF373D4B))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.size(101.dp))

            Text(
                text = "Preview captured image. Continue or retake below",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.W500,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .wrapContentWidth(align = Alignment.CenterHorizontally)
                    .padding(horizontal = 90.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured selfie",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(327f / 280f)
                        .clip(RoundedCornerShape(8.dp))
                        .padding(horizontal = 24.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Unable to load image", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
            if (faceDetected == false) {
                Text(
                    text = "No face detected, please retake the selfie.",
                    color = Color.Red,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Text(
                    text = "Ensure your face is clear and well-lit before you continue",
                    color = Color.White,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(align = Alignment.CenterHorizontally)
                        .padding(horizontal = 80.dp)
                )
            }

            Spacer(modifier = Modifier.height(99.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRetake,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF374151)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                ) {
                    Text("Retake", fontSize = 14.sp, fontWeight = FontWeight.W500)
                }

                Button(
                    onClick = { onContinue(file) },
                    enabled = faceDetected == true,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (faceDetected == true) Color(0XFF2B7AEF) else Color.Gray,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                ) {
                    Text("Continue", fontSize = 14.sp, fontWeight = FontWeight.W500)
                }
            }

            // Feedback to user

        }
    }
}

suspend fun detectFace(file: File): Boolean {
    val bitmap = BitmapFactory.decodeFile(file.path) ?: return false
    val image = InputImage.fromBitmap(bitmap, 0)

    val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .build()

    val detector = FaceDetection.getClient(options)
    val faces = detector.process(image).await()

    // ✅ Require at least one face
    return faces.isNotEmpty()
}
