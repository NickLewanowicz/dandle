package app.dandle.data.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side crypto for Dandle Cloud (brief 05 §5.2). Pure JVM (BC lightweight API +
 * javax.crypto, NO android.*, never a registered JCE provider) so every primitive is
 * unit-testable with the plain-JVM suite.
 *
 * Threat model: protects event contents against the server/host/operator. A compromised
 * unlocked device is OUT of scope (Keystore + app sandbox are that boundary). Lost
 * passphrase = data unrecoverable — the login copy says so.
 */
object DandleCrypto {

    /** KDF + envelope format version — bump on any param/algorithm change (envelope carries it). */
    const val CRYPTO_VERSION = 1

    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val GCM_TAG_BITS = 128

    /**
     * Argon2id starting params (brief 05 §5.2): m=64 MiB, t=3, p=1 → ~0.5–2 s on mid-range
     * phones. Call OFF the main thread. The params are versioned INSIDE the envelope, so a
     * future bump cannot brick stored envelopes.
     */
    data class KdfParams(val memoryKib: Int = 64 * 1024, val iterations: Int = 3, val parallelism: Int = 1)

    fun deriveKey(passphrase: CharArray, salt: ByteArray, params: KdfParams = KdfParams()): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(params.memoryKib)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .build()
        )
        val out = ByteArray(KEY_BYTES)
        generator.generateBytes(passphrase, out)
        return out
    }
}

/**
 * The account key envelope (brief 05 §5.2): a random per-account DEK wrapped with the
 * Argon2id(passphrase)-derived key. Serialized to JSON and stored in DataStore; the same
 * bytes live (plaintext-side) in the users collection so another device can unwrap.
 * AAD binds the DEK to its purpose — envelope ciphertexts cannot be spliced into other roles.
 */
data class DekEnvelope(
    val version: Int,
    val kdf: DandleCrypto.KdfParams,
    val salt: ByteArray,
    /** AES-256-GCM ciphertext of the DEK: nonce ‖ tag-included-ciphertext. */
    val wrappedDek: ByteArray,
) {
    fun toJson(): String = jsonString(this)

    companion object {
        fun fromJson(text: String): DekEnvelope = jsonParse(text)

        fun create(passphrase: CharArray, params: DandleCrypto.KdfParams = DandleCrypto.KdfParams()): Pair<DekEnvelope, SecretKey> {
            val salt = ByteArray(DandleCrypto.SALT_BYTES).also(java.security.SecureRandom()::nextBytes)
            val kek = DandleCrypto.deriveKey(passphrase, salt, params)
            val dek = ByteArray(DandleCrypto.KEY_BYTES).also(java.security.SecureRandom()::nextBytes)
            val wrapped = aesGcmEncrypt(kek, dek, aad = "dandle:v${DandleCrypto.CRYPTO_VERSION}:dek")
            return DekEnvelope(DandleCrypto.CRYPTO_VERSION, params, salt, wrapped) to SecretKeySpec(dek, "AES")
        }

        /** Unwraps the DEK; a wrong passphrase throws [WrongPassphraseException] (GCM tag failure). */
        fun unwrap(envelope: DekEnvelope, passphrase: CharArray): SecretKey {
            val kek = DandleCrypto.deriveKey(passphrase, envelope.salt, envelope.kdf)
            val dek = try {
                aesGcmDecrypt(kek, envelope.wrappedDek, aad = "dandle:v${envelope.version}:dek")
            } catch (_: Exception) {
                throw WrongPassphraseException()
            }
            return SecretKeySpec(dek, "AES")
        }
    }
}

class WrongPassphraseException : Exception("passphrase did not unwrap the DEK")

/**
 * Per-record encryption (brief 05 §5.2). Key = HKDF-SHA256(DEK, info="dandle:v1:record") —
 * records never reuse the DEK directly. AAD binds EVERY ciphertext to its collection+record
 * id, so ciphertexts cannot be swapped between rows (the classic E2E bug — dedicated test).
 */
