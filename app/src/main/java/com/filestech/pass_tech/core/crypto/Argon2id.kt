package com.filestech.pass_tech.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id, version 0x13 (RFC 9106), through BouncyCastle.
 *
 * Checked against the RFC 9106 vector and against the outputs of the Flutter app (2.7.1), which the
 * reference C implementation (argon2-cffi) reproduces too: see `Argon2idTest`.
 *
 * Slow on purpose (hundreds of milliseconds with the default parameters): never call it on the main
 * thread.
 */
object Argon2id {

    /**
     * @param password the password bytes, UTF-8 without Unicode normalisation, as in the Flutter app.
     * The caller owns this array and wipes it.
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        params: KdfParams,
        secret: ByteArray? = null,
        associatedData: ByteArray? = null,
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
        if (secret != null) builder.withSecret(secret)
        if (associatedData != null) builder.withAdditional(associatedData)

        val out = ByteArray(params.outputLength)
        Argon2BytesGenerator().apply {
            init(builder.build())
            generateBytes(password, out)
        }
        return out
    }
}
