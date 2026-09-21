package com.filestech.pass_tech.core.crypto

import com.filestech.pass_tech.testing.Resources
import com.filestech.pass_tech.testing.hex
import com.filestech.pass_tech.testing.unhex
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Known-answer tests. Every expected value below comes from a standard (RFC or NIST test vector) or
 * from the Flutter app itself, and was also recomputed with an independent implementation (Python
 * `cryptography` and `argon2-cffi`) before being written here.
 */
class CryptoVectorsTest {

    @Test
    fun `Argon2id matches the RFC 9106 test vector`() {
        val out = Argon2id.derive(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            params = KdfParams(memoryKiB = 32, iterations = 3, parallelism = 4),
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 },
        )
        assertThat(out.hex()).isEqualTo("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659")
    }

    @Test
    fun `Argon2id matches the outputs of the Flutter app 2_7_1`() {
        val cases = Json.parseToJsonElement(Resources.text("compat/2.7.1/argon2id_kat.json")).jsonArray
        assertThat(cases).hasSize(3)
        for (case in cases.map { it.jsonObject }) {
            val params = KdfParams(
                memoryKiB = case.getValue("m").jsonPrimitive.int,
                iterations = case.getValue("t").jsonPrimitive.int,
                parallelism = case.getValue("p").jsonPrimitive.int,
                outputLength = case.getValue("outLen").jsonPrimitive.int,
            )
            val out = Argon2id.derive(
                password = case.getValue("password").jsonPrimitive.content.encodeToByteArray(),
                salt = Base64.getDecoder().decode(case.getValue("salt").jsonPrimitive.content),
                params = params,
            )
            assertThat(out.hex()).isEqualTo(case.getValue("hex").jsonPrimitive.content)
        }
    }

    @Test
    fun `HKDF-SHA256 matches RFC 5869 test case 1`() {
        val okm = HkdfSha256.derive(
            salt = unhex("000102030405060708090a0b0c"),
            ikm = ByteArray(22) { 0x0b },
            info = unhex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertThat(okm.hex())
            .isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    }

    @Test
    fun `AES-256-GCM matches the GCM specification test cases 13, 14 and 16`() {
        val zeroKey = ByteArray(32)
        val zeroNonce = ByteArray(12)
        assertThat(AesGcm.encrypt(zeroKey, ByteArray(0), ByteArray(0), zeroNonce).cipherAndTag.hex())
            .isEqualTo("530f8afbc74536b9a963b4f1c4cb738b")
        assertThat(AesGcm.encrypt(zeroKey, ByteArray(16), ByteArray(0), zeroNonce).cipherAndTag.hex())
            .isEqualTo("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919")

        val key = unhex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val nonce = unhex("cafebabefacedbaddecaf888")
        val plain = unhex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
        )
        val aad = unhex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val sealed = AesGcm.encrypt(key, plain, aad, nonce)
        assertThat(sealed.cipherAndTag.hex()).isEqualTo(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
                "76fc6ece0f4e1768cddf8853bb2d551b",
        )
        assertThat(AesGcm.decryptOrNull(key, nonce, sealed.cipherAndTag, aad)).isEqualTo(plain)
    }

    @Test
    fun `AES-GCM refuses altered data, a different AAD and a different key`() {
        val key = ByteArray(32) { it.toByte() }
        val aad = "pt:v=4|alias=a".encodeToByteArray()
        val sealed = AesGcm.encrypt(key, "vault".encodeToByteArray(), aad)

        val flippedCipher = sealed.cipherAndTag.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val flippedTag = sealed.cipherAndTag.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val otherKey = key.copyOf().also { it[0] = 99 }

        assertThat(AesGcm.decryptOrNull(key, sealed.nonce, sealed.cipherAndTag, aad)).isEqualTo("vault".encodeToByteArray())
        assertThat(AesGcm.decryptOrNull(key, sealed.nonce, flippedCipher, aad)).isNull()
        assertThat(AesGcm.decryptOrNull(key, sealed.nonce, flippedTag, aad)).isNull()
        assertThat(AesGcm.decryptOrNull(key, sealed.nonce, sealed.cipherAndTag, "pt:v=4|alias=b".encodeToByteArray())).isNull()
        assertThat(AesGcm.decryptOrNull(otherKey, sealed.nonce, sealed.cipherAndTag, aad)).isNull()
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 matches RFC 7914 section 11`() {
        assertThat(LegacyCrypto.pbkdf2HmacSha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64).hex())
            .isEqualTo(
                "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                    "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            )
        assertThat(LegacyCrypto.pbkdf2HmacSha256("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80_000, 64).hex())
            .isEqualTo(
                "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                    "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            )
    }

    @Test
    fun `HMAC-SHA256 matches RFC 4231 test cases 1, 2 and 4`() {
        assertThat(LegacyCrypto.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).hex())
            .isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
        assertThat(LegacyCrypto.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).hex())
            .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
        assertThat(LegacyCrypto.hmacSha256(ByteArray(25) { (it + 1).toByte() }, ByteArray(50) { 0xcd.toByte() }).hex())
            .isEqualTo("82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b")
    }

    @Test
    fun `HMAC and PBKDF2 accept an empty key, as HMAC defines it`() {
        // HMAC pads the key with zeros to the block size, so "" and "\u0000" are the same key.
        val empty = LegacyCrypto.hmacSha256(ByteArray(0), "x".encodeToByteArray())
        val zero = LegacyCrypto.hmacSha256(ByteArray(1), "x".encodeToByteArray())
        assertThat(empty.hex()).isEqualTo(zero.hex())
        assertThat(LegacyCrypto.pbkdf2HmacSha256(ByteArray(0), "s".encodeToByteArray(), 2, 32)).hasLength(32)
    }

    @Test
    fun `read bounds refuse what would exhaust the device`() {
        assertThat(KdfParams.validatedOrNull(19_456, 2, 1)).isEqualTo(KdfParams.OWASP_MOBILE_2024)
        assertThat(KdfParams.validatedOrNull(KdfParams.MIN_MEMORY_KIB - 1, 2, 1)).isNull()
        assertThat(KdfParams.validatedOrNull(KdfParams.MAX_MEMORY_KIB + 1, 2, 1)).isNull()
        assertThat(KdfParams.validatedOrNull(1_048_576, 2, 1)).isNull()
        assertThat(KdfParams.validatedOrNull(19_456, 0, 1)).isNull()
        assertThat(KdfParams.validatedOrNull(19_456, KdfParams.MAX_ITERATIONS + 1, 1)).isNull()
        assertThat(KdfParams.validatedOrNull(19_456, 2, 0)).isNull()
        assertThat(KdfParams.validatedOrNull(19_456, 2, KdfParams.MAX_PARALLELISM + 1)).isNull()
    }
}
