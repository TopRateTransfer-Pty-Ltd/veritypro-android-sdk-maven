package com.example.veritypro_sdk.ui.prototype

import android.graphics.BitmapFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.veritypro_sdk.utils.CameraUtils
import java.io.File

/**
 * Screen 5 — live document capture (CameraX), rendered neo-brutalist.
 * Reuses the SDK camera plumbing (CameraUtils.createSmartImageCapture / bindSmartCamera) and
 * saves the JPEG to cache, handing the path back via [onCaptured] for preview + ML verification.
 */
@Composable
fun ProtoDocumentCaptureScreen(
    docLabel: String,
    sideLabel: String,
    onCaptured: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { CameraUtils.createSmartImageCapture(context, withVideoCapture = false) }
    var capturing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }.also { pv ->
                    CameraUtils.bindSmartCamera(ctx, lifecycleOwner, pv, imageCapture)
                }
            },
        )

        // Top bar over the camera — close + mono kicker (white on scrim)
        Row(
            Modifier.fillMaxWidth().background(Color(0xCC120037)).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✕", color = Color.White, fontFamily = ProtoDisplay, fontSize = 20.sp,
                fontWeight = FontWeight.Black, modifier = Modifier.protoClick(onClose))
            Spacer(Modifier.width(16.dp))
            MonoLabel("${docLabel.uppercase()} · ${sideLabel.uppercase()}", Color.White, size = 12)
        }

        // Document frame — white brackets, ID-1 aspect
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1.586f).border(3.dp, Color.White)
                ) {
                    // ink corner accents
                    val corner = Modifier.size(22.dp).background(Proto.GoldenFizz)
                    Box(corner.align(Alignment.TopStart))
                    Box(corner.align(Alignment.TopEnd))
                    Box(corner.align(Alignment.BottomStart))
                    Box(corner.align(Alignment.BottomEnd))
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.background(Color(0xCC171717)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    MonoLabel(if (capturing) "CAPTURING…" else "HOLD STEADY · FILL THE FRAME", Color.White, size = 12)
                }
            }
        }

        // Shutter — square white neo-brutalist button
        Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
            BrutalBox(
                background = Color.White,
                borderColor = Color.White,
                modifier = Modifier.width(120.dp),
            ) {
                Text(
                    if (capturing) "…" else "CAPTURE",
                    color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 15.sp, fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().protoClick {
                        if (!capturing) {
                            capturing = true
                            val file = File(context.cacheDir, "proto_doc_${sideLabel.lowercase()}.jpg")
                            val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                            imageCapture.takePicture(
                                opts, ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                        onCaptured(file.absolutePath)
                                    }
                                    override fun onError(exc: ImageCaptureException) {
                                        capturing = false
                                    }
                                },
                            )
                        }
                    }.padding(vertical = 18.dp),
                )
            }
        }
    }
}

/** Screen 6 — check your photo (captured preview + verdict). */
@Composable
fun ProtoDocumentPreviewScreen(
    imagePath: String,
    onLooksGood: () -> Unit,
    onRetake: () -> Unit,
) {
    val bmp = remember(imagePath) { BitmapFactory.decodeFile(imagePath) }
    Column(Modifier.fillMaxSize().background(Proto.Canvas)) {
        ProtoTopBar(step = null, onBack = onRetake)
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp)) {
            Text(
                "Check your photo", color = Proto.Ink, fontFamily = ProtoDisplay,
                fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, lineHeight = 36.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text("Is everything clear and readable?", color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))
            BrutalBox {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Captured document",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.586f),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.586f).background(Color(0xFFEEF0F4)))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row {
                MonoLabel("✓ ALL FOUR CORNERS VISIBLE", Proto.Green, size = 11)
            }
            Spacer(Modifier.height(4.dp))
            Row { MonoLabel("✓ TEXT IS SHARP", Proto.Green, size = 11) }
        }
        Column(Modifier.padding(24.dp)) {
            ProtoPrimaryButton("Looks good", onClick = onLooksGood)
            Spacer(Modifier.height(10.dp))
            Text(
                "Retake", color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().protoClick(onRetake).padding(12.dp), textAlign = TextAlign.Center,
            )
        }
    }
}
