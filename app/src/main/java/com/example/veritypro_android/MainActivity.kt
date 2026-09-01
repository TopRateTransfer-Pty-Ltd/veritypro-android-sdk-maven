package com.example.veritypro_android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_android.ui.theme.VerityproandroidTheme
import com.example.veritypro_sdk.VerityPro
import com.example.veritypro_sdk.services.MLRetrofitInstance
import androidx.compose.runtime.mutableStateOf
import com.example.veritypro_sdk.ui.prototype.ProtoVerificationScreen
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.utils.LivenessResult
import com.example.veritypro_sdk.utils.VerityOption

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Point the ML client at the DEPLOYED doc-ml via the gateway so v2 capture-verify hits the
        // live endpoint: base + relative "v2/kyc/doc/capture-verify" = /docai/v2/kyc/doc/capture-verify.
        // (Swap back to "http://192.168.4.126:8001/" to use a local doc-ml server.)
        MLRetrofitInstance.configure("https://api.skylinefare.com/docai/")

        setContent {
            VerityproandroidTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VerityProDemoApp()
                }
            }
        }
    }
}

// Fixed GUID "user id" for the demo. Biometric-only (liveness step-up) reuse requires vendorData to
// be a GUID that OWNS the prior document session (backend ownership check Guid.TryParse(VendorData)).
// A stable GUID lets a document run and a later liveness-only run resolve to the same user.
private const val DEMO_VENDOR_USER = "b9f1a3d2-4c6e-4a10-9b2f-7e5c8d1a2b34"

@Composable
fun VerityProDemoApp() {
    val context = LocalContext.current
    val activity = context as Activity
    val prefs = remember { context.getSharedPreferences("proto_demo", Activity.MODE_PRIVATE) }

    // LOCAL DEMO PREVIEW (uncommitted): render the B1 redesign flow (VerityFlowHost) so the new
    // design is visible on-device. Starts at the Welcome/consent screen.
    val showRedesign = remember { mutableStateOf(false) }
    // Product picker: which modules to request (drives the dynamic welcome intro + flow).
    val product = remember { mutableStateOf<List<String>?>(null) } // null = default (doc + selfie)
    if (showRedesign.value) {
        val mods = product.value ?: emptyList()
        val isBiometricOnly = mods.size == 1 && mods.contains("BIOMETRIC")
        // For a liveness step-up, supply the persisted session id from a prior document run.
        val priorSession = if (isBiometricOnly) prefs.getString("last_doc_session", null) else null
        ProtoVerificationScreen(
            options = VerityOption(
                apiKey = BuildConfig.API_KEY,
                integrationId = BuildConfig.INTEGRATION_ID,
                signingKey = null,
                placesApiKey = BuildConfig.PLACES_API_KEY,
                requiredModules = product.value,
                firstName = "Ade",
                lastName = "Oba",
                dateOfBirth = "1990-01-15T00:00:00.000Z",
                vendorData = DEMO_VENDOR_USER,
                isO2Code = "AU",
                streetAddress = null,
                previousEngineSessionId = priorSession,
            ),
            onExit = { showRedesign.value = false },
            // Persist the session id from document-bearing runs so a later liveness step-up can reuse it.
            onSessionEstablished = { sid -> prefs.edit().putString("last_doc_session", sid).apply() },
        )
        return
    }

    // Launcher that listens for result
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra("verification_result", LivenessResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra("verification_result") as? LivenessResult
        }
        Toast.makeText(context, "Result Success: ${value?.success}", Toast.LENGTH_LONG).show()

    }
    val options = VerityOption(
        apiKey = BuildConfig.API_KEY,
        integrationId = BuildConfig.INTEGRATION_ID,
        firstName = "Ade",
        lastName = "Oba",
        dateOfBirth = "1990-01-15T00:00:00.000Z",
        vendorData = "verity",
        isO2Code = "AU",
        streetAddress = null,
    )
    val sdk = remember { VerityPro(options, themeMode = ThemeMode.LIGHT) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Hello Verity Pro User",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Box(modifier = Modifier.height(16.dp))
        Button(onClick = {
            sdk.startVerification(launcher, activity)

        }) {
            Text(
                text = "Start Verification",
                fontWeight = FontWeight.Bold
            )
        }
        Box(modifier = Modifier.height(20.dp))
        Text("Preview new design — pick a product:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Box(modifier = Modifier.height(8.dp))
        listOf(
            "Document only" to listOf("DOCUMENT"),
            "Document + Selfie" to listOf("DOCUMENT", "BIOMETRIC"),
            "Biometric only (step-up)" to listOf("BIOMETRIC"),
            "Address" to listOf("ADDRESS"),
            "EDD" to listOf("EDD"),
            "Combined (all)" to listOf("DOCUMENT", "BIOMETRIC", "ADDRESS", "EDD"),
        ).forEach { (label, mods) ->
            Button(onClick = {
                val isBiometricOnly = mods.size == 1 && mods.contains("BIOMETRIC")
                // Liveness-only is a RETURNING-USER step-up: it face-matches the selfie against the
                // portrait from a prior document session, so the backend requires that prior session.
                // Guard here instead of firing a request the backend correctly rejects with HTTP 400.
                if (isBiometricOnly && prefs.getString("last_doc_session", null) == null) {
                    Toast.makeText(
                        context,
                        "Run a document verification first — liveness-only is a returning-user step-up.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    product.value = mods; showRedesign.value = true
                }
            }) {
                Text(label, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.height(8.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    Text("odkjn")
}