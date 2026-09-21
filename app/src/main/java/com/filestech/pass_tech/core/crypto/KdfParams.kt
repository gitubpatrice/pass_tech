package com.filestech.pass_tech.core.crypto

/**
 * Argon2id parameters, written into every Pass Tech file format (vault, heir snapshot, `.ptbak`
 * backup) and read back from it.
 *
 * The value used to WRITE and the values ACCEPTED on read are two different things, on purpose: a
 * file is always opened with the parameters it carries. If the write value is raised one day, the
 * files that already exist keep opening. The Flutter app learnt this the hard way (audit
 * 2026-08-03): it once derived with its compile-time constants whatever the file said.
 */
data class KdfParams(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val outputLength: Int = DEFAULT_OUTPUT_LENGTH,
) {
    companion object {
        const val DEFAULT_OUTPUT_LENGTH = 32

        /** OWASP 2024 baseline for a password manager on mobile. The WRITE value only. */
        val OWASP_MOBILE_2024 = KdfParams(memoryKiB = 19_456, iterations = 2, parallelism = 1)

        // Read bounds. They do not say what is recommended, only what a file may ask for without
        // endangering the device: a hostile file announcing m = 1 GiB or t = 64 would exhaust
        // memory and CPU just by being opened.
        //
        // The memory cap is 64 MiB, LOWER than the 1 GiB of the Flutter app, on purpose.
        // BouncyCastle allocates Argon2 memory on the Java heap, which is a few hundred MiB at most
        // on a phone: a backup announcing 1 GiB would have crashed the app on OutOfMemoryError, not
        // been refused. No Pass Tech file was ever written above 19 MiB, so nothing legitimate is lost.
        const val MIN_MEMORY_KIB = 4_096
        const val MAX_MEMORY_KIB = 65_536
        const val MAX_ITERATIONS = 16
        const val MAX_PARALLELISM = 4

        /** Validates parameters read from a file. `null` means the file must be refused (fail closed). */
        fun validatedOrNull(memoryKiB: Int, iterations: Int, parallelism: Int): KdfParams? =
            KdfParams(memoryKiB, iterations, parallelism).takeIf {
                memoryKiB in MIN_MEMORY_KIB..MAX_MEMORY_KIB &&
                    iterations in 1..MAX_ITERATIONS &&
                    parallelism in 1..MAX_PARALLELISM
            }
    }
}
