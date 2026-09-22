package com.filestech.pass_tech.core.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG

/** Whether this phone can authenticate with a strong biometric right now: hardware present, one enrolled. */
fun interface BiometricSupport {
    fun available(): Boolean

    companion object {
        fun of(context: Context) = BiometricSupport {
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        }
    }
}
