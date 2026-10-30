package app.dandle.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.dandle.data.api.ChildDto
import app.dandle.data.api.PaginatedDto
import app.dandle.data.api.ProfileDto
import app.dandle.data.prefs.SessionStore
import app.dandle.domain.NormalizedServerUrl
import app.dandle.domain.ServerClock
import app.dandle.domain.normalizeServerUrl
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Distinct login failures (spec §1.1.5) — the UI owns the user-facing copy. */
sealed interface LoginError {
    /** DNS/connection/timeout failure. */
    data object Unreachable : LoginError

    /** Form login rejected (re-rendered login page or 403), or profile/children 401/403. */
    data object BadCredentials : LoginError

    /** Login succeeded but the account sees zero children (spec: create one on the web first). */
    data object NoChildren : LoginError

    /** Any other non-2xx. */
    data class HttpError(val status: Int) : LoginError
}

/** Progressive login step 1: is this URL a reachable Baby Buddy server? */
sealed interface UrlVerifyResult {
    /** The server answered (any HTTP code — auth comes in step 2). */
    data class Reached(val server: NormalizedServerUrl, val cleartext: Boolean) : UrlVerifyResult
    data object Unreachable : UrlVerifyResult
    data class HttpError(val status: Int) : UrlVerifyResult
}

sealed interface LoginResult {
    data class Success(val server: NormalizedServerUrl, val apiKey: String) : LoginResult
    data class Failure(val error: LoginError) : LoginResult
}

/**
 * Password login, replicating the original client's flow (brief 01 §3; research doc §2/§3.1):
 *
 * 1. `GET {url}/login/`          → csrftoken cookie + csrfmiddlewaretoken hidden input
 * 2. `POST {url}/login/` form    → credentials + csrf + `next` + `Referer`; no redirect follow;
 *                                  a 3xx with `sessionid` cookie = success
 * 3. `GET {url}/api/profile`     → `api_key` (session-authenticated)
 * 4. `GET {url}/api/children/`   → token-authenticated validation; zero children = NoChildren
 *
 * 5. Success persists serverUrl + apiKey + username (SessionStore). Server clock offsets are
 *    captured from every response's `Date` header by the client's interceptor.
 *
 * Pure JVM (OkHttp); tests drive it against MockWebServer.
 */
