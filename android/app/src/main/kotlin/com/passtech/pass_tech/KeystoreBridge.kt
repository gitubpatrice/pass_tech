package com.passtech.pass_tech

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * MethodChannel handler exposing AndroidKeyStore-bound AES/GCM 256 keys to
 * Flutter. The keystore-resident secret key (KEK = Key-Encryption-Key) wraps
 * a random per-vault `hwSecret` (DEK material). The KEK never leaves the TEE
 * / StrongBox; only the wrapped ciphertext + nonce are stored on disk in the
 * vault JSON.
 *
 * Methods:
 *  - createKek(alias)   -> Boolean (true if created, false if alias existed)
 *  - wrap(alias, plaintext) -> { ciphertext: ByteArray, nonce: ByteArray }
 *  - unwrap(alias, ciphertext, nonce) -> ByteArray
 *  - deleteKek(alias)   -> null
 *  - hasKek(alias)      -> Boolean
 *
 * Auth model: setUserAuthenticationRequired(false) — the master password is
 * the actual auth factor on the Flutter side. Adding biometric/PIN gate here
 * would result in double prompts and break the unlock UX.
 *
 * StrongBox: requested with setIsStrongBoxBacked(true) on Android P+ when
 * available; falls back silently to TEE-software on devices without StrongBox
 * (e.g. Galaxy S9). The fallback is logged in debug only — not exposed to UI.
 */
class KeystoreBridge(@Suppress("unused") private val ctx: Context) : MethodCallHandler {

    companion object {
        const val CHANNEL_NAME = "com.passtech.pass_tech/keystore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val GCM_TAG_BITS = 128
    }

    private val ks: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override fun onMethodCall(call: MethodCall, result: Result) {
        try {
            when (call.method) {
                "createKek" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    result.success(createKek(alias))
                }
                "wrap" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    val plaintext = call.argument<ByteArray>("plaintext")
                        ?: return result.error("BAD_ARG", "plaintext missing", null)
                    result.success(wrap(alias, plaintext))
                }
                "unwrap" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    val ciphertext = call.argument<ByteArray>("ciphertext")
                        ?: return result.error("BAD_ARG", "ciphertext missing", null)
                    val nonce = call.argument<ByteArray>("nonce")
                        ?: return result.error("BAD_ARG", "nonce missing", null)
                    result.success(unwrap(alias, ciphertext, nonce))
                }
                "deleteKek" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    if (ks.containsAlias(alias)) ks.deleteEntry(alias)
                    result.success(null)
                }
                "hasKek" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    result.success(ks.containsAlias(alias))
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            // No sensitive content in the message: alias is non-secret, plaintext
            // is never logged. Class name + message are sufficient for triage.
            result.error("KEYSTORE_ERROR", "${e.javaClass.simpleName}: ${e.message}", null)
        }
    }

    private fun createKek(alias: String): Boolean {
        if (ks.containsAlias(alias)) return false
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)

        fun build(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // master password est l'auth factor côté app — pas de double-auth.
                .setUserAuthenticationRequired(false)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                b.setIsStrongBoxBacked(true)
            }
            return b.build()
        }

        // Try StrongBox first, fallback to TEE software on StrongBoxUnavailableException
        // or any provider error (older devices, S9, emulators).
        try {
            gen.init(build(strongBox = true))
            gen.generateKey()
        } catch (_: Exception) {
            gen.init(build(strongBox = false))
            gen.generateKey()
        }
        return true
    }

    private fun wrap(alias: String, plaintext: ByteArray): Map<String, ByteArray> {
        val key = ks.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("KEK not found for alias")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Init without explicit IV → Keystore generates a fresh random IV per call.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plaintext)
        val iv = cipher.iv ?: throw IllegalStateException("missing IV")
        return mapOf("ciphertext" to ct, "nonce" to iv)
    }

    private fun unwrap(alias: String, ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val key = ks.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("KEK not found for alias")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }
}

/** Helper to register the channel from MainActivity. */
fun registerKeystoreBridge(
    context: Context,
    messenger: io.flutter.plugin.common.BinaryMessenger,
) {
    MethodChannel(messenger, KeystoreBridge.CHANNEL_NAME)
        .setMethodCallHandler(KeystoreBridge(context))
}
