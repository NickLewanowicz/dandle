package app.dandle.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crypto contract (brief 05 §5.2). These pin the details most likely to fail silently in
 * production: HKDF RFC vectors, envelope round-trip, wrong-passphrase distinctness, and the
 * AAD binding that makes ciphertexts non-swappable between records.
 */
class CryptoTest {

    private val passphrase = "correct horse battery staple".toCharArray()

    @Test
    fun `HKDF-SHA256 matches the RFC 5869 test case 1 vector`() {
        // RFC 5869 A.1: IKM=0x0b x22, salt=0x000102...0c, info=f0f1..f9, L=42.
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte(), 0x07.toByte(), 0x08.toByte(), 0x09.toByte(), 0x0a.toByte(), 0x0b.toByte(), 0x0c.toByte())
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        val okm = Hkdf.derive(ikm, salt = salt, info = info, length = 42)
        val expected = byteArrayOf(0x3c.toByte(), 0xb2.toByte(), 0x5f.toByte(), 0x25.toByte(), 0xfa.toByte(), 0xac.toByte(), 0xd5.toByte(), 0x7a.toByte(), 0x90.toByte(), 0x43.toByte(), 0x4f.toByte(), 0x64.toByte(), 0xd0.toByte(), 0x36.toByte(), 0x2f.toByte(), 0x2a.toByte(), 0x2d.toByte(), 0x2d.toByte(), 0x0a.toByte(), 0x90.toByte(), 0xcf.toByte(), 0x1a.toByte(), 0x5a.toByte(), 0x4c.toByte(), 0x5d.toByte(), 0xb0.toByte(), 0x2d.toByte(), 0x56.toByte(), 0xec.toByte(), 0xc4.toByte(), 0xc5.toByte(), 0xbf.toByte(), 0x34.toByte(), 0x00.toByte(), 0x72.toByte(), 0x08.toByte(), 0xd5.toByte(), 0xb8.toByte(), 0x87.toByte(), 0x18.toByte(), 0x58.toByte(), 0x65.toByte(), )
        assertArrayEquals(expected, okm)
    }

    @Test
    fun `Argon2id KDF is deterministic for the same inputs and salt`() {
        val salt = ByteArray(16) { it.toByte() }
        val a = DandleCrypto.deriveKey(passphrase, salt)
        val b = DandleCrypto.deriveKey(passphrase, salt)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
        val other = DandleCrypto.deriveKey("different".toCharArray(), salt)
        assertNotEquals(a.toList(), other.toList())
    }

    @Test
    fun `envelope round-trips through its JSON and unwraps the same DEK`() {
        val (envelope, dek) = DekEnvelope.create(passphrase)
        val restored = DekEnvelope.fromJson(envelope.toJson())
        assertEquals(envelope.version, restored.version)
        assertEquals(envelope.kdf, restored.kdf)
        assertArrayEquals(envelope.salt, restored.salt)
        assertArrayEquals(envelope.wrappedDek, restored.wrappedDek)

        val unwrapped = DekEnvelope.unwrap(restored, passphrase)
        assertArrayEquals(dek.encoded, unwrapped.encoded)
    }

    @Test
    fun `a wrong passphrase fails with WrongPassphraseException, never with garbage`() {
        val (envelope, _) = DekEnvelope.create(passphrase)
        val restored = DekEnvelope.fromJson(envelope.toJson())
        try {
            DekEnvelope.unwrap(restored, "wrong".toCharArray())
            throw AssertionError("unwrap must fail")
        } catch (e: WrongPassphraseException) {
            // exactly the expected failure
        }
    }

    @Test
    fun `record round-trip and the AAD binds ciphertext to its record id`() {
        val (_, dek) = DekEnvelope.create(passphrase)
        RecordCipher.install(dek.encoded)
        try {
            val plaintext = """{"type":"FEEDING","amount":120.0}""".toByteArray()
            val sealed = RecordCipher.encrypt(plaintext, "sync_records", "abc000000000001")
            assertArrayEquals(plaintext, RecordCipher.decrypt(sealed, "sync_records", "abc000000000001"))

            // Same ciphertext presented under a DIFFERENT record id must be rejected (GCM tag).
            try {
                RecordCipher.decrypt(sealed, "sync_records", "xyz999999999999")
                throw AssertionError("ciphertext swap must fail")
            } catch (_: Exception) {
            }

            // Tampered ciphertext must fail.
            val tampered = sealed.copyOf().also { it[sealed.size - 1] = (it[sealed.size - 1].toInt() xor 1).toByte() }
            try {
                RecordCipher.decrypt(tampered, "sync_records", "abc000000000001")
                throw AssertionError("tampering must fail")
            } catch (_: Exception) {
            }
        } finally {
            RecordCipher.reset()
        }
    }

    @Test
    fun `each encryption uses a fresh nonce (same plaintext, different ciphertext)`() {
        val (_, dek) = DekEnvelope.create(passphrase)
        RecordCipher.install(dek.encoded)
        try {
            val plaintext = "same".toByteArray()
            val a = RecordCipher.encrypt(plaintext, "sync_records", "rec1")
            val b = RecordCipher.encrypt(plaintext, "sync_records", "rec1")
            assertNotEquals(a.toList(), b.toList())
            assertArrayEquals(plaintext, RecordCipher.decrypt(a, "sync_records", "rec1"))
            assertArrayEquals(plaintext, RecordCipher.decrypt(b, "sync_records", "rec1"))
        } finally {
            RecordCipher.reset()
        }
    }

    @Test
    fun `Argon2id params take a measurable-but-bounded time (UI progress budget)`() {
        val start = System.currentTimeMillis()
        DandleCrypto.deriveKey(passphrase, ByteArray(16) { 1 })
        val elapsed = System.currentTimeMillis() - start
        // m=64MiB t=3 lands in the 0.5–2s window on this dev machine; CI can be slower.
        assertTrue("KDF took ${elapsed}ms — params or environment changed", elapsed < 10_000)
    }
}
