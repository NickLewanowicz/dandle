package app.dandle.data.auth

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import app.dandle.data.prefs.SessionStore
import app.dandle.domain.Clock
import app.dandle.domain.ServerClock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.Locale

/**
 * Login flow against MockWebServer (brief 01 §3 sequence + every error branch).
 */
class LoginFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var sessionFile: File

    private val localNow = 1_700_000_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionFile = Files.createTempDirectory("login-test").resolve("session.preferences_pb").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        sessionFile.delete()
    }

    private fun sessionStore() = SessionStore(sessionFile.absolutePath.toPath())

    private fun loginClient(serverClock: ServerClock) = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(ServerClockInterceptor(serverClock))
        .build()

    private fun flow(client: OkHttpClient, store: SessionStore) =
        LoginFlow(client = client, json = Json { ignoreUnknownKeys = true; explicitNulls = false }, sessionStore = store)

    private fun dateHeaderFor(epochMillis: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME
            .withLocale(Locale.US)
            .format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC))

    /** Standard happy-path dispatcher; [childrenCount] drives the zero-children branch.
     *  Date offsets are second-granularity (the Date header truncates to whole seconds). */
    private fun happyDispatcher(
        childrenCount: Int = 1,
        profileStatus: Int = 200,
        loginFormStatus: Int = 302,
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            return when {
                path == "/login/" && request.method == "GET" -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Date", dateHeaderFor(localNow + 5_000))
                    .setHeader("Set-Cookie", "csrftoken=CSRF123; Path=/")
                    .setBody(
                        """<html><form><input type="hidden" name="csrfmiddlewaretoken" value="TOKEN789"><input name="next"></form></html>"""
                    )
                path == "/login/" && request.method == "POST" -> MockResponse()
                    .setResponseCode(loginFormStatus)
                    .setHeader("Date", dateHeaderFor(localNow + 60_000))
                    .setHeader("Set-Cookie", "csrftoken=CSRF123; Path=/")
                    .setHeader("Set-Cookie", "sessionid=SESS456; Path=/")
                    .setBody("")
                path == "/api/profile" -> MockResponse()
                    .setResponseCode(profileStatus)
                    .setHeader("Date", dateHeaderFor(localNow + 120_000))
                    .setBody(
                        """{"user":{"id":1,"username":"admin","first_name":"","last_name":"","email":"","is_staff":true},"language":"en-US","timezone":"UTC","api_key":"KEY789"}"""
                    )
                path == "/api/children/" -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Date", dateHeaderFor(localNow + 180_000))
                    .setBody(
                        """{"count":$childrenCount,"next":null,"previous":null,"results":[{"id":1,"first_name":"Emma","last_name":"Stone","birth_date":"2025-01-02","birth_time":"08:30","slug":"emma-stone"}]}"""
                    )
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    // -- Happy path ---------------------------------------------------------------

    @Test
    fun `happy path performs the csrf - form - profile - children dance and persists the session`() = runTest {
        server.dispatcher = happyDispatcher()
        val store = sessionStore()
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), store)
            .login(server.url("/").toString(), "admin", "admin")

        assertTrue("expected Success, got $result", result is LoginResult.Success)
        assertEquals("KEY789", (result as LoginResult.Success).apiKey)

        // Form POST carries credentials + csrf + next, the csrf cookie and a Referer.
        val formGet = server.takeRequest()
        assertEquals("/login/", formGet.path)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/login/", post.path)
        val body = post.body.readUtf8()
        assertTrue(body.contains("username=admin"))
        assertTrue(body.contains("password=admin"))
        assertTrue(body.contains("csrfmiddlewaretoken=TOKEN789"))
        assertTrue(body.contains("next=%2F"))
        assertEquals("csrftoken=CSRF123", post.getHeader("Cookie"))
        assertTrue(post.getHeader("Referer")!!.endsWith("/login/"))

        // Profile is called with the session cookie; children with the token.
        val profile = server.takeRequest()
        assertEquals("/api/profile", profile.path) // no trailing slash
        assertTrue(profile.getHeader("Cookie")!!.contains("sessionid=SESS456"))
        val children = server.takeRequest()
        assertEquals("Token KEY789", children.getHeader("Authorization"))

        runCurrent()
        val session = store.sessionFlow.first()
        assertEquals(server.url("/").toString().trimEnd('/'), session.serverUrl)
        assertEquals("KEY789", session.apiKey)
        assertEquals("admin", session.username)
        assertFalse(session.sessionExpired)
    }

    // -- T8: pasted API key path ---------------------------------------------------

    /** Token-path dispatcher: children validation + optional profile + optional key status. */
    private fun tokenDispatcher(
        childrenStatus: Int = 200,
        childrenCount: Int = 1,
        withProfile: Boolean = true,
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            return when {
                path == "/api/children/" -> MockResponse()
                    .setResponseCode(childrenStatus)
                    .setHeader("Date", dateHeaderFor(localNow + 5_000))
                    .setBody(
                        if (childrenStatus == 200) {
                            """{"count":$childrenCount,"next":null,"previous":null,"results":[{"id":1,"first_name":"Emma","last_name":"Stone","birth_date":"2025-01-02","birth_time":"08:30","slug":"emma-stone"}]}"""
                        } else {
                            """{"detail":"Invalid token."}"""
                        }
                    )
                path == "/api/profile" && withProfile -> MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """{"user":{"id":1,"username":"admin","first_name":"","last_name":"","email":"","is_staff":true},"language":"en-US","timezone":"UTC","api_key":"ignored"}"""
                    )
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `pasted api key validates via children and persists the session (T8)`() = runTest {
        server.dispatcher = tokenDispatcher()
        val store = sessionStore()
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), store)
            .loginWithToken(server.url("/").toString(), "MINTEDKEY123")

        assertTrue("expected Success, got $result", result is LoginResult.Success)
        assertEquals("MINTEDKEY123", (result as LoginResult.Success).apiKey)

        // First request validates the key with the children list; token auth on the wire.
        val children = server.takeRequest()
        assertEquals("/api/children/", children.requestUrl!!.encodedPath)
        assertEquals("Token MINTEDKEY123", children.getHeader("Authorization"))

        // Profile is best-effort (display name only).
        val profile = server.takeRequest()
        assertEquals("/api/profile", profile.requestUrl!!.encodedPath)
        assertEquals("Token MINTEDKEY123", profile.getHeader("Authorization"))

        // The persisted session matches the form-login shape exactly.
        val session = store.sessionFlow.first()
        assertEquals("MINTEDKEY123", session.apiKey)
        assertEquals("admin", session.username)
        assertTrue(
            "session serverUrl should be the mock server root, got ${session.serverUrl}",
            session.serverUrl == server.url("/").toString().trimEnd('/'),
        )
        assertFalse(session.sessionExpired)
    }

    @Test
    fun `pasted api key rejected by the server maps to BadCredentials (T8)`() = runTest {
        server.dispatcher = tokenDispatcher(childrenStatus = 401)
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), sessionStore())
            .loginWithToken(server.url("/").toString(), "WRONGKEY")

        assertTrue("expected Failure, got $result", result is LoginResult.Failure)
        assertEquals(LoginError.BadCredentials, (result as LoginResult.Failure).error)
    }

    @Test
    fun `pasted api key with zero children maps to NoChildren (T8)`() = runTest {
        server.dispatcher = tokenDispatcher(childrenCount = 0)
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), sessionStore())
            .loginWithToken(server.url("/").toString(), "MINTEDKEY123")

        assertTrue(result is LoginResult.Failure)
        assertEquals(LoginError.NoChildren, (result as LoginResult.Failure).error)
    }

    @Test
    fun `unreachable server on the token path maps to Unreachable (T8)`() = runTest {
        val url = server.url("/").toString()
        server.shutdown()
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), sessionStore())
            .loginWithToken(url, "MINTEDKEY123")

        assertTrue(result is LoginResult.Failure)
        assertEquals(LoginError.Unreachable, (result as LoginResult.Failure).error)
    }

    @Test
    fun `server clock offset is captured from Date headers`() = runTest {
        server.dispatcher = happyDispatcher()
        val serverClock = ServerClock(Clock.fixed(localNow))
        flow(loginClient(serverClock), sessionStore())
            .login(server.url("/").toString(), "admin", "admin")
        // Last sample seen by the flow: the children response, dated localNow + 180_000.
        assertEquals(180_000L, serverClock.currentOffsetMillis())
    }

    // -- Error branches -------------------------------------------------------------

    @Test
    fun `re-rendered login form maps to BadCredentials`() = runTest {
        server.dispatcher = happyDispatcher(loginFormStatus = 200) // bad creds re-renders, no redirect
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), sessionStore())
            .login(server.url("/").toString(), "admin", "wrong")
        assertEquals(LoginResult.Failure(LoginError.BadCredentials), result)
    }

    @Test
    fun `403 on form login maps to BadCredentials`() = runTest {
        server.dispatcher = happyDispatcher(loginFormStatus = 403)
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), sessionStore())
            .login(server.url("/").toString(), "admin", "wrong")
        assertEquals(LoginResult.Failure(LoginError.BadCredentials), result)
    }

    @Test
    fun `401 on profile maps to BadCredentials`() = runTest {
        server.dispatcher = happyDispatcher(profileStatus = 401)
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), sessionStore())
            .login(server.url("/").toString(), "admin", "admin")
        assertEquals(LoginResult.Failure(LoginError.BadCredentials), result)
    }

    @Test
    fun `zero children maps to NoChildren`() = runTest {
        server.dispatcher = happyDispatcher(childrenCount = 0)
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), sessionStore())
            .login(server.url("/").toString(), "admin", "admin")
        assertEquals(LoginResult.Failure(LoginError.NoChildren), result)
    }

    @Test
    fun `500 on profile maps to HttpError with status`() = runTest {
        server.dispatcher = happyDispatcher(profileStatus = 500)
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), sessionStore())
            .login(server.url("/").toString(), "admin", "admin")
        assertEquals(LoginResult.Failure(LoginError.HttpError(500)), result)
    }

    @Test
    fun `unreachable server maps to Unreachable`() = runTest {
        server.shutdown() // connection refused
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), sessionStore())
            .login(server.url("/").toString(), "admin", "admin")
        assertEquals(LoginResult.Failure(LoginError.Unreachable), result)
    }

    @Test
    fun `login url normalization is applied before connecting (bare host would need https)`() = runTest {
        // The mock serves plain HTTP, so a scheme-less URL (normalized to https://) cannot
        // reach it — this pins that LoginFlow uses the normalized URL end-to-end.
        // Scheme/trim rules themselves are unit-tested in ServerUrlNormalizerTest.
        server.shutdown() // nothing to reach either way; Unreachable is the expected branch
        val result = flow(loginClient(ServerClock(Clock.fixed(localNow))), sessionStore())
            .login("${server.hostName}:${server.port}", "admin", "admin") // no scheme → https://
        assertEquals(LoginResult.Failure(LoginError.Unreachable), result)
    }
}
