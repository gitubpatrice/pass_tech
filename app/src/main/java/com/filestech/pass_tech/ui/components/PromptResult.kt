package com.filestech.pass_tech.ui.components

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.filestech.pass_tech.R
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

sealed interface PromptResult {
    /** [cipher] may now be used once: the secure hardware saw the biometric. */
    class Authenticated(val cipher: Cipher) : PromptResult

    /** The user closed the prompt, or the system did: nothing failed, nothing to say (2.7.1). */
    data object Canceled : PromptResult

    /** Too many attempts, no biometric left, or the hardware refused. */
    data object Failed : PromptResult
}

/**
 * Shows the system biometric prompt for [cipher], strong biometrics only, with the same text for
 * arming and unlocking (2.7.1 builds it in one place so the two never diverge). A finger that is not
 * recognised leaves the prompt up for another try; only its end resumes.
 */
suspend fun FragmentActivity.authenticate(cipher: Cipher): PromptResult =
    suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticated = result.cryptoObject?.cipher
                    if (continuation.isActive) {
                        continuation.resume(if (authenticated != null) PromptResult.Authenticated(authenticated) else PromptResult.Failed)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) {
                        continuation.resume(if (errorCode in CANCELED) PromptResult.Canceled else PromptResult.Failed)
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_name))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.action_cancel))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }

private val CANCELED = setOf(
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
)