object RecordCipher {

    @Volatile
    private var dek: ByteArray? = null

    fun install(dek: ByteArray) {
        this.dek = dek
    }

    fun reset() {
        dek = null
    }

    private fun recordKey(): javax.crypto.SecretKey {
        val current = checkNotNull(dek) { "DEK not installed" }
        return SecretKeySpec(hkdf(current, "dandle:v${DandleCrypto.CRYPTO_VERSION}:record"), "AES")
    }

    fun encrypt(plaintext: ByteArray, collection: String, recordId: String): ByteArray {
        val aad = "dandle:v${DandleCrypto.CRYPTO_VERSION}:$collection:$recordId"
        return aesGcmEncrypt(recordKey().encoded, plaintext, aad)
    }

    fun decrypt(ciphertext: ByteArray, collection: String, recordId: String): ByteArray {
        val aad = "dandle:v${DandleCrypto.CRYPTO_VERSION}:$collection:$recordId"
        return aesGcmDecrypt(recordKey().encoded, ciphertext, aad)
    }

    /** RFC 5869 HKDF-SHA256, via BC (test vectors pin it). */
    fun hkdf(ikm: ByteArray, info: String, length: Int = DandleCrypto.KEY_BYTES): ByteArray =
        Hkdf.derive(ikm, info = info.toByteArray(), length = length)
}

/** RFC 5869 HKDF-SHA256 (extract-then-expand) — BC's HKDFBytesGenerator under the hood. */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray? = null, info: ByteArray, length: Int): ByteArray {
        val generator = org.bouncycastle.crypto.generators.HKDFBytesGenerator(
            org.bouncycastle.crypto.digests.SHA256Digest()
        )
        generator.init(org.bouncycastle.crypto.params.HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }
}

// -- AES-256-GCM primitives ---------------------------------------------------

/** Output layout: 12-byte random nonce ‖ ciphertext+tag. Fresh nonce per call (brief 05 §5.2). */
internal fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray, aad: String): ByteArray {
    val nonce = ByteArray(DandleCrypto.NONCE_BYTES)
        .also(java.security.SecureRandom()::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(DandleCrypto.GCM_TAG_BITS, nonce))
    cipher.updateAAD(aad.toByteArray())
    val encrypted = cipher.doFinal(plaintext)
    return nonce + encrypted
}

internal fun aesGcmDecrypt(key: ByteArray, sealed: ByteArray, aad: String): ByteArray {
    val nonce = sealed.copyOfRange(0, DandleCrypto.NONCE_BYTES)
    val ciphertext = sealed.copyOfRange(DandleCrypto.NONCE_BYTES, sealed.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(DandleCrypto.GCM_TAG_BITS, nonce))
    cipher.updateAAD(aad.toByteArray())
    return cipher.doFinal(ciphertext)
}

// -- Envelope JSON (tiny hand-rolled codec: 4 fields, no model dependency) ----

private fun jsonString(e: DekEnvelope): String =
    """{"version":${e.version},"m":${e.kdf.memoryKib},"t":${e.kdf.iterations},"p":${e.kdf.parallelism},""" +
        """"salt":"${b64(e.salt)}","wrapped":"${b64(e.wrappedDek)}"}"""

private fun jsonParse(text: String): DekEnvelope {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(text)
        as kotlinx.serialization.json.JsonObject
    fun str(key: String) = (obj[key] as kotlinx.serialization.json.JsonPrimitive).content
    fun int(key: String) = (obj[key] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
    return DekEnvelope(
        version = int("version"),
        kdf = DandleCrypto.KdfParams(memoryKib = int("m"), iterations = int("t"), parallelism = int("p")),
        salt = unb64(str("salt")),
        wrappedDek = unb64(str("wrapped")),
    )
}

internal fun b64(bytes: ByteArray): String =
    java.util.Base64.getEncoder().encodeToString(bytes)

internal fun unb64(text: String): ByteArray =
    java.util.Base64.getDecoder().decode(text)
