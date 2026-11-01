package app.dandle.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.dandle.data.crypto.PassphraseVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed DEK vault (brief 05 §5.2): after login the account DEK is wrapped
 * with a hardware-bound Keystore key, so sessions survive app restarts without re-prompting
 * the passphrase — while the server never sees anything but its own wrapped envelope.
 * A stolen device cannot unwrap (Keystore key is non-exportable).
 */
class KeystoreVault : PassphraseVault {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun wrap(dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(dek)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "dandle-cloud-dek"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
