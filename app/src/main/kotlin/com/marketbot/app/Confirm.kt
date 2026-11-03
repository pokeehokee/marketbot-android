package com.marketbot.app

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Fingerprint, face or screen-lock confirmation before anything that moves real
 * money or loosens a safety: arming live, turning the kill switch off, moving
 * cash, changing keys. Someone holding an unlocked phone cannot do these alone.
 */
object Confirm {
    fun enabled(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("confirm_live", true)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("confirm_live", on).apply()

    private val authenticators get() = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

    /** Whether the phone has a fingerprint, face or screen lock to confirm with. */
    fun available(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun ask(activity: FragmentActivity, title: String, onOk: () -> Unit, onFail: (String) -> Unit = {}) {
        if (!enabled(activity) || !available(activity)) { onOk(); return }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("Confirm it's you").setAllowedAuthenticators(authenticators)
            .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText("Cancel") }.build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            // Cancelled, locked out or unavailable: say so, or the action just silently does not happen.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail("Not confirmed: $errString")
        }).authenticate(info)
    }
}

private tailrec fun Context.activity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** `confirm("Arm live trading") { ... }`: runs the block after the user confirms (or at once without a screen lock). */
@Composable
fun rememberConfirm(): (String, () -> Unit) -> Unit {
    val ctx = LocalContext.current
    return { title, block -> ctx.activity()?.let { Confirm.ask(it, title, block) } ?: block() }
}

/** As [rememberConfirm], with a callback when the confirmation is cancelled or fails. */
@Composable
fun rememberConfirmOr(): (String, (String) -> Unit, () -> Unit) -> Unit {
    val ctx = LocalContext.current
    return { title, onFail, block -> ctx.activity()?.let { Confirm.ask(it, title, block, onFail) } ?: block() }
}
