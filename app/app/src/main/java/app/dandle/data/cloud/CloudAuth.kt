package app.dandle.data.cloud

/**
 * Dandle Cloud auth boundary (brief 05 §5): PocketBase account auth + passphrase verification
 * in one step. A sign-in only succeeds when the passphrase unwraps the account's stored DEK —
 * a wrong passphrase is [CloudError.WRONG_PASSPHRASE], distinct from bad login credentials.
 */
interface CloudAuth {
    /**
     * Creates the account. [contactEmail] is the OPTIONAL contact address (brief 06 §2) —
     * plain metadata for service-change warnings, NOT a login identity and never verified;
     * pass an empty string to omit it entirely from the createUser body.
     */
    suspend fun signUp(
        username: String,
        password: String,
        passphrase: String,
        contactEmail: String = "",
    ): CloudLoginResult
    suspend fun signIn(username: String, password: String, passphrase: String): CloudLoginResult
}

sealed interface CloudLoginResult {
    /** token = PB auth token; envelope = serialized KDF+wrapped-DEK blob (stored client-side). */
    data class Success(val token: String, val envelope: String, val username: String, val userId: String, val dek: ByteArray) : CloudLoginResult
    data class Failure(val error: CloudError) : CloudLoginResult
}

sealed interface CloudError {
    data object UNREACHABLE : CloudError
    data object BAD_CREDENTIALS : CloudError
    data object USERNAME_TAKEN : CloudError
    data object WRONG_PASSPHRASE : CloudError
    data class HTTP(val status: Int) : CloudError
}
