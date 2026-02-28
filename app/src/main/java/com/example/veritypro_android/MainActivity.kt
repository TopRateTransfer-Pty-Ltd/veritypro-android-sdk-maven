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
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.utils.LivenessResult
import com.example.veritypro_sdk.utils.VerityOption

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

@Composable
fun VerityProDemoApp() {
    val context = LocalContext.current
    val activity = context as Activity

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
        apiKey = "1MXKwMkTUBtgVdoEDCJDtRLT8ioV7Zha",
        integrationId = "9c233c40-c3ad-4fb2-913d-9a75dafc4d69",
        //apiKey = BuildConfig.API_KEY,
        //integrationId = BuildConfig.INTEGRATION_ID,
        firstName = "Ade",
        lastName = "Oba",
        dateOfBirth = "1990-01-15T00:00:00.000Z",
        vendorData = "verity",
        isO2Code = "NG",
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
    }
}

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    Text("odkjn")
}