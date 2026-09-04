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
import com.example.veritypro_sdk.utils.VerityMode
import com.example.veritypro_sdk.utils.VerityOption
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding

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
    // Orchestration source: false = CLIENT-driven (on-device module queue), true = SERVER-driven
    // (backend /v2/sessions owns the flow via ServerFlowDriver). Lets us exercise BOTH paths from
    // the native demo without the Flutter layer.
    val serverMode = remember { mutableStateOf(false) }
    // vendorData identifies the subject. CLIENT mode keeps a FIXED GUID so a later liveness step-up
    // can face-match against the same subject's prior document session. SERVER mode uses a FRESH GUID
    // per test launch: the backend returns an EXISTING active session for a reused vendorData
    // (ignoring the newly-requested steps), so reusing one GUID made every server test inherit the
    // first session's steps (Address showed the Document module). A fresh GUID = a clean session that
    // honours exactly the requested steps. Set at launch time in launchProduct below.
    val vendorData = remember { mutableStateOf(DEMO_VENDOR_USER) }
    if (showRedesign.value) {
        val mods = product.value ?: emptyList()
        val isBiometricOnly = mods.size == 1 && mods.contains("BIOMETRIC")
        val isEddOnly = mods.size == 1 && mods.contains("EDD")
        // The prior session id to send as previousEngineSessionId:
        //  • CLIENT liveness step-up → the persisted v1 KYC session id (backend face-matches against it).
        //  • SERVER EDD-only / BIOMETRIC-only → the persisted v2 session id of a prior server run, so the
        //    backend runs the step STANDALONE (skips the identity prepend in AutoPromoteSteps).
        val priorSession = when {
            isBiometricOnly && !serverMode.value -> prefs.getString("last_doc_session", null)
            serverMode.value && (isBiometricOnly || isEddOnly) -> prefs.getString("srv_prev_session", null)
            else -> null
        }
        ProtoVerificationScreen(
            options = VerityOption(
                apiKey = BuildConfig.API_KEY,
                integrationId = BuildConfig.INTEGRATION_ID,
                signingKey = null,
                placesApiKey = BuildConfig.PLACES_API_KEY,
                requiredModules = product.value,
                // SERVER_DRIVEN routes ProtoVerificationScreen to ServerFlowDriver (backend owns the
                // step order); any other mode uses ClientFlowDriver (unchanged on-device behaviour).
                mode = if (serverMode.value) VerityMode.SERVER_DRIVEN.name else VerityMode.BIOMETRIC.name,
                firstName = "Ade",
                lastName = "Oba",
                dateOfBirth = "1990-01-15T00:00:00.000Z",
                vendorData = vendorData.value,
                isO2Code = "AU",
                streetAddress = null,
                previousEngineSessionId = priorSession,
            ),
            onExit = { showRedesign.value = false; serverMode.value = false },
            // Persist the session id from document-bearing CLIENT runs so a later liveness step-up can reuse it.
            onSessionEstablished = { sid -> prefs.edit().putString("last_doc_session", sid).apply() },
            // Persist a DOCUMENT-bearing SERVER session's v2 id so a later 🌐 EDD-only / 🌐 Biometric-only
            // run can pass it as previousEngineSessionId and run standalone (returning-user path).
            onServerSessionEstablished = { sid ->
                if (product.value?.any { it.equals("DOCUMENT", true) } == true) {
                    prefs.edit().putString("srv_prev_session", sid).apply()
                }
            },
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.Top,
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

        // The same six products run through EITHER orchestration source, so we can compare
        // client-driven vs server-driven behaviour on-device from one screen.
        val products = listOf(
            "Document only" to listOf("DOCUMENT"),
            "Document + Selfie" to listOf("DOCUMENT", "BIOMETRIC"),
            "Biometric only (step-up)" to listOf("BIOMETRIC"),
            "Address" to listOf("ADDRESS"),
            "EDD" to listOf("EDD"),
            "Combined (all)" to listOf("DOCUMENT", "BIOMETRIC", "ADDRESS", "EDD"),
        )

        // Shared launch logic: guard the returning-user step-up, pick the right vendorData for the
        // orchestration source, then enter the flow.
        val launchProduct: (List<String>, Boolean) -> Unit = { mods, useServer ->
            val isBiometricOnly = mods.size == 1 && mods.contains("BIOMETRIC")
            if (useServer) {
                // EDD-only and BIOMETRIC-only are RETURNING-USER steps: the backend runs them standalone
                // only when a prior session is supplied (else it prepends identity / rejects biometric).
                // Require a prior 🌐 server run that established identity (persisted as srv_prev_session).
                val isEddOnly = mods.size == 1 && mods.contains("EDD")
                val needsPrior = isBiometricOnly || isEddOnly
                if (needsPrior && prefs.getString("srv_prev_session", null) == null) {
                    Toast.makeText(
                        context,
                        "Run a 🌐 server-driven Document (or Document + Selfie) first — 🌐 EDD and 🌐 Liveness-only are returning-user steps.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    // FRESH GUID per server launch → a clean session that honours exactly these steps
                    // (the backend returns an existing active session for a reused vendorData). The prior
                    // server session is linked via previousEngineSessionId (set in the render block above).
                    vendorData.value = java.util.UUID.randomUUID().toString()
                    product.value = mods; serverMode.value = true; showRedesign.value = true
                }
            } else {
                // CLIENT mode: FIXED GUID so a later liveness step-up face-matches the same subject.
                // Liveness-only is a RETURNING-USER step-up: it needs the prior document session, so
                // guard here instead of firing a request the backend correctly rejects with HTTP 400.
                if (isBiometricOnly && prefs.getString("last_doc_session", null) == null) {
                    Toast.makeText(
                        context,
                        "Run a document verification first — liveness-only is a returning-user step-up.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    vendorData.value = DEMO_VENDOR_USER
                    product.value = mods; serverMode.value = false; showRedesign.value = true
                }
            }
        }

        Box(modifier = Modifier.height(20.dp))
        Text("CLIENT-driven (on-device flow):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Box(modifier = Modifier.height(8.dp))
        products.forEach { (label, mods) ->
            Button(onClick = { launchProduct(mods, false) }) {
                Text(label, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.height(8.dp))
        }

        Box(modifier = Modifier.height(16.dp))
        Text("SERVER-driven (/v2/sessions backend flow):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Box(modifier = Modifier.height(8.dp))
        products.forEach { (label, mods) ->
            Button(onClick = { launchProduct(mods, true) }) {
                Text("🌐 $label", fontWeight = FontWeight.Bold)
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