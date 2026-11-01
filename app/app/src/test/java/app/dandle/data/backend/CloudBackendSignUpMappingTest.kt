package app.dandle.data.backend

import app.dandle.data.cloud.CloudError
import app.dandle.data.cloud.CloudLoginResult
import app.dandle.data.cloud.PocketBaseClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloud create-account error mapping + wire shape (QA 2026-10-31):
 * - ONLY a genuine username COLLISION may read as USERNAME_TAKEN. The old mapping turned ANY
 *   400 mentioning "username" into USERNAME_TAKEN, so a pattern rejection (dotted names, under
 *   the old strict server pattern) dead-ended with a lying "name already taken" banner.
 * - The request path must never double-slash: the configured base ends in "/"
 *   (CLOUD_BASE_URL), and raw concatenation with "/api/…" produced "…//api/…".
 *
 * PocketBaseClient is final and network-bound (no fake seam), so this drives the REAL wire:
 * MockWebServer plays the PocketBase instance, same seam the login retention test uses for the
 * server flow. signUp runs one Argon2id derivation (~1s), same cost CryptoTest already pays.
 */
class CloudBackendSignUpMappingTest {

    private lateinit var server: MockWebServer

    /** PocketBase stand-in answering [status]/[body] for the users-records POST. */
    private fun backendAnswering(status: Int, body: String): CloudBackend {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/collections/users/records" && request.method == "POST") {
                    MockResponse().setResponseCode(status).setBody(body)
                } else {
                    MockResponse().setResponseCode(404).setBody("""{"code":404}""")
                }
        }
        server.start()
        return CloudBackend(
            pb = PocketBaseClient(
                // Deliberately trailing-slashed, like AppContainer.CLOUD_BASE_URL.
                baseUrl = server.url("/").toString(),
                client = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
            ),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun `a unique-violation 400 maps to USERNAME_TAKEN and the path never double-slashes`() = runBlocking {
        val backend = backendAnswering(
            status = 400,
            body = """{"code":400,"message":"Failed to create record.","data":""" +
                """{"username":{"code":"validation_not_unique","message":"Value must be unique"}}}""",
        )
        val result = backend.auth.signUp("taken.name", "pw123456", "secret-passphrase")

        assertTrue(result is CloudLoginResult.Failure)
        assertEquals(CloudError.USERNAME_TAKEN, (result as CloudLoginResult.Failure).error)

        val recorded = server.takeRequest()
        assertEquals(
            "trailing-slash base must not produce a double-slash wire path",
            "/api/collections/users/records",
            recorded.path,
        )
    }

    @Test
    fun `a pattern-rejection 400 stays HTTP so the generic banner shows`() = runBlocking {
        val backend = backendAnswering(
            status = 400,
            body = """{"code":400,"message":"Failed to create record.","data":""" +
                """{"username":{"code":"validation_pattern_mismatch","message":"Invalid value"}}}""",
        )
        val result = backend.auth.signUp("x", "pw123456", "secret-passphrase")

        assertTrue(result is CloudLoginResult.Failure)
        assertEquals("not USERNAME_TAKEN — the name was invalid, not taken", CloudError.HTTP(400), (result as CloudLoginResult.Failure).error)
    }
}
