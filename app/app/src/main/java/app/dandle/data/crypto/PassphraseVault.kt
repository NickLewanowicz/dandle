package app.dandle.data.crypto

/** Where does the cloud DEK live at rest (brief 05 §5.2)? Impl: Android Keystore (android layer). */
interface PassphraseVault {
    fun wrap(dek: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}
