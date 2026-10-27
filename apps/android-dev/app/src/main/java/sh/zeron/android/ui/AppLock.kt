package sh.zeron.android.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** The phone's own unlock (biometrics, falling back to the screen lock where Android allows it). */
object AppLock {
    /** Biometrics and the screen lock together need Android 11; before that, biometrics alone. */
    private fun authenticators() = if (android.os.Build.VERSION.SDK_INT >= 30) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

    fun available(activity: FragmentActivity) =
        BiometricManager.from(activity).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Zeron")
            .setAllowedAuthenticators(authenticators())
            .apply { if (android.os.Build.VERSION.SDK_INT < 30) setNegativeButtonText("Cancel") }
            .build()
        prompt.authenticate(info)
    }
}

/** Covers the app until the user unlocks; asks as soon as it shows. */
@Composable
fun LockScreen(activity: FragmentActivity, onUnlocked: () -> Unit) {
    LaunchedEffect(Unit) { AppLock.prompt(activity, onUnlocked) }
    Box(
        Modifier.fillMaxSize().background(Z.background).clickable { AppLock.prompt(activity, onUnlocked) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.clip(CircleShape).background(Z.controlFill).padding(22.dp)) {
                Icon(Icons.Rounded.Lock, null, tint = Z.text)
            }
            Text("Zeron is locked", color = Z.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Tap to unlock", color = Z.subline, fontSize = 14.sp, modifier = Modifier.padding(bottom = 40.dp))
        }
    }
}
