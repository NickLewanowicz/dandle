p = 'app/app/src/main/java/app/dandle/data/auth/LoginFlow.kt'
s = open(p).read()

# sealed result for URL verification (progressive login step 1)
anchor = 'sealed interface LoginResult {'
verify = '''/** Progressive login step 1: is this URL a reachable Baby Buddy server? */
sealed interface UrlVerifyResult {
    /** The server answered (any HTTP code — auth comes in step 2). */
    data class Reached(val server: NormalizedServerUrl, val cleartext: Boolean) : UrlVerifyResult
    data object Unreachable : UrlVerifyResult
    data class HttpError(val status: Int) : UrlVerifyResult
}

sealed interface LoginResult {'''
assert anchor in s
s = s.replace(anchor, verify, 1)

# verifyUrl method — insert before tokenChecked's private helper or after loginWithToken;
# anchor on loginWithToken's closing + tokenChecked start
anchor2 = '    private suspend fun tokenChecked(server: NormalizedServerUrl, apiKey: String): LoginResult {'
verifyfn = '''    /**
     * Progressive login step 1 (owner directive): probe the URL before asking for
     * credentials. Any HTTP response — including the 401 an unauthenticated
     * /api/children/ probe gets — means a reachable server; only connection-level
     * failures (DNS, timeout, refused) are "unreachable". Cleartext URLs are
     * reported so the UI can warn before credentials are entered.
     */
    suspend fun verifyUrl(rawUrl: String): UrlVerifyResult = try {
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
    } catch (e: Exception) {
        UrlVerifyResult.Unreachable
    }

''' + anchor2
assert anchor2 in s
s = s.replace(anchor2, verifyfn, 1)
open(p,'w').write(s)
print("verifyUrl added")
