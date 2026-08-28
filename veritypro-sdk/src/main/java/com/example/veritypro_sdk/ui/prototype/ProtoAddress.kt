package com.example.veritypro_sdk.ui.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.delay

/**
 * Address module — step 1: the customer enters/confirms the address being verified. This registers
 * the address-verification session (createAddressVerification needs the street) before the proof
 * document is uploaded.
 *
 * When [placesApiKey] is non-blank, Google Places predictions appear INLINE in a dropdown below the
 * field as the user types (no full-screen overlay); tapping a suggestion fills the field. The field
 * is always manually editable, and when no key is configured it is a plain manual field.
 */
@Composable
fun ProtoAddressEntryScreen(
    initial: String = "",
    submitting: Boolean,
    errorMsg: String?,
    placesApiKey: String? = null,
    countryIso2: String? = null,
    onSubmit: (street: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val placesEnabled = !placesApiKey.isNullOrBlank()

    // Initialise Places + build the client synchronously so it's ready before the first query.
    val placesClient: PlacesClient? = remember(placesEnabled) {
        if (!placesEnabled) null
        else {
            if (!Places.isInitialized()) runCatching { Places.initialize(context.applicationContext, placesApiKey!!) }
            runCatching { Places.createClient(context) }.getOrNull()
        }
    }
    val sessionToken = remember { runCatching { AutocompleteSessionToken.newInstance() }.getOrNull() }

    var street by remember { mutableStateOf(initial) }
    var predictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    // Set true right after a suggestion is picked so the resulting text change doesn't re-query.
    var justPicked by remember { mutableStateOf(false) }

    // Debounced inline autocomplete: re-runs (and cancels the prior debounce) on every keystroke.
    LaunchedEffect(street) {
        if (placesClient == null) return@LaunchedEffect
        if (justPicked) { justPicked = false; return@LaunchedEffect }
        val q = street.trim()
        if (q.length < 3) { predictions = emptyList(); return@LaunchedEffect }
        delay(300)  // debounce
        val builder = FindAutocompletePredictionsRequest.builder().setQuery(q)
        if (sessionToken != null) builder.setSessionToken(sessionToken)
        if (!countryIso2.isNullOrBlank()) builder.setCountries(listOf(countryIso2))
        placesClient.findAutocompletePredictions(builder.build())
            .addOnSuccessListener { resp -> predictions = resp.autocompletePredictions.take(5) }
            .addOnFailureListener { predictions = emptyList() }
    }

    Column(Modifier.fillMaxSize().background(Proto.Canvas).verticalScroll(rememberScrollState())) {
        ProtoTopBar(step = null, onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            MonoLabel("ADDRESS", Proto.Brand, size = 12)
            Spacer(Modifier.height(12.dp))
            Text(
                "Your address", color = Proto.Ink, fontFamily = ProtoDisplay,
                fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, lineHeight = 36.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter the residential address shown on your proof of address.",
                color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))

            MonoLabel("STREET ADDRESS", Proto.Sub, size = 11)
            Spacer(Modifier.height(10.dp))
            BrutalBox(background = Color.White, shadow = false) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp)) {
                    if (street.isEmpty()) {
                        Text(
                            if (placesEnabled) "Start typing your address" else "12 Example St, Suburb, 2000",
                            color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 16.sp,
                        )
                    }
                    BasicTextField(
                        value = street,
                        onValueChange = { street = it },
                        singleLine = false,
                        textStyle = TextStyle(color = Proto.Ink, fontFamily = ProtoDisplay,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(Proto.Brand),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Inline suggestions dropdown — appears directly below the field as the user types.
            if (predictions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    predictions.forEach { p ->
                        val primary = p.getPrimaryText(null).toString()
                        val secondary = p.getSecondaryText(null).toString()
                        BrutalBox(background = Color.White, shadow = false) {
                            Row(
                                Modifier.fillMaxWidth().protoClick {
                                    street = p.getFullText(null).toString()
                                    justPicked = true
                                    predictions = emptyList()
                                }.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(10.dp).height(10.dp).background(Proto.Brand))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(primary, color = Proto.Ink, fontFamily = ProtoDisplay,
                                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    if (secondary.isNotBlank()) {
                                        Text(secondary, color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                MonoLabel("POWERED BY GOOGLE", Proto.Sub, size = 9)
            }

            Spacer(Modifier.height(14.dp))
            when {
                submitting -> MonoLabel("SETTING UP…", Proto.Amber, size = 11)
                errorMsg != null -> Text(errorMsg, color = Proto.Danger, fontFamily = ProtoDisplay,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
            ProtoPrimaryButton(
                label = "Continue",
                enabled = street.trim().length >= 4 && !submitting,
                background = Proto.Brand,
                onClick = { onSubmit(street.trim()) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
