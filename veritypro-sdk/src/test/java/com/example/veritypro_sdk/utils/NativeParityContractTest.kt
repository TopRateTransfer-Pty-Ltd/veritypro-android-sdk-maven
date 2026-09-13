package com.example.veritypro_sdk.utils

import com.example.veritypro_sdk.ui.prototype.selfieIntroCopy
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NativeParityContractTest {
    @Test fun `liveness only never describes document matching`() {
        val copy = selfieIntroCopy(false)
        assertFalse(copy.contains("document", ignoreCase=true))
        assertFalse(copy.contains("No photos"))
    }
    @Test fun `primary action has actual disabled semantics and exit touch target`() {
        val root = File("src/main/java/com/example/veritypro_sdk/ui/prototype")
        val design = File(root, "ProtoDesign.kt").readText()
        assertTrue(design.contains("enabled = enabled"))
        assertTrue(design.contains("Role.Button"))
        assertTrue(design.contains("48.dp"))
        val screen = File(root, "ProtoVerificationScreen.kt").readText()
        assertTrue(screen.contains(".size(48.dp)"))
        assertFalse(screen.substringAfter("ProtoStage.AddressEntry ->").substringBefore("ProtoStage.AddressUpload ->").contains("vm.createAddressVerification"))
    }
}
