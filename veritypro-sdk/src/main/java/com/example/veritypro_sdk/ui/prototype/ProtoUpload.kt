package com.example.veritypro_sdk.ui.prototype

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Reusable neo-brutalist upload screen for the Address and EDD modules: pick an evidence type,
 * then upload or photograph a file (PDF/JPG/PNG). The file is copied to cache and handed to
 * [onSubmit] for the real backend call. Submitting/error states are driven by the caller.
 */
@Composable
fun ProtoUploadScreen(
    kicker: String,
    title: String,
    subtitle: String,
    docTypes: List<Pair<String, Int>>,
    accent: Color,
    step: String?,
    submitting: Boolean,
    errorMsg: String?,
    onSubmit: (docTypeInt: Int, file: File) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var selectedType by remember { mutableStateOf(docTypes.firstOrNull()?.second) }
    var pickedFile by remember { mutableStateOf<File?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val f = File(context.cacheDir, "proto_upload_${kicker.filter { it.isLetterOrDigit() }}.dat")
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
                f.exists() && f.length() > 0
            }.getOrDefault(false)
            if (ok) {
                pickedFile = f
                pickedName = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Proto.Canvas).verticalScroll(rememberScrollState())) {
        ProtoTopBar(step = step, onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            MonoLabel(kicker, accent, size = 12)
            Spacer(Modifier.height(12.dp))
            Text(
                title, color = Proto.Ink, fontFamily = ProtoDisplay,
                fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, lineHeight = 36.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp)
            Spacer(Modifier.height(20.dp))

            MonoLabel("DOCUMENT TYPE", Proto.Sub, size = 11)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                docTypes.forEach { (label, type) ->
                    val selected = selectedType == type
                    BrutalBox(
                        background = if (selected) Color(0xFFEFEFFE) else Color.White,
                        borderColor = if (selected) Proto.Brand else Proto.Ink,
                        shadow = false,
                    ) {
                        Row(
                            Modifier.protoClick { selectedType = type }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(18.dp).border(Proto.borderW, if (selected) Proto.Brand else Proto.Ink),
                                contentAlignment = Alignment.Center,
                            ) { if (selected) Box(Modifier.size(10.dp).background(Proto.Brand)) }
                            Spacer(Modifier.width(12.dp))
                            Text(label, color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            // Upload area
            BrutalBox {
                Column(
                    Modifier.protoClick { picker.launch("*/*") }.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("＋", color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        pickedName ?: "Upload or photograph",
                        color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(2.dp))
                    MonoLabel("PDF, JPG OR PNG · MAX 10 MB", Proto.Sub, size = 10)
                }
            }

            Spacer(Modifier.height(14.dp))
            when {
                submitting -> MonoLabel("SUBMITTING…", Proto.Amber, size = 11)
                errorMsg != null -> Text(errorMsg, color = Proto.Danger, fontFamily = ProtoDisplay, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
            ProtoPrimaryButton(
                label = "Submit",
                enabled = selectedType != null && pickedFile != null && !submitting,
                background = accent,
                onClick = { onSubmit(selectedType!!, pickedFile!!) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