class LoginFlow(
    private val client: OkHttpClient,
    private val json: Json,
    private val sessionStore: SessionStore,
) {

    /** BbLog tag for the login dance (see app.dandle.log.BbLog). */
    private val TAG = app.dandle.log.BbLog.TAG_LOGIN

    suspend fun login(rawUrl: String, username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            val server = normalizeServerUrl(rawUrl)
            try {
                loginChecked(server, username.trim(), password)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LoginResult.Failure(LoginError.Unreachable)
            }
        }

    /**
     * T8 (Priya): self-hosters mint API keys for scripts — let them paste one instead of
     * handing over a password. The key is validated exactly like password login's last step:
     * `GET /api/children/` with `Authorization: Token <key>` (200 + count > 0 = in), then a
     * best-effort profile read for a display name. Saves the SAME session shape as form login.
     */
    suspend fun loginWithToken(rawUrl: String, apiKey: String): LoginResult =
        withContext(Dispatchers.IO) {
            val server = normalizeServerUrl(rawUrl)
            try {
                tokenChecked(server, apiKey.trim())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LoginResult.Failure(LoginError.Unreachable)
            }
        }

    /**
     * Progressive login step 1 (owner directive): probe the URL before asking for
     * credentials. Any HTTP response — including the 401 an unauthenticated
     * /api/children/ probe gets — means a reachable server; only connection-level
     * failures (DNS, timeout, refused) are "unreachable". Cleartext URLs are
     * reported so the UI can warn before credentials are entered.
     */
    suspend fun verifyUrl(rawUrl: String): UrlVerifyResult = withContext(Dispatchers.IO) {
        try {
            val server = normalizeServerUrl(rawUrl)
            val probe = client.newCall(
                Request.Builder()
                    .url("${server.url}/api/children/?limit=1")
                    .get()
                    .build()
            ).execute()
            probe.use { probeResponse ->
                when {
                    probeResponse.code == 404 ->
                        UrlVerifyResult.HttpError(404) // reachable host, but no Baby Buddy API here
                    else -> UrlVerifyResult.Reached(server, server.isCleartext)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            UrlVerifyResult.Unreachable
        }
    }

    private suspend fun tokenChecked(server: NormalizedServerUrl, apiKey: String): LoginResult {
        if (apiKey.isEmpty()) return LoginResult.Failure(LoginError.BadCredentials)
        val base = server.url

        // -- Validation: children list, token-authenticated (the password path's step 4) ----
        val childrenResponse = client.newCall(
            Request.Builder()
                .url("$base/api/children/?limit=1")
                .get()
                .header("Authorization", "Token $apiKey")
                .build()
        ).execute()
        childrenResponse.use { children ->
            when {
                children.code == 401 || children.code == 403 ->
                    return LoginResult.Failure(LoginError.BadCredentials)
                !children.isSuccessful ->
                    return LoginResult.Failure(LoginError.HttpError(children.code))
            }
            val page = runCatching {
                json.decodeFromString<PaginatedDto<ChildDto>>(children.body?.string().orEmpty())
            }.getOrNull() ?: return LoginResult.Failure(LoginError.HttpError(children.code))
            if (page.count == 0) return LoginResult.Failure(LoginError.NoChildren)

            // -- Display name: profile read is best-effort; the login works without it. ----
            val username = runCatching {
                client.newCall(
                    Request.Builder()
                        .url("$base/api/profile")
                        .get()
                        .header("Authorization", "Token $apiKey")
                        .build()
                ).execute()
            }.getOrNull()?.use { profile ->
                runCatching {
                    json.decodeFromString<ProfileDto>(profile.body?.string().orEmpty())
                }.getOrNull()?.user?.username
            }.orEmpty().ifBlank { "API key" }

            sessionStore.saveLogin(serverUrl = base, apiKey = apiKey, username = username)
            app.dandle.log.BbLog.d(TAG, "pasted key stored (user=$username)")
            return LoginResult.Success(server, apiKey)
        }
    }

    private suspend fun loginChecked(
        server: NormalizedServerUrl,
        username: String,
        password: String,
    ): LoginResult {
        val base = server.url

        // -- Step 1: login page (csrf cookie + hidden input) ----------------------
        val loginPageResponse = client.newCall(
            Request.Builder().url("$base/login/").get().build()
        ).execute()
        loginPageResponse.use { resp ->
            if (!resp.isSuccessful) return LoginResult.Failure(LoginError.HttpError(resp.code))
            val cookies = parseSetCookies(resp)
            val csrfCookie = cookies["csrftoken"] ?: return LoginResult.Failure(LoginError.HttpError(resp.code))
            val csrfToken = extractCsrfInput(resp.body?.string())
                ?: return LoginResult.Failure(LoginError.HttpError(resp.code))
            app.dandle.log.BbLog.d(TAG, "csrf fetched (${resp.code})")

            // -- Step 2: form login (no redirect follow; 3xx + sessionid = OK) ----
            val form = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("csrfmiddlewaretoken", csrfToken)
                .add("next", "/")
                .build()
            val loginPostResponse = client.newCall(
                Request.Builder()
                    .url("$base/login/")
                    .post(form)
                    .header("Cookie", "csrftoken=$csrfCookie")
                    .header("Referer", "$base/login/")
                    .build()
            ).execute()
            loginPostResponse.use { post ->
                val postCookies = parseSetCookies(post)
                val sessionCookie = postCookies["sessionid"]
                app.dandle.log.BbLog.d(TAG, "form posted status=${post.code} session=${sessionCookie != null}")
                if (post.code == 403 || post.isSuccessful) {
                    // 403 = bad csrf/credentials; a 200 re-render of the login form = bad creds
                    // (research doc §3.1: "403 → Invalid username or password").
                    return LoginResult.Failure(LoginError.BadCredentials)
                }
                if (!post.isRedirect || sessionCookie == null) {
                    return LoginResult.Failure(LoginError.HttpError(post.code))
                }

                // -- Step 3: profile → api_key (session cookie) -------------------
                val profileResponse = client.newCall(
                    Request.Builder()
                        .url("$base/api/profile")
                        .get()
                        .header("Cookie", buildCookieHeader(mapOf("csrftoken" to csrfCookie) + postCookies))
                        .build()
                ).execute()
                profileResponse.use { profile ->
                    app.dandle.log.BbLog.d(TAG, "profile fetched (${profile.code})")
                    when {
                        profile.code == 401 || profile.code == 403 ->
                            return LoginResult.Failure(LoginError.BadCredentials)
                        !profile.isSuccessful ->
                            return LoginResult.Failure(LoginError.HttpError(profile.code))
                    }
                    val profileDto = runCatching {
                        json.decodeFromString<ProfileDto>(profile.body?.string().orEmpty())
                    }.getOrNull()
                    val apiKey = profileDto?.apiKey
                        ?: return LoginResult.Failure(LoginError.HttpError(profile.code))

                    // -- Step 4: validate with the children list (token auth) -----
                    val childrenResponse = client.newCall(
                        Request.Builder()
                            .url("$base/api/children/?limit=1")
                            .get()
                            .header("Authorization", "Token $apiKey")
                            .build()
                    ).execute()
                    childrenResponse.use { children ->
                        when {
                            children.code == 401 || children.code == 403 ->
                                return LoginResult.Failure(LoginError.BadCredentials)
                            !children.isSuccessful ->
                                return LoginResult.Failure(LoginError.HttpError(children.code))
                        }
                        val page = runCatching {
                            json.decodeFromString<PaginatedDto<ChildDto>>(
                                children.body?.string().orEmpty()
                            )
                        }.getOrNull() ?: return LoginResult.Failure(LoginError.HttpError(children.code))

                        if (page.count == 0) {
                            return LoginResult.Failure(LoginError.NoChildren)
                        }

                        sessionStore.saveLogin(
                            serverUrl = base,
                            apiKey = apiKey,
                            username = username,
                        )
                        app.dandle.log.BbLog.d(TAG, "key stored for user=$username")
                        return LoginResult.Success(server, apiKey)
                    }
                }
            }
        }
    }

    private fun parseSetCookies(response: Response): Map<String, String> =
        response.headers("Set-Cookie").mapNotNull { header ->
            val pair = header.substringBefore(';').split('=', limit = 2)
            if (pair.size == 2) pair[0].trim() to pair[1].trim() else null
        }.toMap()

    private fun buildCookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    internal fun extractCsrfInput(html: String?): String? {
        if (html == null) return null
        for (tag in Regex("<input[^>]*>").findAll(html)) {
            val tagText = tag.value
            val name = Regex("""name="([^"]*)"""").find(tagText)?.groupValues?.get(1)
            if (name == "csrfmiddlewaretoken") {
                return Regex("""value="([^"]*)"""").find(tagText)?.groupValues?.get(1)
            }
        }
        return null
    }
}
