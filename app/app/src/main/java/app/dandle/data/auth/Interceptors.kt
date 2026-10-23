package app.dandle.data.auth

import app.dandle.domain.ServerClock
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Token <api_key>` (research doc §2) to every request when a key is
 * available. The key comes through a provider lambda so the interceptor never blocks on
 * DataStore reads (the DI container mirrors the session state in memory).
 */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Token $token")
                .build()
        }
        return chain.proceed(request)
    }
}

/**
 * Captures the server's clock skew from the `Date` response header of every response
 * (brief 00 pattern #3). No-ops when the header is absent.
 */
class ServerClockInterceptor(private val serverClock: ServerClock) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        response.headers.getDate("Date")?.time?.let { serverEpoch ->
            serverClock.onServerTimeSample(serverEpoch)
        }
        return response
    }
}
